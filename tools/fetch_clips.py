#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从上游 openGym 下载动作演示短片（mp4）到 app/src/main/assets/clips/。

上游 `catalogue/media/still/<id>.webp` 是**单帧静态图**，真正的动作过程在
`catalogue/media/clip/<id>.mp4`（180×180、约 9 秒、H.264、中位 23 KB）。

默认下「主打动作」——徒手体操 865 + 力量 104，加上 48 个技能树主干
（exercises.json 里带 skillLevel 的）和三套内置模板用到的动作，去重后 989 个、
约 24 MB。其余 1314 个（拉伸/柔韧/瑜伽/有氧/爆发力…）不进 APK，
App 内按需下载。全量 2303 个约 56 MB，用 --all 才会那样下。

用法（在工程根目录）：

    python tools/fetch_clips.py              # 默认集合
    python tools/fetch_clips.py --all        # 全部 2303 个（约 63 MB）
    python tools/fetch_clips.py --ids 2308,1465 --force
    python tools/fetch_clips.py --dry-run    # 只列出会下哪些

脚本幂等：已存在且非空的文件会跳过，中断后重跑即可续上。
"""

from __future__ import annotations

import argparse
import concurrent.futures as futures
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXERCISES = os.path.join(ROOT, "app", "src", "main", "assets", "exercises.json")
PRESETS = os.path.join(
    ROOT, "app", "src", "main", "java", "io", "github", "kylin33", "aifitgym",
    "data", "seeding", "RoutinePresets.kt",
)
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "clips")

CLIP_URL = (
    "https://raw.githubusercontent.com/DuarteSantos8/openGym/main"
    "/catalogue/media/clip/{}.mp4"
)
UA = "fitgym-clip-fetcher/1.0"


def load_catalog() -> list[dict]:
    with open(EXERCISES, encoding="utf-8") as f:
        return json.load(f)["items"]


# 预装进 APK 的分类。其余分类（stretching / mobility / yoga / cardio /
# plyometrics / isometric / combat / pilates / rehab）留给 App 内按需下载，
# 否则光是拉伸和瑜伽就多出 770 个短片、20 MB
BUNDLED_CATEGORIES = {"calisthenics", "strength"}


def default_ids(items: list[dict]) -> list[str]:
    """徒手体操 + 力量，再加技能树主干和内置计划模板用到的动作。"""
    ids = {e["id"] for e in items if e.get("category") in BUNDLED_CATEGORIES}
    ids |= {e["id"] for e in items if e.get("skillLevel")}
    if os.path.exists(PRESETS):
        # 模板文件里的动作 id 就是 4~5 位数字字符串，直接抠出来即可，不做 Kotlin 解析
        with open(PRESETS, encoding="utf-8") as f:
            ids |= set(re.findall(r'"(\d{4,5})"', f.read()))
    return sorted(ids)


def looks_like_mp4(path: str) -> bool:
    """ftyp 在偏移 4，是 mp4 最简单可靠的判定。"""
    try:
        with open(path, "rb") as f:
            head = f.read(12)
        return len(head) >= 12 and head[4:8] == b"ftyp"
    except OSError:
        return False


def fetch(one: tuple[str, str, bool]) -> tuple[str, str, int]:
    exercise_id, out_dir, force = one
    dest = os.path.join(out_dir, f"{exercise_id}.mp4")
    if not force and os.path.exists(dest) and os.path.getsize(dest) > 0 and looks_like_mp4(dest):
        return exercise_id, "skip", os.path.getsize(dest)

    req = urllib.request.Request(CLIP_URL.format(exercise_id), headers={"User-Agent": UA})
    last = ""
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = resp.read()
            break
        except urllib.error.HTTPError as exc:
            return exercise_id, f"HTTP {exc.code}", 0
        except Exception as exc:
            # 并发高时代理容易 502，退避重试一次比整批重跑省事
            last = f"{type(exc).__name__}: {exc}"
            time.sleep(0.6 * (attempt + 1))
    else:
        return exercise_id, last or "下载失败", 0

    if len(data) < 1024 or data[4:8] != b"ftyp":
        return exercise_id, "不是合法 mp4", 0

    # 先写临时文件再改名，避免中断留下半截文件被当成「已下载」
    tmp = dest + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dest)
    return exercise_id, "ok", len(data)


def main() -> int:
    ap = argparse.ArgumentParser(description="下载上游动作演示短片")
    ap.add_argument("--all", action="store_true", help="下载全部动作（约 56 MB）")
    ap.add_argument("--ids", help="指定 id，逗号分隔")
    ap.add_argument("--out", default=OUT_DIR, help="输出目录")
    ap.add_argument("--jobs", type=int, default=3, help="并发数（代理不稳时降到 1~2）")
    ap.add_argument("--force", action="store_true", help="已存在也重新下载")
    ap.add_argument("--dry-run", action="store_true", help="只列出会下载哪些")
    args = ap.parse_args()

    items = load_catalog()
    known = {e["id"] for e in items}

    if args.ids:
        ids = [i.strip() for i in args.ids.split(",") if i.strip()]
    elif args.all:
        ids = [e["id"] for e in items]
    else:
        ids = default_ids(items)

    unknown = [i for i in ids if i not in known]
    if unknown:
        print(f"注意：{len(unknown)} 个 id 不在动作库里，仍会尝试下载：{unknown[:5]}")

    os.makedirs(args.out, exist_ok=True)
    if args.dry_run:
        print(f"将下载 {len(ids)} 个：{', '.join(ids)}")
        return 0

    print(f"目标 {len(ids)} 个短片 → {os.path.relpath(args.out, ROOT)}")
    stats = {"ok": 0, "skip": 0, "fail": 0}
    total = 0
    failures: list[tuple[str, str]] = []

    jobs = [(i, args.out, args.force) for i in ids]
    with futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for exercise_id, status, size in pool.map(fetch, jobs):
            if status in ("ok", "skip"):
                stats[status] += 1
                total += size
            else:
                stats["fail"] += 1
                failures.append((exercise_id, status))
                print(f"  ✗ {exercise_id}: {status}")

    print(
        f"\n完成：下载 {stats['ok']}，跳过 {stats['skip']}，失败 {stats['fail']}；"
        f"合计 {total / 1048576:.2f} MB"
    )
    if failures:
        print("失败的可以重跑本脚本补齐。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
