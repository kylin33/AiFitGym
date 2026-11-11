#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 openGym 前端源码里提取人体肌肉热力图资源。

数据来源（都是纯前端文件，不含第三方依赖）：
  frontend/src/lib/body-paths.js   SVG 几何（MuscleMap by Melih Colpan, MIT）
  frontend/src/lib/muscles.js      18 块可着色肌肉 / 别名表 / 体部位回退权重
  frontend/src/lib/recovery.js     每块肌肉的疲劳半衰期
  frontend/src/index.css           .bm-m.l0…l4 的混色比例与恢复期红橙黄

产出（默认写到 app/src/main/assets/anatomy/）：
  body-paths.json   male/female × front/back 的 viewBox 与逐肌肉 path 列表
  muscles.json      肌肉词表、中文名、别名映射、色阶参数、疲劳参数

另外会用同一份数据渲染一份 HTML 预览，方便肉眼校验配色：
  tools/preview/anatomy-preview.html

用法：
  python tools/extract_anatomy.py --frontend <openGym 仓库>/frontend
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import re
import sys

# 18 块可着色肌肉，按「从头到脚」顺序排列（与上游 MUSCLES 一致）
MUSCLES_FALLBACK = [
    "trapezius", "deltoids", "chest", "upper-back", "serratus",
    "biceps", "triceps", "forearm",
    "abs", "obliques", "lower-back",
    "gluteal", "quadriceps", "hamstring", "adductors", "hip-flexors",
    "calves", "tibialis",
]

MUSCLE_CN = {
    "trapezius": "斜方肌",
    "deltoids": "三角肌",
    "chest": "胸大肌",
    "upper-back": "背阔肌 / 上背",
    "serratus": "前锯肌",
    "biceps": "肱二头肌",
    "triceps": "肱三头肌",
    "forearm": "前臂",
    "abs": "腹直肌",
    "obliques": "腹斜肌",
    "lower-back": "竖脊肌 / 下背",
    "gluteal": "臀肌",
    "quadriceps": "股四头肌",
    "hamstring": "腘绳肌",
    "adductors": "内收肌",
    "hip-flexors": "髂腰肌",
    "calves": "小腿三头肌",
    "tibialis": "胫骨前肌",
}

# 动作库里的中文肌群名 → 人体图 slug（None = 图里画不出来，跳过）
#
# exercises.json 的 targetCn / secondaryMuscles 是中文，人体图用英文 slug，
# 这张表就是两者的桥。覆盖了 2303 个动作里出现过的全部 39 种副肌群写法，
# 加上 22 种 targetCn 和 18 块肌肉的正式中文名（如「腹直肌」「髂腰肌」）。
MUSCLE_CN_ALIAS = {
    # 腹 / 核心
    "腹肌": "abs", "腹部": "abs", "下腹": "abs", "核心": "abs", "腹直肌": "abs",
    # 臀
    "臀肌": "gluteal", "臀部": "gluteal", "外展肌": "gluteal",
    # 腿前 / 腿后
    "股四头肌": "quadriceps",
    "腘绳肌": "hamstring",
    # 胸
    "胸肌": "chest", "上胸": "chest", "胸大肌": "chest",
    # 肩
    "三角肌": "deltoids", "肩部": "deltoids", "三角肌后束": "deltoids", "肩袖": "deltoids",
    # 腰腹侧
    "腹斜肌": "obliques",
    # 下背
    "竖脊肌": "lower-back", "下背": "lower-back", "竖脊肌 / 下背": "lower-back",
    # 小腿
    "小腿三头肌": "calves", "小腿": "calves", "比目鱼肌": "calves",
    # 背
    "上背": "upper-back", "背阔肌": "upper-back", "背部": "upper-back",
    "菱形肌": "upper-back", "背阔肌 / 上背": "upper-back",
    # 手臂
    "肱三头肌": "triceps",
    "肱二头肌": "biceps", "肱肌": "biceps",
    "前臂": "forearm", "腕屈肌": "forearm", "腕伸肌": "forearm",
    "握力肌群": "forearm", "手腕": "forearm",
    # 内收 / 髋
    "内收肌": "adductors", "大腿内侧": "adductors", "腹股沟": "adductors",
    "髋屈肌": "hip-flexors", "髂腰肌": "hip-flexors",
    # 斜方 / 胫骨 / 前锯
    "斜方肌": "trapezius", "肩胛提肌": "trapezius",
    "胫骨前肌": "tibialis", "胫骨": "tibialis",
    "前锯肌": "serratus",
    # 画不出来的（关节、末端、循环系统）
    "心肺系统": None,
    "踝关节": None, "踝稳定肌": None, "足部": None, "手部": None, "胸锁乳突肌": None,
}

