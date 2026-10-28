#!/usr/bin/env python3
"""Number-spelling probe: extra FLEURS my_mm test clips whose refs contain digits or number words.

Not part of the G1 clip set. Needs a running llama-server on :8089 and fleurs/test.tsv + test.tar.gz in
~/.cache/budgetwise-eval/fleurs. Writes golden/numprobe-<label>-auto.jsonl (text only: id, ref, hyp).
Usage: probe_numbers.py <label>
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_spike as rs  # noqa: E402

BASE = os.path.expanduser("~/.cache/budgetwise-eval")
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
HERE = os.path.dirname(os.path.abspath(__file__))


def main(label):
    fl = os.path.join(BASE, "fleurs")
    num = re.compile(r"[၀-၉0-9]|ထောင်|သောင်း|သိန်း|ရာ")
    rows = []
    for line in open(os.path.join(fl, "test.tsv"), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if 3 * 16000 <= int(p[5]) <= 12 * 16000 and num.search(p[2]):
            rows.append((p[1], rs.strip_punct(p[2]).strip()))
    rows = rows[:14]
    want = {n for n, _ in rows}
    raw = os.path.join(fl, "numraw")
    os.makedirs(raw, exist_ok=True)
    with tarfile.open(os.path.join(fl, "test.tar.gz")) as tf:
        for m in tf:
            if os.path.basename(m.name) in want:
                m.name = os.path.basename(m.name)
                tf.extract(m, raw)
    os.makedirs(os.path.join(BASE, "clips", "wav"), exist_ok=True)
    out = os.path.join(HERE, "golden", f"numprobe-{label}-auto.jsonl")
    with open(out, "w", encoding="utf-8") as f:
        for i, (n, ref) in enumerate(rows):
            wav = os.path.join(BASE, "clips", "wav", f"num_{i:02d}.wav")
            subprocess.run([FFMPEG, "-loglevel", "error", "-y", "-i", os.path.join(raw, n), "-ac", "1",
                            "-ar", "16000", "-sample_fmt", "s16", wav], check=True)
            r = rs.run_clip("http://127.0.0.1:8089", {"id": f"num_{i:02d}", "wav": wav, "kind": "numprobe"}, None)
            f.write(json.dumps({"id": r["id"], "ref": ref, "raw": r["raw"], "text": r["text"]},
                               ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main(sys.argv[1])
