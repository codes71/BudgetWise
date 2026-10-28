#!/usr/bin/env python3
"""Check llama-mtmd-cli output equals the server golden raw text for a few clips.

Usage: parity_cli.py <llama-bin-dir> <model.gguf> <mmproj.gguf> <golden auto jsonl> <id> [<id> ...]
Clip wavs are read from ~/.cache/budgetwise-eval/clips/wav/<id>.wav. Exit 1 on any difference.
"""
import json
import os
import subprocess
import sys


def main(bindir, model, mmproj, golden, ids):
    raws = {}
    for line in open(golden, encoding="utf-8"):
        r = json.loads(line)
        if "id" in r:
            raws[r["id"]] = r["raw"]
    bad = 0
    for i in ids:
        wav = os.path.expanduser(f"~/.cache/budgetwise-eval/clips/wav/{i}.wav")
        p = subprocess.run([os.path.join(bindir, "llama-mtmd-cli"), "-m", model, "--mmproj", mmproj,
                            "--audio", wav, "-p", "<__media__>", "--temp", "0", "-n", "256"],
                           capture_output=True, text=True, stdin=subprocess.DEVNULL)
        cli = p.stdout.strip()
        ok = cli == raws[i]
        bad += not ok
        print(i, "MATCH" if ok else "DIFF")
        print("  cli:", cli)
        print("  srv:", raws[i])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:]))
