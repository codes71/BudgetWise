"""Mac reference for the phone's ONNX speech engines (OnnxFreococoEngine, OnnxOmnilingualEngine).

Runs freococo.onnx and Omnilingual model.int8.onnx with Python onnxruntime, with the same session options and
the same pre- and post-processing as the Kotlin code, so the phone can be checked against these transcripts.
The onnxruntime version must equal the `onnxruntime` entry in apps/android/gradle/libs.versions.toml.

usage:
  ort_reference.py transcribe --out goldens.json [--opt disable|basic|extended|all] [--threads N] [wav ...]
  ort_reference.py fixtures --out decode-fixtures.json [wav ...]
  ort_reference.py memory --model freococo|omni [--opt ...] [--threads N] <wav>

transcribe writes {"onnxruntime", "options", "clips": {id: {"freococo", "omni"}}}; the device test reads it.
fixtures records model outputs (freococo ids, Omnilingual top-2 logits per frame) plus the Python decode, for the
JVM unit tests that check the Kotlin decoders without the models. memory prints peak RSS for one model.
Default model and clip locations are the eval cache in ~/.cache/budgetwise-eval.
"""
import argparse
import json
import resource
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf

CACHE = Path.home() / ".cache/budgetwise-eval"
FREOCOCO = CACHE / "models/freococo-onnx/freococo.onnx"
VOCAB = CACHE / "models/freococo-myanmar-asr/vocab.json"
OMNI = CACHE / "models/omni1b-int8/model.int8.onnx"
TOKENS = CACHE / "models/omni1b-int8/tokens.txt"
WAVS = sorted((CACHE / "my-voice/audio/wav").glob("r*.wav"))

OPT = {
    "disable": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
    "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
    "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
}
# Same as the Kotlin engines: FREOCOCO_MIN_SAMPLES and OMNI_MIN_SAMPLES.
FREOCOCO_MIN_SAMPLES = 8000
OMNI_MIN_SAMPLES = 400


def session(path, opt, threads, kleidiai=False, arena=False, mem_pattern=False, prepack=False):
    """Defaults are OnnxSessions in OnnxEngines.kt; the flags exist to measure the alternatives."""
    so = ort.SessionOptions()
    if not prepack:
        so.add_session_config_entry("session.disable_prepacking", "1")
    if not kleidiai:
        so.add_session_config_entry("mlas.disable_kleidiai", "1")
    so.enable_cpu_mem_arena = arena
    so.enable_mem_pattern = mem_pattern
    so.graph_optimization_level = OPT[opt]
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def read_wav(path):
    audio, sr = sf.read(str(path), dtype="float32")
    assert sr == 16000 and audio.ndim == 1, f"{path}: need 16 kHz mono"
    return audio


def load_vocab(path=VOCAB):
    return {i: t for t, i in json.loads(Path(path).read_text(encoding="utf-8")).items()}


def load_tokens(path=TOKENS):
    out = {}
    for line in Path(path).read_text(encoding="utf-8").split("\n"):
        if not line:
            continue
        tok, _, i = line.rpartition(" ")
        out[int(i)] = tok
    return out


def collapse(ids):
    out, prev = [], -1
    for i in ids:
        if i != prev and i != 0:
            out.append(int(i))
        prev = i
    return out


def freococo_text(ids, vocab):
    return " ".join(vocab[i] for i in collapse(ids) if vocab.get(i))


def omni_normalize(audio):
    x = audio.astype(np.float64)
    mean = x.mean()
    var = ((x - mean) ** 2).mean()
    return ((x - mean) / np.sqrt(var + 1e-5)).astype(np.float32)


def omni_text(frame_ids, tokens):
    return "".join(tokens[i] for i in collapse(frame_ids)).replace("▁", " ").strip()


def run_freococo(sess, audio):
    if len(audio) < FREOCOCO_MIN_SAMPLES:
        return None
    return sess.run(["ids"], {"audio": audio[None, :]})[0][0]


def run_omni(sess, audio):
    if len(audio) < OMNI_MIN_SAMPLES:
        return None
    return sess.run(["logits"], {"x": omni_normalize(audio)[None, :]})[0][0]


def options(a):
    return {
        "graph_optimization_level": a.opt,
        "intra_op_threads": a.threads,
        "inter_op_threads": 1,
        "kleidiai": a.kleidiai,
        "cpu_mem_arena": a.arena,
        "mem_pattern": a.mem_pattern,
        "prepacking": a.prepack,
    }