# 上游 index.css 里的系统色（iOS 调色板）
PALETTE = {
    "red": "#ff453a",
    "orange": "#ff9f0a",
    "yellow": "#ffd60a",
    "green": "#30d158",
    "surface_dark": "#1c1c1e",
    "surface_light": "#ffffff",
    "label_dark": "#ffffff",
    "label_light": "#000000",
}


# ------------------------------------------------------------------ 解析 JS
def read(path: str) -> str:
    with open(path, encoding="utf-8") as fp:
        return fp.read()


def load_body_paths(frontend: str) -> dict:
    src = read(os.path.join(frontend, "src", "lib", "body-paths.js"))
    marker = "export default "
    at = src.index(marker) + len(marker)
    payload = src[at:].strip().rstrip(";")
    data = json.loads(payload)          # 上游直接把 JSON 字面量 export 出来
    if not data:
        sys.exit("body-paths.js 解析结果为空")
    return data


def js_object_to_dict(src: str, name: str) -> dict:
    """把 `const NAME = { 'a-b': 'c', d: null }` 这种简单字面量转成 dict。"""
    match = re.search(rf"const\s+{name}\s*=\s*\{{", src)
    if not match:
        return {}
    start = match.end() - 1
    depth, end = 0, None
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end is None:
        return {}
    body = src[start:end]
    result = {}
    for m in re.finditer(r"(?:'([^']+)'|([A-Za-z_$][\w$]*))\s*:\s*(null|'([^']*)')", body):
        k = m.group(1) or m.group(2)
        result[k] = None if m.group(3) == "null" else m.group(4)
    return result


def js_string_array(src: str, name: str) -> list[str]:
    match = re.search(rf"(?:const|let|var)\s+{name}\s*=\s*\[(.*?)\]", src, re.S)
    if not match:
        return []
    return re.findall(r"'([^']*)'", match.group(1))


def load_muscles(frontend: str) -> dict:
    src = read(os.path.join(frontend, "src", "lib", "muscles.js"))
    return {
        "muscles": js_string_array(src, "MUSCLES") or MUSCLES_FALLBACK,
        "inert": js_string_array(src, "INERT"),
        "alias": js_object_to_dict(src, "ALIAS"),
        "byBodyPart": parse_by_bodypart(src),
        "secondary": float(re.search(r"const\s+SECONDARY\s*=\s*([\d.]+)", src).group(1))
        if re.search(r"const\s+SECONDARY\s*=\s*([\d.]+)", src) else 0.4,
    }


