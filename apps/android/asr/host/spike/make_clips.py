#!/usr/bin/env python3
"""Build the provisional G1 clip set under ~/.cache/budgetwise-eval/clips (stdlib + ffmpeg).

30 FLEURS my_mm test utterances (CC-BY-4.0, evenly spaced among those <= 15 s) plus the
3 generated 5 s silence/noise clips. Budget phrases are PENDING (user Decision 6).
Audio stays outside git.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile

BASE = os.path.expanduser("~/.cache/budgetwise-eval")
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
PUNCT = re.compile(r"[။၊,.?!()\"'“”‘’:;]")


def main():
    fl = os.path.join(BASE, "fleurs")
    clips_dir = os.path.join(BASE, "clips")
    os.makedirs(os.path.join(clips_dir, "wav"), exist_ok=True)
    rows = []
    for line in open(os.path.join(fl, "test.tsv"), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 6 and int(p[5]) <= 15 * 16000 and int(p[5]) >= 3 * 16000:
            rows.append((p[1], PUNCT.sub("", p[2]).strip()))
    step = len(rows) // 30
    picked = rows[::step][:30]
    want = {name for name, _ in picked}
    raw = os.path.join(fl, "raw")
    os.makedirs(raw, exist_ok=True)
    with tarfile.open(os.path.join(fl, "test.tar.gz")) as tf:
        for m in tf:
            if os.path.basename(m.name) in want:
                m.name = os.path.basename(m.name)
                tf.extract(m, raw)
    out = []
    for i, (name, ref) in enumerate(picked):
        wav = os.path.join(clips_dir, "wav", f"fleurs_{i:02d}.wav")
        subprocess.run([FFMPEG, "-loglevel", "error", "-y", "-i", os.path.join(raw, name),
                        "-ac", "1", "-ar", "16000", "-sample_fmt", "s16", wav], check=True)
        out.append({"id": f"fleurs_{i:02d}", "wav": wav, "ref": ref, "kind": "fleurs"})
    # 5 s digital silence, low pink noise, louder brown-noise hum (generated, not recorded)
    gens = {"silence_digital": "anullsrc=r=16000:cl=mono",
            "silence_room_low": "anoisesrc=color=pink:amplitude=0.01:r=16000:d=5:seed=1",
            "silence_room_hum": "anoisesrc=color=brown:amplitude=0.05:r=16000:d=5:seed=2"}
    for n, src in gens.items():
        subprocess.run([FFMPEG, "-loglevel", "error", "-y", "-f", "lavfi", "-i", src, "-t", "5",
                        "-ac", "1", "-ar", "16000", "-sample_fmt", "s16",
                        os.path.join(clips_dir, "wav", n + ".wav")], check=True)
        out.append({"id": n, "wav": os.path.join(clips_dir, "wav", n + ".wav"), "ref": "", "kind": "silence"})
    with open(os.path.join(clips_dir, "clips.jsonl"), "w", encoding="utf-8") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(len(out), "clips")


if __name__ == "__main__":
    sys.exit(main())