def cmd_transcribe(a):
    vocab, tokens = load_vocab(), load_tokens()
    fs = session(FREOCOCO, a.opt, a.threads, a.kleidiai, a.arena, a.mem_pattern, a.prepack)
    os_ = session(OMNI, a.opt, a.threads, a.kleidiai, a.arena, a.mem_pattern, a.prepack)
    clips, secs = {}, {"freococo": 0.0, "omni": 0.0}
    for w in a.wavs:
        audio = read_wav(w)
        t = time.time()
        ids = run_freococo(fs, audio)
        secs["freococo"] += time.time() - t
        t = time.time()
        logits = run_omni(os_, audio)
        secs["omni"] += time.time() - t
        clips[Path(w).stem] = {
            "freococo": "" if ids is None else freococo_text(ids.tolist(), vocab),
            "omni": "" if logits is None else omni_text(logits.argmax(-1).tolist(), tokens),
        }
    out = {
        "onnxruntime": ort.__version__,
        "options": options(a),
        "clips": clips,
    }
    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{a.out}: {len(clips)} clips, freococo {secs['freococo']:.1f}s, omni {secs['omni']:.1f}s", file=sys.stderr)


def cmd_fixtures(a):
    vocab, tokens = load_vocab(), load_tokens()
    fs = session(FREOCOCO, a.opt, a.threads, a.kleidiai, a.arena, a.mem_pattern, a.prepack)
    os_ = session(OMNI, a.opt, a.threads, a.kleidiai, a.arena, a.mem_pattern, a.prepack)
    clips = {}
    for w in a.wavs:
        audio = read_wav(w)
        ids = run_freococo(fs, audio)
        logits = run_omni(os_, audio)
        top2 = np.argsort(-logits, axis=-1, kind="stable")[:, :2]
        clips[Path(w).stem] = {
            "freococo_ids": ids.tolist(),
            "freococo_text": freococo_text(ids.tolist(), vocab),
            "omni_frames": int(logits.shape[0]),
            "omni_top2_ids": top2.tolist(),
            "omni_top2_logits": np.take_along_axis(logits, top2, axis=-1).tolist(),
            # Every logit outside the top 2 is at least this low, so filling the rest with it keeps the argmax.
            "omni_floor": float(logits.min()),
            "omni_text": omni_text(logits.argmax(-1).tolist(), tokens),
        }
    # A slice of real (quiet) audio and its normalization, for the Kotlin normalization test.
    pcm = (read_wav(a.wavs[-1])[16000:16400] * 32768).round().astype(np.int16)
    norm = omni_normalize(pcm.astype(np.float32) / 32768)
    out = {
        "onnxruntime": ort.__version__,
        "options": options(a),
        "vocab_size": len(tokens),
        "clips": clips,
        "normalize": {"pcm16": pcm.tolist(), "expected": norm.tolist()},
    }
    Path(a.out).write_text(json.dumps(out, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{a.out}: {len(clips)} clips", file=sys.stderr)


def cmd_memory(a):
    path = {"freococo": FREOCOCO, "omni": OMNI}[a.model]
    run = {"freococo": run_freococo, "omni": run_omni}[a.model]
    sess = session(path, a.opt, a.threads, a.kleidiai, a.arena, a.mem_pattern, a.prepack)
    after_load = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    audio = read_wav(a.wavs[0])
    if a.seconds:
        audio = np.resize(audio, a.seconds * 16000)
    run(sess, audio)
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    # macOS reports ru_maxrss in bytes.
    print(f"{a.model} opt={a.opt} threads={a.threads} arena={a.arena} mem_pattern={a.mem_pattern} prepack={a.prepack} "
          f"{len(audio) / 16000:.1f}s: peak after load {after_load / 1e6:.0f} MB, after run {peak / 1e6:.0f} MB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["transcribe", "fixtures", "memory"])
    ap.add_argument("wavs", nargs="*", type=Path)
    ap.add_argument("--out")
    ap.add_argument("--opt", choices=list(OPT), default="disable")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--model", choices=["freococo", "omni"])
    ap.add_argument("--kleidiai", action="store_true")
    ap.add_argument("--arena", action="store_true")
    ap.add_argument("--mem-pattern", action="store_true")
    ap.add_argument("--prepack", action="store_true")
    ap.add_argument("--seconds", type=int, default=0)
    a = ap.parse_args()
    a.wavs = a.wavs or WAVS
    {"transcribe": cmd_transcribe, "fixtures": cmd_fixtures, "memory": cmd_memory}[a.cmd](a)


if __name__ == "__main__":
    main()
