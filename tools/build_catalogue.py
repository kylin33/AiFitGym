#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 openGym 的 catalogue/ 动作库筛成 App 内置的徒手 + 有氧动作包。

用法（默认参数即可，在 FitGym 工程根目录执行）：

    python tools/build_catalogue.py

默认行为：
  1. 遍历 <catalogue>/exercises/*.json；
  2. 保留 equipment == "body weight"（徒手）**或** category == "cardio"（有氧）的动作；
  3. 合并 <catalogue>/i18n/zh.json 的中文名称与中文步骤；
  4. 把对应的 catalogue/media/still/<id>.webp 复制到 assets/gifs/ 下；
  5. 输出 app/src/main/assets/exercises.json + taxonomy.json（assets 根目录）。

参数都是可选的，见 --help。
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import shutil
import sys
from collections import Counter, OrderedDict

# ---------------------------------------------------------------- 中文词表
# openGym 的 taxonomy 字段是英文枚举，App 里要显示中文，这里做一次映射。
BODY_PART_CN = {
    "waist": "腰腹",
    "upper legs": "大腿",
    "back": "背部",
    "shoulders": "肩部",
    "upper arms": "上臂",
    "chest": "胸部",
    "full body": "全身",
    "lower legs": "小腿",
    "lower arms": "前臂",
    "cardio": "心肺",
    "neck": "颈部",
}

CATEGORY_CN = {
    "strength": "力量",
    "calisthenics": "徒手体操",
    "stretching": "拉伸",
    "mobility": "灵活度",
    "plyometrics": "爆发力",
    "isometric": "等长收缩",
    "cardio": "有氧",
    "yoga": "瑜伽",
    "olympic": "举重",
    "pilates": "普拉提",
    "rehab": "康复",
    "combat": "搏击",
}

EQUIPMENT_CN = {
    "body weight": "徒手",
    "dumbbell": "哑铃",
    "cable": "龙门架",
    "barbell": "杠铃",
    "leverage machine": "固定器械",
    "band": "弹力带",
    "resistance band": "阻力带",
    "kettlebell": "壶铃",
    "weighted": "负重",
    "suspension trainer": "悬挂带",
    "smith machine": "史密斯机",
    "stability ball": "瑞士球",
    "medicine ball": "药球",
    "ez barbell": "曲杆",
    "roller": "滚轴",
    "sled machine": "雪橇机",
    "massage ball": "按摩球",
    "weight plate": "杠铃片",
    "rope": "绳索",
    "assisted": "辅助",
    "landmine": "地雷管",
    "bosu ball": "波速球",
    "sandbag": "沙袋",
    "trap bar": "六角杠",
    "olympic barbell": "奥杆",
    "stationary bike": "固定单车",
    "elliptical machine": "椭圆机",
    "wheel roller": "健腹轮",
    "macebell": "锤铃",
    "tire": "轮胎",
    "hammer": "锤",
    "upper body ergometer": "上肢功率车",
    "skierg machine": "滑雪机",
    "stepmill machine": "爬楼机",
}

TARGET_CN = {
    "abs": "腹肌",
    "glutes": "臀肌",
    "delts": "三角肌",
    "quads": "股四头肌",
    "pectorals": "胸肌",
    "upper back": "上背",
    "biceps": "肱二头肌",
    "triceps": "肱三头肌",
    "lats": "背阔肌",
    "obliques": "腹斜肌",
    "hamstrings": "腘绳肌",
    "calves": "小腿三头肌",
    "cardiovascular system": "心肺系统",
    "spine": "竖脊肌",
    "forearms": "前臂",
    "abductors": "外展肌",
    "adductors": "内收肌",
    "levator scapulae": "肩胛提肌",
    "traps": "斜方肌",
    "shins": "胫骨前肌",
    "hip flexors": "髋屈肌",
    "serratus anterior": "前锯肌",
}

MUSCLE_CN = {
    "core": "核心",
    "shoulders": "肩部",
    "glutes": "臀肌",
    "hamstrings": "腘绳肌",
    "triceps": "肱三头肌",
    "hip flexors": "髋屈肌",
    "quadriceps": "股四头肌",
    "forearms": "前臂",
    "calves": "小腿",
    "lower back": "下背",
    "upper back": "上背",
    "biceps": "肱二头肌",
    "obliques": "腹斜肌",
    "rhomboids": "菱形肌",
    "trapezius": "斜方肌",
    "rear deltoids": "三角肌后束",
    "lats": "背阔肌",
    "upper chest": "上胸",
    "adductors": "内收肌",
    "chest": "胸肌",
    "grip muscles": "握力肌群",
    "ankles": "踝关节",
    "lower abs": "下腹",
    "rotator cuff": "肩袖",
    "brachialis": "肱肌",
    "abdominals": "腹部",
    "feet": "足部",
    "soleus": "比目鱼肌",
    "wrist flexors": "腕屈肌",
    "groin": "腹股沟",
    "inner thighs": "大腿内侧",
    "sternocleidomastoid": "胸锁乳突肌",
    "wrist extensors": "腕伸肌",
    "shins": "胫骨",
    "traps": "斜方肌",
    "hands": "手部",
    "deltoids": "三角肌",
    "back": "背部",
    "latissimus dorsi": "背阔肌",
    "ankle stabilizers": "踝稳定肌",
}

# 动作 JSON 只有 2 个字段的，是仓库里删改后留下的占位文件，直接跳过。
STUB_KEYS = 5


def norm(value) -> str:
    return (value or "").strip().lower()


def load_exercises(exercise_dir: str) -> tuple[list[dict], int]:
    """读取目录下所有动作 JSON，返回 (有效动作, 占位文件数)。"""
    items: list[dict] = []
    stubs = 0
    for name in sorted(os.listdir(exercise_dir)):
        if not name.endswith(".json"):
            continue
        path = os.path.join(exercise_dir, name)
        try:
            with open(path, encoding="utf-8") as fp:
                data = json.load(fp)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            print(f"  ! 跳过无法解析的文件 {name}: {exc}", file=sys.stderr)
            continue
        if not isinstance(data, dict) or len(data) < STUB_KEYS:
            stubs += 1
            continue
        if not data.get("id"):
            data["id"] = os.path.splitext(name)[0]
        items.append(data)
    return items, stubs


def matches(item: dict, equipment: str, category: str) -> bool:
    """徒手（equipment 命中）或有氧（category 命中）都算核心动作。"""
    return norm(item.get("equipment")) == equipment or norm(item.get("category")) == category


def build(catalogue: str, out_dir: str, i18n: str, equipment: str,
          category: str, keep_without_media: bool, limit: int) -> int:
    exercise_dir = os.path.join(catalogue, "exercises")
    still_dir = os.path.join(catalogue, "media", "still")
    i18n_path = os.path.join(catalogue, "i18n", f"{i18n}.json")

    if not os.path.isdir(exercise_dir):
        sys.exit(f"找不到动作目录: {exercise_dir}")

    print(f"扫描 {exercise_dir} ...")
    all_items, stubs = load_exercises(exercise_dir)
    print(f"  动作文件 {len(all_items) + stubs} 个（其中占位 {stubs} 个、有效 {len(all_items)} 个）")

    zh: dict = {}
    if os.path.exists(i18n_path):
        with open(i18n_path, encoding="utf-8") as fp:
            zh = json.load(fp)
        print(f"  中文词条 {len(zh)} 条（{os.path.relpath(i18n_path, catalogue)}）")
    else:
        print("  ! 未找到中文语言包，名称将保留英文", file=sys.stderr)

    selected = [it for it in all_items if matches(it, equipment, category)]
    print(f"  命中筛选（equipment={equipment} 或 category={category}）：{len(selected)} 个")

    # 缺图的先剔掉，保证 UI 不会出现空卡片
    with_media, missing_media = [], []
    for item in selected:
        if os.path.exists(os.path.join(still_dir, f"{item['id']}.webp")):
            with_media.append(item)
        else:
            missing_media.append(item)
    if missing_media and not keep_without_media:
        print(f"  ! {len(missing_media)} 个动作缺少 WebP，已跳过（--keep-without-media 可保留）")
    chosen = selected if keep_without_media else with_media
    if limit > 0:
        chosen = chosen[:limit]
    print(f"  最终打包：{len(chosen)} 个")

    # 图片目录叫 gifs/：上游是 WebP 序列帧/静态图，App 里统一当动作演示图用
    images_out = os.path.join(out_dir, "gifs")
    os.makedirs(images_out, exist_ok=True)

    records: list[dict] = []
    copied = 0
    for item in chosen:
        eid = item["id"]
        zh_item = zh.get(eid) or {}
        zh_name = (zh_item.get("name") or "").strip()
        zh_steps = [s for s in (zh_item.get("instructions") or []) if isinstance(s, str)]
        en_steps = [s for s in (item.get("instructions") or []) if isinstance(s, str)]

        src_img = os.path.join(still_dir, f"{eid}.webp")
        image_rel = None
        if os.path.exists(src_img):
            dst_img = os.path.join(images_out, f"{eid}.webp")
            if not (os.path.exists(dst_img) and os.path.getsize(dst_img) == os.path.getsize(src_img)):
                shutil.copy2(src_img, dst_img)
            copied += 1
            image_rel = f"gifs/{eid}.webp"

        name_en = (item.get("name") or "").strip()
        body_part = norm(item.get("bodyPart"))
        cat = norm(item.get("category"))
        target = norm(item.get("target"))

        records.append(OrderedDict(
            id=eid,
            name=zh_name or name_en,          # 中文优先，缺失回落英文
            nameEn=name_en,
            hasNameZh=bool(zh_name),
            bodyPart=body_part,
            bodyPartCn=BODY_PART_CN.get(body_part, body_part),
            category=cat,
            categoryCn=CATEGORY_CN.get(cat, cat),
            equipment=norm(item.get("equipment")),
            equipmentCn=EQUIPMENT_CN.get(norm(item.get("equipment")), norm(item.get("equipment"))),
            target=target,
            targetCn=TARGET_CN.get(target, target),
            secondaryMuscles=[MUSCLE_CN.get(norm(m), m) for m in (item.get("secondaryMuscles") or [])],
            description=(item.get("description") or "").strip(),
            steps=zh_steps or en_steps,        # 中文步骤缺失时回落英文
            stepsCn=bool(zh_steps),
            image=image_rel,
            femaleVariant=item.get("femaleVariant"),
        ))

    # 排序：有氧优先？不，按 部位 → 名称 排，列表更稳；App 端另有筛选。
    records.sort(key=lambda r: (r["bodyPart"], r["name"]))

    payload = OrderedDict(
        version=1,
        source="openGym catalogue (github.com/DuarteSantos8/openGym)",
        generatedAt=_dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        filter=OrderedDict(equipment=equipment, category=category),
        count=len(records),
        items=records,
    )

    os.makedirs(out_dir, exist_ok=True)
    out_json = os.path.join(out_dir, "exercises.json")
    with open(out_json, "w", encoding="utf-8") as fp:
        json.dump(payload, fp, ensure_ascii=False, separators=(",", ":"))
    print(f"写出 {out_json}（{os.path.getsize(out_json) / 1024:.0f} KB）")

    # 筛选栏需要的枚举与计数
    taxonomy = OrderedDict()
    for key, field, label_field in (("bodyParts", "bodyPart", "bodyPartCn"),
                                    ("categories", "category", "categoryCn")):
        counter = Counter(r[field] for r in records)
        labels = {}
        for rec in records:
            labels.setdefault(rec[field], rec[label_field])
        taxonomy[key] = [
            OrderedDict(value=v, label=labels.get(v, v), count=c)
            for v, c in sorted(counter.items(), key=lambda kv: -kv[1])
        ]
    taxonomy["total"] = len(records)
    taxonomy["withChineseSteps"] = sum(1 for r in records if r["stepsCn"])
    taxonomy["withChineseName"] = sum(1 for r in records if r["hasNameZh"])
    out_taxonomy = os.path.join(out_dir, "taxonomy.json")
    with open(out_taxonomy, "w", encoding="utf-8") as fp:
        json.dump(taxonomy, fp, ensure_ascii=False, indent=2)

    total_img = sum(os.path.getsize(os.path.join(images_out, f))
                    for f in os.listdir(images_out))
    print(f"图片 {copied} 张，共 {total_img / 1024 / 1024:.1f} MB")
    print(f"中文名覆盖率 {taxonomy['withChineseName']}/{len(records)}，"
          f"中文步骤覆盖率 {taxonomy['withChineseSteps']}/{len(records)}")
    print("部位分布：" + "，".join(
        f"{e['label']} {e['count']}" for e in taxonomy["bodyParts"]))
    print("分类分布：" + "，".join(
        f"{e['label']} {e['count']}" for e in taxonomy["categories"]))
    if missing_media:
        print(f"（另 {len(missing_media)} 个动作在 catalogue 中无图："
              f"{', '.join(m['id'] for m in missing_media[:8])} ...）")
    return 0


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser(description="从 openGym catalogue 生成 App 内置动作包")
    parser.add_argument("--catalogue", default=os.environ.get(
        "OPENGYM_CATALOGUE", r"D:\work\AndroidDev\tools\ref\openGym\catalogue"),
        help="openGym 仓库的 catalogue 目录")
    parser.add_argument("--out", default=os.path.join(
        root, "app", "src", "main", "assets"), help="assets 输出目录")
    parser.add_argument("--i18n", default="zh", help="使用哪个语言包（zh / zh-TW ...）")
    parser.add_argument("--equipment", default="body weight", help="徒手器械枚举值")
    parser.add_argument("--category", default="cardio", help="有氧分类枚举值")
    parser.add_argument("--keep-without-media", action="store_true", help="缺图动作也保留")
    parser.add_argument("--limit", type=int, default=0, help="只取前 N 个（调试用）")
    args = parser.parse_args()
    return build(
        catalogue=os.path.abspath(args.catalogue),
        out_dir=os.path.abspath(args.out),
        i18n=args.i18n,
        equipment=norm(args.equipment),
        category=norm(args.category),
        keep_without_media=args.keep_without_media,
        limit=args.limit,
    )


if __name__ == "__main__":
    raise SystemExit(main())
