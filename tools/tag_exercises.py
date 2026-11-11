#!/usr/bin/env python3
"""给 assets/exercises.json 的 2303 条动作补三个字段：branch / skillLevel / isBodyweightFriendly。

用法：
    python tools/tag_exercises.py            # 就地改写（自动备份 .bak）
    python tools/tag_exercises.py --check    # 只校验不写盘

三条规则：
1. branch        —— 按 target（主发力肌群）+ 动作名关键词自动归类；
                    48 个主干动作强制归到它所属的那条链（自动规则会看错少数跨系统动作，
                    比如拉力链的握力动作 target 是前臂）。
2. skillLevel    —— 48 个主干动作 1~8，其余全部 0（自由训练池）。
3. isBodyweightFriendly —— 徒手 / 弹力带 / 哑铃 / 壶铃 / 跳绳算「居家可练」，
                    器械类（跑步机、椭圆机、雪橇…）为 False。

主干动作表是唯一的事实来源，Kotlin 侧只读这些字段，不再维护第二份配置。
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSET = ROOT / "app" / "src" / "main" / "assets" / "exercises.json"
# 备份放在 assets 外面：assets 下的任何文件都会被打进 APK
BACKUP = ROOT / "tools" / "exercises.json.bak"

# ---------------------------------------------------------------- 流派归类

# target 是上游的英文肌群枚举，值域很小（22 个），直接查表比模糊匹配稳。
TARGET_BRANCH = {
    "pectorals": "PUSH",
    "delts": "PUSH",
    "triceps": "PUSH",
    "lats": "PULL",
    "upper back": "PULL",
    "biceps": "PULL",
    "forearms": "PULL",
    "traps": "PULL",
    "levator scapulae": "PULL",
    "quads": "LEGS",
    "hamstrings": "LEGS",
    "glutes": "LEGS",
    "calves": "LEGS",
    "adductors": "LEGS",
    "abductors": "LEGS",
    "shins": "LEGS",
    "abs": "CORE",
    "obliques": "CORE",
    "spine": "CORE",
    "hip flexors": "CORE",
}

BODY_PART_BRANCH = {
    "chest": "PUSH",
    "shoulders": "PUSH",
    "back": "PULL",
    "upper arms": "PULL",
    "lower arms": "PULL",
    "upper legs": "LEGS",
    "lower legs": "LEGS",
    "waist": "CORE",
    "neck": "PULL",
}

# 心肺类动作靠名字区分快慢：爆发间歇 → HIIT，稳态有氧 → CARDIO。
HIIT_WORDS = (
    "波比", "开合跳", "高抬腿", "登山", "冲刺", "间歇", "战斗绳", "跳箱", "深蹲跳",
    "burpee", "jumping jack", "high knee", "mountain climber", "sprint", "tabata",
    "battle rope", "box jump", "jump squat", "tuck jump", "skater",
)
CARDIO_WORDS = (
    "绳", "跑", "走", "踏", "骑", "车", "机", "滑雪", "爬楼", "游泳",
    "rope", "run", "walk", "jog", "bike", "cycl", "elliptical", "treadmill",
    "ski", "step", "stride", "swim",
)

HOME_EQUIPMENT = {"body weight", "band", "dumbbell", "kettlebell", "rope"}


def branch_of(item: dict) -> str:
    """先看肌群，心肺类再看动作名。返回六大流派之一。"""
    target = (item.get("target") or "").strip().lower()
    category = (item.get("category") or "").strip().lower()
    name = f"{item.get('name', '')} {item.get('nameEn', '')}".lower()

    if target == "cardiovascular system" or category == "cardio":
        if any(w in name for w in HIIT_WORDS):
            return "HIIT"
        if any(w in name for w in CARDIO_WORDS):
            return "CARDIO"
        # 没命中关键词的心肺动作默认当稳态有氧，比默认 HIIT 更保守
        return "CARDIO"

    if target in TARGET_BRANCH:
        return TARGET_BRANCH[target]

    body_part = (item.get("bodyPart") or "").strip().lower()
    return BODY_PART_BRANCH.get(body_part, "CORE")


# ---------------------------------------------------------------- 48 个主干节点

# (流派, 阶, 动作 id, 口径, 组数, 目标值, 展示文案)
# 每条链 8 阶、动作互不重复 —— 新模型里「流派 + 阶」唯一确定一个动作，
# 同一个动作占两阶的话 skillLevel 这个单值字段就存不下两套标准。
SETS_REPS = "SETS_AND_REPS"
SETS_DUR = "SETS_AND_DURATION"
VOLUME = "TOTAL_VOLUME_REPS"
CONT = "CONTINUOUS_DURATION"

BOSSES = [
    # 推力：减杠杆 → 标准 → 偏重 → 单侧
    ("PUSH", 1, "0659", SETS_REPS, 3, 25, "3 组 × 25 次"),   # 墙壁俯卧撑
    ("PUSH", 2, "0493", SETS_REPS, 3, 20, "3 组 × 20 次"),   # 上斜俯卧撑
    ("PUSH", 3, "1182", SETS_REPS, 3, 15, "3 组 × 15 次"),   # 跪姿俯卧撑
    ("PUSH", 4, "0662", SETS_REPS, 3, 15, "3 组 × 15 次"),   # 标准俯卧撑
    ("PUSH", 5, "0283", SETS_REPS, 3, 12, "3 组 × 12 次"),   # 钻石俯卧撑
    ("PUSH", 6, "2921", SETS_REPS, 3, 12, "3 组 × 12 次"),   # 派克俯卧撑
    ("PUSH", 7, "3294", SETS_REPS, 3, 8, "3 组 × 每侧 8 次"),  # 弓箭手俯卧撑
    ("PUSH", 8, "0471", SETS_REPS, 3, 5, "3 组 × 5 次"),     # 倒立俯卧撑

    # 拉力：握力 → 肩胛 → 水平拉 → 垂直拉
    ("PULL", 1, "4177", SETS_REPS, 3, 10, "3 组 × 10 次"),   # 悬垂肩胛耸肩
    ("PULL", 2, "0688", SETS_REPS, 3, 15, "3 组 × 15 次"),   # 肩胛引体向上
    ("PULL", 3, "0499", SETS_REPS, 3, 15, "3 组 × 15 次"),   # 反向划船
    ("PULL", 4, "7385", SETS_REPS, 3, 8, "3 组 × 8 次"),     # 离心引体
    ("PULL", 5, "1326", SETS_REPS, 3, 10, "3 组 × 10 次"),   # 反握引体
    ("PULL", 6, "0652", SETS_REPS, 3, 10, "3 组 × 10 次"),   # 引体向上
    ("PULL", 7, "3418", SETS_REPS, 3, 8, "3 组 × 8 次"),     # L 型引体
    ("PULL", 8, "0631", SETS_REPS, 3, 3, "3 组 × 3 次"),     # 双力臂

    # 下肢：静力 → 双腿 → 分腿 → 单腿
    ("LEGS", 1, "0734", SETS_DUR, 3, 60, "3 组 × 60 秒"),    # 靠墙静蹲
    ("LEGS", 2, "4797", SETS_REPS, 3, 20, "3 组 × 20 次"),   # 支撑深蹲
    ("LEGS", 3, "1197", SETS_REPS, 3, 25, "3 组 × 25 次"),   # 徒手全蹲
    ("LEGS", 4, "3743", SETS_REPS, 3, 12, "3 组 × 每侧 12 次"),  # 保加利亚分腿蹲
    ("LEGS", 5, "2569", SETS_REPS, 3, 10, "3 组 × 每侧 10 次"),  # 哥萨克深蹲
    ("LEGS", 6, "1762", SETS_REPS, 3, 8, "3 组 × 每侧 8 次"),    # 辅助手枪蹲
    ("LEGS", 7, "3366", SETS_REPS, 3, 8, "3 组 × 每侧 8 次"),    # 虾式深蹲
    ("LEGS", 8, "1759", SETS_REPS, 3, 5, "3 组 × 每侧 5 次"),    # 手枪深蹲

    # 核心：前链 → 抗伸展 → 悬垂 → 支撑
    ("CORE", 1, "0276", SETS_REPS, 3, 20, "3 组 × 20 次"),   # 死虫式
    ("CORE", 2, "0463", SETS_DUR, 3, 60, "3 组 × 60 秒"),    # 平板支撑
    ("CORE", 3, "1163", SETS_REPS, 3, 15, "3 组 × 15 次"),   # 仰卧抬腿
    ("CORE", 4, "1246", SETS_DUR, 3, 45, "3 组 × 45 秒"),    # 空心支撑
    ("CORE", 5, "10735", SETS_REPS, 3, 15, "3 组 × 15 次"),  # 悬垂举膝
    ("CORE", 6, "3891", SETS_REPS, 3, 10, "3 组 × 10 次"),   # 悬垂脚触杠
    ("CORE", 7, "3969", SETS_DUR, 3, 30, "3 组 × 30 秒"),    # 团身收腹
    ("CORE", 8, "1402", SETS_DUR, 3, 15, "3 组 × 15 秒"),    # L 坐

    # 爆发心肺：低冲击 → 跳跃 → 波比 → 极限
    ("HIIT", 1, "4420", SETS_DUR, 6, 20, "6 轮 × 20 秒"),    # 原地踏步
    ("HIIT", 2, "3094", SETS_DUR, 8, 30, "8 轮 × 30 秒"),    # 开合跳
    ("HIIT", 3, "3556", SETS_DUR, 8, 30, "8 轮 × 30 秒"),    # 高抬腿跑
    ("HIIT", 4, "0630", SETS_DUR, 8, 30, "8 轮 × 30 秒"),    # 登山者
    ("HIIT", 5, "5039", SETS_DUR, 8, 30, "8 轮 × 30 秒"),    # 无声波比
    ("HIIT", 6, "1160", SETS_REPS, 10, 10, "10 组 × 10 次（EMOM）"),  # 波比
    ("HIIT", 7, "3457", VOLUME, 1, 65, "单场累计 65 次"),     # 波比跳加俯卧撑
    ("HIIT", 8, "5187", VOLUME, 1, 100, "单场累计 100 次"),   # 波比立定跳远加俯卧撑

    # 有氧耐力：走 → 跑 → 跳绳 → 变速 → 长距离
    ("CARDIO", 1, "3004", CONT, 1, 900, "连续 15 分钟"),       # 快走
    ("CARDIO", 2, "4004", SETS_DUR, 10, 60, "10 轮 × 1 分钟"),  # 原地慢跑
    ("CARDIO", 3, "6100", SETS_DUR, 6, 180, "6 轮 × 3 分钟"),   # 徒手跳绳
    ("CARDIO", 4, "2612", CONT, 1, 900, "连续 15 分钟"),        # 跳绳
    ("CARDIO", 5, "3677", CONT, 1, 1800, "连续 30 分钟"),       # 高抬腿跳绳
    ("CARDIO", 6, "4229", SETS_DUR, 8, 30, "8 轮 × 30 秒冲刺"),  # 冲刺跑
    ("CARDIO", 7, "0685", CONT, 1, 2100, "连续 35 分钟（5 公里折算）"),  # 跑步
    ("CARDIO", 8, "5038", VOLUME, 1, 100, "单场累计 100 次双摇"),  # 双摇跳绳
]

BRANCHES = ["PUSH", "PULL", "LEGS", "CORE", "HIIT", "CARDIO"]

# 上游中文名有几处明显错译 / 噪声后缀，主干动作是用户天天看到的名字，这里改掉。
# 只动 name，nameEn / 动图 / 步骤一律按上游原样保留，方便日后重新拉取时比对。
NAME_FIX = {
    "3294": "弓箭手俯卧撑",       # 上游写「弓步俯卧撑」，英文是 archer push up
    "0631": "双力臂",             # 上游写「双杠上推」，英文是 muscle up
    "1759": "单腿深蹲（手枪式）",  # 上游带「男」后缀（male 变体），不适合当显示名
    "0734": "靠墙静蹲",           # 上游写「靠墙坐」
    "0463": "平板支撑",           # 上游写「前平板支撑」
}

# 主干动作是考级动作，必须真能练；拉伸/柔韧/康复类条目不能当门槛。
NON_TRAINING = {"stretching", "mobility", "yoga", "rehab"}


def validate(items: list[dict]) -> list[str]:
    """写盘前先自查，任何一条不成立都直接退出，不留半套数据。"""
    errors: list[str] = []
    by_id = {i["id"]: i for i in items}

    seen: dict[tuple[str, int], str] = {}
    used: dict[str, tuple[str, int]] = {}
    for branch, tier, eid, metric, sets, value, text in BOSSES:
        if eid not in by_id:
            errors.append(f"{branch}-{tier}: 动作库没有 {eid}")
            continue
        if (branch, tier) in seen:
            errors.append(f"{branch}-{tier} 重复：{seen[(branch, tier)]} / {eid}")
        seen[(branch, tier)] = eid
        if eid in used:
            # 一个动作只能有一个 skillLevel，同动作占两阶会静默丢掉一阶
            errors.append(f"{eid}（{by_id[eid]['name']}）同时出现在 {used[eid]} 和 {(branch, tier)}")
        used[eid] = (branch, tier)
        if metric not in (SETS_REPS, SETS_DUR, VOLUME, CONT):
            errors.append(f"{eid}: 未知口径 {metric}")
        if sets < 1 or value < 1:
            errors.append(f"{eid}: 组数/目标值必须 ≥ 1")
        if by_id[eid]["category"] in NON_TRAINING:
            errors.append(f"{branch}-{tier}: {by_id[eid]['name']} 属于 {by_id[eid]['category']}，不能当考级动作")

    for branch in BRANCHES:
        tiers = sorted(t for b, t, *_ in BOSSES if b == branch)
        if tiers != list(range(1, 9)):
            errors.append(f"{branch} 的阶不连续：{tiers}")

    for eid in NAME_FIX:
        if eid not in by_id:
            errors.append(f"NAME_FIX 里的 {eid} 在动作库里不存在")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验，不写盘")
    args = parser.parse_args()

    data = json.loads(ASSET.read_text(encoding="utf-8"))
    items = data["items"]

    errors = validate(items)
    if errors:
        print("校验失败：", file=sys.stderr)
        for e in errors:
            print("  -", e, file=sys.stderr)
        return 1

    boss_by_id = {eid: (branch, tier, metric, sets, value, text)
                  for branch, tier, eid, metric, sets, value, text in BOSSES}

    branch_counts: Counter[str] = Counter()
    skill_counts: Counter[int] = Counter()
    friendly = 0

    for item in items:
        boss = boss_by_id.get(item["id"])
        branch = boss[0] if boss else branch_of(item)
        item["branch"] = branch
        item["skillLevel"] = boss[1] if boss else 0
        item["isBodyweightFriendly"] = (item.get("equipment") or "").strip().lower() in HOME_EQUIPMENT

        fix = NAME_FIX.get(item["id"])
        if fix:
            item["name"] = fix

        if boss:
            _, _, metric, sets, value, text = boss
            item["unlockType"] = metric
            item["unlockSets"] = sets
            item["unlockValue"] = value
            item["unlockText"] = text
            # 主干动作全是可以徒手完成的，装备字段偶尔标得不一致，这里以人工表为准
            item["isBodyweightFriendly"] = True
        else:
            item["unlockType"] = SETS_REPS
            item["unlockSets"] = 3
            item["unlockValue"] = 0
            item["unlockText"] = ""

        branch_counts[branch] += 1
        skill_counts[item["skillLevel"]] += 1
        friendly += 1 if item["isBodyweightFriendly"] else 0

    data["version"] = 2
    data["taggedAt"] = "tag_exercises.py"

    print(f"动作总数 {len(items)}")
    print("流派分布：" + "  ".join(f"{b}={branch_counts[b]}" for b in BRANCHES))
    print(f"主干动作 {sum(v for k, v in skill_counts.items() if k)} 个 / 自由池 {skill_counts[0]} 个")
    print(f"居家可练 {friendly} 个 / 器械 {len(items) - friendly} 个")

    print("\n各链前 3 个支线动作（同主肌群优先、排除拉伸类）：")
    # 与 Kotlin 侧 getAccessoryPool 的排序保持一致：同主肌群 → 训练类 → 名称
    pool: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for item in items:
        if item["skillLevel"] == 0 and item["isBodyweightFriendly"]:
            pool[(item["branch"], item["target"])].append(item)
    for branch in BRANCHES:
        boss = next(x[2] for x in BOSSES if x[0] == branch)
        boss_item = next(i for i in items if i["id"] == boss)
        same = sorted(
            pool.get((branch, boss_item["target"]), []),
            key=lambda i: (i["category"] in NON_TRAINING, i["name"]),
        )[:3]
        names = "、".join(i["name"] for i in same) or "（同主肌群没有支线动作）"
        print(f"  {branch:6} 主干 {boss_item['name']}（{boss_item['target']}）→ {names}")

    if args.check:
        print("\n--check：未写盘")
        return 0

    backup = BACKUP
    if not backup.exists():
        shutil.copy2(ASSET, backup)
    ASSET.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n已写入 {ASSET.relative_to(ROOT)}（备份 {backup.relative_to(ROOT)}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
