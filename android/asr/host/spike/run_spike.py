#!/usr/bin/env python3
"""Mac spike harness for Raj Burmese ASR through llama-server (stdlib only)."""
import argparse
import base64
import collections
import json
import re
import statistics
import sys
import urllib.request
import wave

ASR_TAG = "<asr_text>"
N_PREDICT = 512

DEFAULT_MARKER = "<__media__>"


def build_prompt(forced, marker=DEFAULT_MARKER):
    """ChatML prompt with one audio marker. llama-server randomizes the marker per run
    (GET /props media_marker), so pass that in when talking to a server."""
    p = ("<|im_start|>system\n<|im_end|>\n"
         "<|im_start|>user\n" + marker + "<|im_end|>\n"
         "<|im_start|>assistant\n")
    if forced:
        p += "language " + forced + ASR_TAG
    return p


def parse_raw(raw):
    """Split `language X<asr_text>transcript` into (label, transcript)."""
    if ASR_TAG not in raw:
        return "", raw.strip()
    head, _, tail = raw.partition(ASR_TAG)
    head = head.strip()
    label = head[len("language"):].strip() if head.startswith("language") else head
    return label, tail.strip()


# Minimal sylbreak: a new syllable starts at a consonant or independent vowel that
# is not followed by asat/virama and not preceded by virama; Burmese digit runs
# are their own units. Spaces are dropped. WS5 syllables.py replaces this.
_CUT = re.compile(
    r"(?<!\u1039)[\u1000-\u102A\u103F\u104C-\u104F](?![\u103A\u1039]|\u1037\u103A)|[\u1040-\u1049]+"
)


_PUNCT = re.compile(r"[\u104a\u104b,.?!()\"'\u201c\u201d\u2018\u2019:;]")


def strip_punct(text):
    return _PUNCT.sub("", text)


def syllables(text):
    out = []
    for word in strip_punct(text).split():
        cuts = sorted({m.start() for m in _CUT.finditer(word)} | {0})
        for a, b in zip(cuts, cuts[1:] + [len(word)]):
            out.append(word[a:b])
    return out


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def ser(ref, hyp):
    r = syllables(ref)
    if not r:
        return 0.0 if not syllables(hyp) else 1.0
    return edit_distance(r, syllables(hyp)) / len(r)


def cer(ref, hyp):
    r = "".join(strip_punct(ref).split())
    h = "".join(strip_punct(hyp).split())
    if not r:
        return 0.0 if not h else 1.0
    return edit_distance(r, h) / len(r)


_DIGITS = str.maketrans("၀၁၂၃၄၅၆၇၈၉", "0123456789")
_DIGIT_RUN = re.compile(r"([၀-၉0-9][၀-၉0-9,]*)")
# Whole-syllable number words, so ordinary words (ဝယ်) never match by substring.
_DIGIT_WORDS = {"တစ်": 1, "နှစ်": 2, "သုံး": 3, "လေး": 4, "ငါး": 5, "ခြောက်": 6,
                "ရှစ်": 8, "ကိုး": 9}
_UNIT_WORDS = {"ဆယ်": 10, "ဆယ့်": 10, "ရာ": 100, "ရာ့": 100, "ထောင်": 1000, "ထောင့်": 1000,
               "သောင်း": 10_000, "သိန်း": 100_000, "သန်း": 1_000_000}
# ရာ + these is "percent" / "century", not one hundred.
_NOT_HUNDRED = {"ခိုင်", "စု"}


def _number_tokens(text):
    """Tokens: ('d', int) for digit strings, ('w', syllable) for Burmese syllables."""
    toks = []
    for i, chunk in enumerate(_DIGIT_RUN.split(strip_punct(text))):
        if i % 2:
            toks.append(("d", int(chunk.translate(_DIGITS).replace(",", "") or 0)))
        else:
            toks.extend(("w", s) for s in syllables(chunk))
    return toks


def _classify(toks, i):
    """Return ('digit'|'unit', value, width) for the token at i, or None."""
    kind, v = toks[i]
    if kind == "d":
        return "digit", v, 1
    if v == "ခု" and i + 1 < len(toks) and toks[i + 1] == ("w", "နစ်"):
        return "digit", 7, 2
    if v in _DIGIT_WORDS:
        return "digit", _DIGIT_WORDS[v], 1
    if v in _UNIT_WORDS:
        if _UNIT_WORDS[v] == 100 and i + 1 < len(toks) and toks[i + 1][1] in _NOT_HUNDRED:
            return None
        return "unit", _UNIT_WORDS[v], 1
    return None