def parse_by_bodypart(src: str) -> dict:
    """BY_BODYPART 的值是嵌套对象，单独解析一层。"""
    match = re.search(r"const\s+BY_BODYPART\s*=\s*\{", src)
    if not match:
        return {}
    start = match.end() - 1
    depth = 0
    for i in range(start, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                block = src[start:i + 1]
                break
    else:
        return {}
    result = {}
    for m in re.finditer(r"'?([\w -]+)'?\s*:\s*\{([^}]*)\}", block):
        weights = {}
        for w in re.finditer(r"'?([\w -]+)'?\s*:\s*([\d.]+)", m.group(2)):
            weights[w.group(1).strip()] = float(w.group(2))
        result[m.group(1).strip()] = weights
    return result


def load_half_lives(frontend: str) -> dict:
    src = read(os.path.join(frontend, "src", "lib", "recovery.js"))
    hours = {
        "24": 24.0,
        "30": 30.0,
        "48": 48.0,
    }
    result = {}
    for slug, const in re.findall(r"'?([\w-]+)'?\s*:\s*FATIGUE_HL_(\d+)H", src):
        result[slug] = hours.get(const, 30.0)
    return result


# ------------------------------------------------------------------ 产出
def normalize_views(raw: dict) -> dict:
    views = {}
    for sex, group in raw.items():
        views[sex] = {}
        for side, view in group.items():
            paths = {slug: ds for slug, ds in view["p"].items() if ds}
            views[sex][side] = {
                "viewBox": view["vb"],
                "paths": paths,
            }
    return views


def build(frontend: str, out_dir: str, preview_path: str) -> int:
    raw = load_body_paths(frontend)
    muscle_meta = load_muscles(frontend)
    half_lives = load_half_lives(frontend)

    muscles = muscle_meta["muscles"]
    inert = muscle_meta["inert"] or ["head", "hair", "neck", "hands", "feet", "knees", "ankles"]
    views = normalize_views(raw)

    # 校验：每块肌肉至少在一个视图里有几何
    drawn = {slug for sex in views.values() for side in sex.values() for slug in side["paths"]}
    missing = [m for m in muscles if m not in drawn]
    if missing:
        print(f"  ! 这些肌肉没有几何数据：{missing}", file=sys.stderr)

    total_paths = sum(
        len(ds) for sex in views.values() for v in sex.values() for ds in v["paths"].values()
    )

    # 中文名 / 中文字段的补全
    names = {}
    src_muscles = read(os.path.join(frontend, "src", "lib", "muscles.js"))
    name_block = re.search(r"export const MUSCLE_NAME = \{(.*?)\n\}", src_muscles, re.S)
    if name_block:
        for m in re.finditer(r"'?([\w-]+)'?\s*:\s*'([^']*)'", name_block.group(1)):
            names[m.group(1)] = m.group(2)

    body = {
        "version": 1,
        "source": "openGym frontend/src/lib/body-paths.js",
        "credit": "Geometry converted from MuscleMap by Melih Colpan (MIT)",
        "generatedAt": _dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "muscles": muscles,
        "inert": inert,
        "views": views,
    }

    assets = {
        "version": 1,
        "muscles": [
            {
                "slug": slug,
                "nameEn": names.get(slug, slug),
                "nameCn": MUSCLE_CN.get(slug, slug),
                "halfLifeHours": half_lives.get(slug, 30.0),
                "views": [f"{sex}/{side}" for sex in views for side in views[sex]
                          if slug in views[sex][side]["paths"]],
            }
            for slug in muscles
        ],
        "inert": inert,
        "alias": muscle_meta["alias"],
        "aliasCn": MUSCLE_CN_ALIAS,
        "bodyPartFallback": muscle_meta["byBodyPart"],
        "secondaryWeight": muscle_meta["secondary"],
        "ramp": {
            "note": "openGym .bm-m.l1…l4：在 base 上叠加 accent，比例 0.32/0.56/0.78/1.0",
            "baseMix": 0.11,
            "silhouetteMix": 0.18,
            "levels": [0.0, 0.32, 0.56, 0.78, 1.0],
        },
        "fatigue": {
            "note": "恢复期视图：疲劳度按 0.25 / 0.5 切成 三档，配色改为 黄→橙→红",
            "levels": [0.0, 0.32, 1.0, 1.0, 1.0],
            "colors": {"1": "yellow", "2": "yellow", "3": "orange", "4": "red"},
            "bands": {"readyBelow": 0.25, "recoveringBelow": 0.5},
            "saturation": "1 - exp(-accumulated)",
        },
        "palette": PALETTE,
    }

    os.makedirs(out_dir, exist_ok=True)
    body_path = os.path.join(out_dir, "body-paths.json")
    meta_path = os.path.join(out_dir, "muscles.json")
    with open(body_path, "w", encoding="utf-8") as fp:
        json.dump(body, fp, ensure_ascii=False, separators=(",", ":"))
    with open(meta_path, "w", encoding="utf-8") as fp:
        json.dump(assets, fp, ensure_ascii=False, indent=2)

    print(f"视图：{', '.join(f'{s}/{v}' for s in views for v in views[s])}")
    print(f"肌肉 {len(muscles)} 块，轮廓件 {len(inert)} 个，path 合计 {total_paths} 条")
    print(f"写出 {body_path}（{os.path.getsize(body_path) / 1024:.0f} KB）")
    print(f"写出 {meta_path}（{os.path.getsize(meta_path) / 1024:.0f} KB）")
    print(f"别名 {len(assets['alias'])} 条，其中不可绘制 "
          f"{sum(1 for v in assets['alias'].values() if v is None)} 条")
    print(f"半衰期：" + "，".join(
        f"{m['nameCn']} {m['halfLifeHours']:.0f}h" for m in assets["muscles"] if m["halfLifeHours"] != 30))

    write_preview(views, muscles, assets, preview_path)
    return 0


# ------------------------------------------------------------------ HTML 预览
def hex_to_rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def rgb_to_hex(rgb: tuple[float, float, float]) -> str:
    return "#%02x%02x%02x" % tuple(max(0, min(255, round(c))) for c in rgb)


def mix(bottom: str, top: str, ratio: float) -> str:
    """等价于 CSS 的 color-mix(bottom (1-ratio), top ratio)。"""
    br, bg, bb = hex_to_rgb(bottom)
    tr, tg, tb = hex_to_rgb(top)
    return rgb_to_hex((
        br + (tr - br) * ratio,
        bg + (tg - bg) * ratio,
        bb + (tb - bb) * ratio,
    ))


def write_preview(views: dict, muscles: list[str], assets: dict, preview_path: str) -> None:
    palette = assets["palette"]
    accent = palette["green"]
    surface, label = palette["surface_light"], palette["label_light"]
    base = mix(surface, label, assets["ramp"]["baseMix"])
    silhouette = mix(surface, label, assets["ramp"]["silhouetteMix"])
    ratios = assets["ramp"]["levels"]

    def level_color(level: int) -> str:
        return mix(base, accent, ratios[max(0, min(4, level))])

    fatigue_colors = {
        1: mix(base, palette["yellow"], 0.32),
        2: palette["yellow"],
        3: palette["orange"],
        4: palette["red"],
    }

    demo = {
        "chest": 4, "deltoids": 3, "triceps": 2, "biceps": 1,
        "abs": 3, "obliques": 2, "quadriceps": 4, "gluteal": 3,
        "hamstring": 2, "calves": 1, "upper-back": 3,
    }
    fatigue_demo = {"quadriceps": 4, "gluteal": 3, "hamstring": 3, "calves": 2, "lower-back": 2, "chest": 1}

    def svg(sex: str, side: str, levels: dict, fatigue: bool) -> str:
        view = views[sex][side]
        vb_x, vb_y, vb_w, vb_h = (float(x) for x in view["viewBox"].split())
        parts = []
        for slug in assets["inert"]:
            for d in view["paths"].get(slug, []):
                parts.append(f'<path d="{d}" fill="{silhouette}"/>')
        for slug in muscles:
            level = levels.get(slug, 0)
            color = (fatigue_colors if fatigue else {}).get(level) or level_color(level)
            for d in view["paths"].get(slug, []):
                parts.append(f'<path d="{d}" fill="{color}"><title>{slug}</title></path>')
        return (f'<svg viewBox="{view["viewBox"]}" aria-label="{sex} {side}">'
                + "".join(parts) + "</svg>")

    def pair(title: str, levels: dict, fatigue: bool = False, sex: str = "male") -> str:
        return f"""
      <figure>
        <div class="pair">{svg(sex, "front", levels, fatigue)}{svg(sex, "back", levels, fatigue)}</div>
        <figcaption>{title}</figcaption>
      </figure>"""

    legend_cells = "".join(
        f'<span class="cell" style="background:{level_color(i)}"></span>' for i in range(5))
    fatigue_cells = "".join(
        f'<span class="cell" style="background:{(fatigue_colors.get(i) or level_color(i))}"></span>'
        for i in range(5))

    muscle_rows = "".join(
        f'<tr><td>{m["nameCn"]}</td><td class="mono">{m["slug"]}</td>'
        f'<td>{m["nameEn"]}</td><td>{m["halfLifeHours"]:.0f}h</td>'
        f'<td class="mono">{", ".join(m["views"])}</td></tr>'
        for m in assets["muscles"])

    html = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>FitGym · 人体肌肉热力图资源预览</title>
<style>
  :root {{ --surface:#ffffff; --ink:#1a1c1a; --muted:#5b615c; --line:#e6ebe4; --accent:{accent}; }}
  * {{ box-sizing:border-box }}
  body {{ margin:0; padding:32px; background:#f7f8f4; color:var(--ink);
         font:400 14px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif }}
  h1 {{ font-size:26px; margin:0 0 6px }}
  .sub {{ color:var(--muted); margin-bottom:26px }}
  .grid {{ display:flex; flex-wrap:wrap; gap:18px }}
  figure {{ margin:0; background:var(--surface); border:1px solid var(--line);
            border-radius:18px; padding:16px 18px 10px; width:250px }}
  .pair {{ display:flex; gap:6px; height:260px }}
  .pair svg {{ flex:1; min-width:0; height:100% }}
  figcaption {{ text-align:center; color:var(--muted); font-size:12.5px; margin-top:8px }}
  h2 {{ font-size:16px; margin:34px 0 12px }}
  .legend {{ display:flex; align-items:center; gap:6px; font-size:12.5px; color:var(--muted) }}
  .cell {{ width:12px; height:12px; border-radius:3px; display:inline-block }}
  table {{ border-collapse:collapse; width:100%; max-width:900px; background:#fff;
           border:1px solid var(--line); border-radius:14px; overflow:hidden; font-size:13px }}
  th,td {{ text-align:left; padding:8px 12px; border-bottom:1px solid var(--line) }}
  th {{ background:#f1f4ee; font-weight:600; font-size:12.5px; color:var(--muted) }}
  tr:last-child td {{ border-bottom:none }}
  .mono {{ font-family:ui-monospace,SFMono-Regular,Consolas,monospace; font-size:12px; color:var(--muted) }}
  .note {{ color:var(--muted); font-size:12.5px; max-width:900px }}
</style></head><body>
  <h1>人体肌肉热力图 · 资源预览</h1>
  <div class="sub">从 openGym 前端 SVG 提取 · 男性/女性 × 正面/背面 · 共 {len(muscles)} 块可着色肌肉</div>

  <div class="grid">
    {pair("男性 · 未训练（全 l0）", {}, sex="male")}
    {pair("男性 · 训练量示意（相对分级）", demo, sex="male")}
    {pair("男性 · 恢复期视图（酸痛未消）", fatigue_demo, fatigue=True, sex="male")}
    {pair("女性 · 同一组训练量", demo, sex="female")}
  </div>

  <h2>强度色阶</h2>
  <div class="legend">未训练 {legend_cells} 练透</div>
  <div class="legend" style="margin-top:6px">恢复期 {fatigue_cells} 仍酸痛</div>

  <h2>肌肉词表</h2>
  <table>
    <tr><th>中文</th><th>slug</th><th>English</th><th>疲劳半衰期</th><th>出现视图</th></tr>
    {muscle_rows}
  </table>
  <p class="note">色阶算法：l1–l4 = base 与 accent 按 32% / 56% / 78% / 100% 混合，base 为 label 11% 叠在 surface 上；
  恢复期把 accent 换成黄(32%)→黄→橙→红。上表半衰期来自 openGym recovery.js，用于疲劳度指数衰减。</p>
</body></html>"""

    os.makedirs(os.path.dirname(preview_path), exist_ok=True)
    with open(preview_path, "w", encoding="utf-8") as fp:
        fp.write(html)
    print(f"写出 {preview_path}（{os.path.getsize(preview_path) / 1024:.0f} KB）")


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser(description="提取 openGym 人体肌肉热力图 SVG 资源")
    parser.add_argument("--frontend", default=os.environ.get(
        "OPENGYM_FRONTEND", r"D:\work\AndroidDev\tools\ref\openGym\frontend"),
        help="openGym 仓库的 frontend 目录")
    parser.add_argument("--out", default=os.path.join(
        root, "app", "src", "main", "assets", "anatomy"), help="资源输出目录")
    parser.add_argument("--preview", default=os.path.join(
        root, "tools", "preview", "anatomy-preview.html"), help="HTML 预览输出路径")
    args = parser.parse_args()
    return build(os.path.abspath(args.frontend), os.path.abspath(args.out),
                 os.path.abspath(args.preview))


if __name__ == "__main__":
    raise SystemExit(main())
