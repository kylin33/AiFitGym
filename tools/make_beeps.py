#!/usr/bin/env python3
"""生成跟练提示音（tick / go / done）到 app/src/main/res/raw/。

为什么自己合成而不是找素材：
- 三种提示音都不到 0.4 秒，正弦 + 指数衰减就够用，而且不会有版权问题；
- 体积可控（合计约 50KB），改了音色重跑一次即可。

音色取向：
- tick  1046Hz / 70ms   —— 倒计时最后三秒，短、脆、不盖住口令
- go     880Hz / 130ms  —— 开始一组 / 休息结束
- done  1046→1318→1568Hz 三连上行 —— 一组完成、整场结算

用法：
    python tools/make_beeps.py
"""
from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"

ATTACK_SEC = 0.004
TAIL_SEC = 0.008
HARMONIC = 0.08  # 加一点二次谐波，比纯正弦更「叮」，不刺耳


def tone(freq: float, duration: float, peak: float) -> list[int]:
    n = int(RATE * duration)
    attack = max(1, int(ATTACK_SEC * RATE))
    tail = max(1, int(TAIL_SEC * RATE))
    samples: list[int] = []
    for i in range(n):
        t = i / RATE
        # 起音淡入，避免波形突然开始产生咔哒声
        env = min(1.0, i / attack)
        # 指数衰减：听感上像敲击，而不是一段被切断的持续音。
        # 2.2 比 3.0 收得慢一点：尾巴更有「实感」，在健身房环境里更容易被听见
        env *= math.exp(-2.2 * i / n)
        # 收尾淡出，避免截断爆音
        if i > n - tail:
            env *= (n - i) / tail
        value = math.sin(2 * math.pi * freq * t) + HARMONIC * math.sin(4 * math.pi * freq * t)
        samples.append(int(max(-1.0, min(1.0, value * env * peak)) * 32767))
    return samples


def silence(duration: float) -> list[int]:
    return [0] * int(RATE * duration)


def write(name: str, samples: list[int]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)  # 16-bit PCM
        handle.setframerate(RATE)
        handle.writeframes(b"".join(struct.pack("<h", s) for s in samples))
    print(f"{path.relative_to(OUT.parent.parent.parent.parent)}  {path.stat().st_size / 1024:.1f} KB")


def main() -> int:
    # peak 0.9 左右 = -1dB：手机小喇叭上已经接近满响度，又留了不削顶的余量。
    # 之前 0.55/0.70 是为了「不刺耳」，但在健身房/口袋场景里直接听不见，等于没有。
    write("tick.wav", tone(1046.5, 0.07, 0.90))

    write("go.wav", tone(880.0, 0.13, 0.95))

    done = (
        tone(1046.5, 0.11, 0.88)
        + silence(0.015)
        + tone(1318.5, 0.11, 0.90)
        + silence(0.015)
        + tone(1568.0, 0.16, 0.95)
    )
    write("done.wav", done)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
