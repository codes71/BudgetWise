"""raj-host (our llama.cpp + libmtmd engine, CPU only) against llama-server at the same pin.

golden_cpu_server_auto.jsonl is a CPU-only llama-server run of PARITY_IDS, made once with the
Task 1 harness (C=~/.cache/budgetwise-eval, clips file = the PARITY_IDS rows of clips.jsonl):

  $C/llama.cpp/build/bin/llama-server -m $C/raj-0.6b/gguf/Raj-0.6B-Q8_0.gguf \
    --mmproj $C/raj-0.6b/gguf/mmproj-Raj-0.6B-Q8_0.gguf -c 4096 --temp 0 --port 8089 \
    -ngl 0 --no-mmproj-offload -t 4
  python3 -I android/asr/host/spike/run_spike.py --server http://127.0.0.1:8089 \
    --clips parity_clips.jsonl --mode auto --label raj-0.6b-q8_0-cpu \
    --out android/asr/host/tests/golden_cpu_server_auto.jsonl

Build raj-host first (see android/asr/host/README.md). Skips when models or clips are missing.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

HOST_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HOST_DIR / "spike"))
import run_spike  # noqa: E402

CACHE = Path(os.environ.get("BUDGETWISE_EVAL_CACHE", Path.home() / ".cache" / "budgetwise-eval"))
MODEL = CACHE / "raj-0.6b" / "gguf" / "Raj-0.6B-Q8_0.gguf"
MMPROJ = CACHE / "raj-0.6b" / "gguf" / "mmproj-Raj-0.6B-Q8_0.gguf"
CLIPS = CACHE / "clips" / "clips.jsonl"
RAJ_HOST = Path(os.environ.get("RAJ_HOST", HOST_DIR / "build" / "raj-host"))
CPU_GOLDEN = HOST_DIR / "tests" / "golden_cpu_server_auto.jsonl"
METAL_GOLDEN = HOST_DIR / "spike" / "golden" / "raj-0.6b-q8_0-auto.jsonl"

PARITY_IDS = ["fleurs_02", "fleurs_10", "fleurs_23", "fleurs_25", "silence_room_hum"]
SILENCE_ID = "silence_room_hum"
MAX_SER_VS_METAL = 0.02


def read_rows(path):
    rows = {}
    for line in open(path, encoding="utf-8"):
        if line.strip():
            r = json.loads(line)
            if "id" in r:
                rows[r["id"]] = r
    return rows


def test_host_matches_server_golden(tmp_path):
    for p in (MODEL, MMPROJ, CLIPS):
        if not p.exists():
            pytest.skip(f"missing {p}")
    clips = read_rows(CLIPS)
    missing = [i for i in PARITY_IDS if i not in clips or not Path(clips[i]["wav"]).exists()]
    if missing:
        pytest.skip(f"missing clips {missing}")
    assert RAJ_HOST.exists(), f"raj-host missing: {RAJ_HOST}"

    subset = tmp_path / "clips.jsonl"
    subset.write_text("".join(json.dumps(clips[i], ensure_ascii=False) + "\n" for i in PARITY_IDS),
                      encoding="utf-8")
    out = tmp_path / "out.jsonl"
    p = subprocess.run([str(RAJ_HOST), "--model", str(MODEL), "--mmproj", str(MMPROJ),
                        "--clips", str(subset), "--threads", "4", "--out", str(out)],
                       capture_output=True, text=True, timeout=900)
    assert p.returncode == 0, p.stderr[-4000:]

    host = read_rows(out)
    cpu = read_rows(CPU_GOLDEN)
    metal = read_rows(METAL_GOLDEN)
    assert sorted(host) == sorted(PARITY_IDS)

    diffs = [f"{i}\n  host: {host[i]['text']}\n  cpu:  {cpu[i]['text']}"
             for i in PARITY_IDS if host[i]["text"] != cpu[i]["text"]]
    assert not diffs, "host text differs from the CPU server:\n" + "\n".join(diffs)
    raw_diffs = [f"{i}\n  host: {host[i]['raw']}\n  cpu:  {cpu[i]['raw']}"
                 for i in PARITY_IDS if host[i]["raw"] != cpu[i]["raw"]]
    assert not raw_diffs, "host raw differs from the CPU server:\n" + "\n".join(raw_diffs)

    assert host[SILENCE_ID]["text"] == ""

    # Pooled SER over the speech clips, scored by run_spike.summarize with the Metal text as reference.
    refs = [{"id": i, "ref": metal[i]["text"]} for i in PARITY_IDS]
    ser = run_spike.summarize([host[i] for i in PARITY_IDS], refs)["ser"]
    per_clip = {i: round(run_spike.ser(metal[i]["text"], host[i]["text"]), 4) for i in PARITY_IDS}
    assert ser <= MAX_SER_VS_METAL, f"SER vs Metal golden {ser:.4f} > {MAX_SER_VS_METAL}: {per_clip}"