def numbers(text):
    """Integer values of every number-bearing span: digit strings and number-word runs,
    so သုံးထောင် == ၃၀၀၀ == 3000 and သုံးသောင်း == 30000."""
    toks = _number_tokens(text)
    out, i = [], 0
    while i < len(toks):
        c = _classify(toks, i)
        if c is None:
            i += 1
            continue
        total = pending = 0
        have_pending = False
        while i < len(toks):
            c = _classify(toks, i)
            if c is None:
                break
            kind, v, width = c
            if kind == "digit":
                if have_pending:  # two digits in a row start a new number
                    break
                pending, have_pending = v, True
            else:
                total += (pending if have_pending else 1) * v
                pending, have_pending = 0, False
            i += width
        out.append(total + pending)
    return out


def amount_hit(ref, hyp):
    """True when the multiset of numbers in ref is contained in the numbers of hyp."""
    want = numbers(ref)
    if not want:
        return False
    have = collections.Counter(numbers(hyp))
    return not (collections.Counter(want) - have)


def has_loop(text, n_predicted=0):
    if n_predicted >= N_PREDICT:
        return True
    s = syllables(text)
    for n in range(1, 11):
        run = 1
        for i in range(n, len(s)):
            if s[i] == s[i - n]:
                run += 1
                if run >= 8 * n:
                    return True
            else:
                run = 1
    return False


def audio_ms(path):
    with wave.open(path, "rb") as w:
        return int(1000 * w.getnframes() / w.getframerate())


_markers = {}


def media_marker(server):
    if server not in _markers:
        with urllib.request.urlopen(server.rstrip("/") + "/props", timeout=30) as r:
            _markers[server] = json.load(r).get("media_marker", DEFAULT_MARKER)
    return _markers[server]


def transcribe(server, wav_path, forced):
    with open(wav_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode()
    body = {
        "prompt": {"prompt_string": build_prompt(forced, media_marker(server)), "multimodal_data": [b64]},
        "temperature": 0,
        "n_predict": N_PREDICT,
        "cache_prompt": False,
    }
    req = urllib.request.Request(
        server.rstrip("/") + "/completion",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def run_clip(server, clip, forced):
    resp = transcribe(server, clip["wav"], forced)
    raw = resp.get("content", "")  # stored unmodified: forced mode returns text only
    if forced:
        language, text = forced, raw.strip()
    else:
        language, text = parse_raw(raw)
    t = resp.get("timings", {})
    return {
        "id": clip["id"], "kind": clip.get("kind", ""), "raw": raw, "text": text,
        "language": language, "audio_ms": audio_ms(clip["wav"]),
        "prompt_ms": t.get("prompt_ms", 0.0), "predicted_ms": t.get("predicted_ms", 0.0),
        "predicted_n": t.get("predicted_n", resp.get("tokens_predicted", 0)),
    }


def summarize(rows, clips):
    ref = {c["id"]: c for c in clips}
    speech = [r for r in rows if r["kind"] in ("budget", "fleurs")]
    edits = total = cedits = ctotal = 0
    for r in speech:
        rs_, hs_ = syllables(ref[r["id"]]["ref"]), syllables(r["text"])
        edits += edit_distance(rs_, hs_)
        total += len(rs_)
        rc = "".join(strip_punct(ref[r["id"]]["ref"]).split())
        hc = "".join(strip_punct(r["text"]).split())
        cedits += edit_distance(rc, hc)
        ctotal += len(rc)
    budget = [r for r in rows if r["kind"] == "budget"]
    silence = [r for r in rows if r["kind"] == "silence"]
    aud = sum(r["audio_ms"] for r in speech) / 1000
    lat = [r["prompt_ms"] + r["predicted_ms"] for r in rows if r["kind"] != "silence"]
    return {
        "ser": edits / total if total else None,
        "cer": cedits / ctotal if ctotal else None,
        "amount_hits": sum(amount_hit(ref[r["id"]]["ref"], r["text"]) for r in budget),
        "amount_total": len(budget),
        "loops": sum(has_loop(r["text"], r["predicted_n"]) for r in rows),
        "silence_nonempty": sum(1 for r in silence if strip_punct(r["text"]).strip()),
        "silence_texts": [r["text"] for r in silence],
        "tokens_per_audio_s": (sum(r["predicted_n"] for r in speech) / aud) if aud else None,
        "p50_ms": statistics.median(lat) if lat else None,
        "clips": len(rows),
    }


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://127.0.0.1:8089")
    ap.add_argument("--clips", required=True)
    ap.add_argument("--mode", choices=["auto", "forced"], required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--language", default="Burmese", help="label used when --mode forced")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    clips = [json.loads(l) for l in open(a.clips, encoding="utf-8") if l.strip()]
    forced = a.language if a.mode == "forced" else None
    rows = []
    for c in clips:
        row = run_clip(a.server, c, forced)
        rows.append(row)
        print(f"{a.label} {a.mode} {c['id']}: {row['text'][:60]}", file=sys.stderr)
    summary = summarize(rows, clips)
    summary.update({"label": a.label, "mode": a.mode})
    with open(a.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
        f.write(json.dumps({"summary": summary}, ensure_ascii=False) + "\n")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
