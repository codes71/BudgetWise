import run_spike as rs


def test_parse_raw_strips_language_prefix():
    assert rs.parse_raw("language Burmese<asr_text>ကော်ဖီ") == ("Burmese", "ကော်ဖီ")


def test_parse_raw_language_none_is_empty():
    assert rs.parse_raw("language None<asr_text>") == ("None", "")


def test_parse_raw_without_tag_returns_raw_text():
    assert rs.parse_raw("hello") == ("", "hello")


def test_build_prompt_forced_appends_tag():
    p = rs.build_prompt("Burmese")
    assert p.endswith("<|im_start|>assistant\nlanguage Burmese<asr_text>")
    assert p.count("<__media__>") == 1


def test_build_prompt_auto_ends_at_assistant():
    p = rs.build_prompt(None)
    assert p.endswith("<|im_start|>assistant\n")
    assert p.count("<__media__>") == 1


def test_ser_identical_is_zero():
    assert rs.ser("ကော်ဖီ သုံးထောင်", "ကော်ဖီ သုံးထောင်") == 0.0


def test_ser_all_wrong_is_one():
    assert rs.ser("ကခ", "ဂဃ") == 1.0


def test_syllables_splits_on_consonant_starts():
    assert rs.syllables("မနက်") == ["မ", "နက်"]


def test_amount_hit_requires_exact_number_phrase():
    assert rs.amount_hit("ကော်ဖီ သုံးထောင်", "ကော်ဖီ သုံးထောင်") is True
    assert rs.amount_hit("သုံးထောင်", "သုံးသောင်း") is False


def test_has_loop_detects_repeated_phrase():
    assert rs.has_loop("သုံးထောင် " * 12) is True
    assert rs.has_loop("ကော်ဖီ သုံးထောင် ပေးခဲ့တယ်") is False
    assert rs.has_loop("", n_predicted=512) is True


def test_ser_ignores_burmese_punctuation():
    assert rs.ser("ကော်ဖီ သုံးထောင်", "ကော်ဖီ သုံးထောင်။") == 0.0
    assert rs.cer("ကော်ဖီ", "ကော်ဖီ။") == 0.0


def test_numbers_words_and_digits_agree():
    assert rs.numbers("သုံးထောင်") == [3000]
    assert rs.numbers("၃၀၀၀") == [3000]
    assert rs.numbers("3,000") == [3000]
    assert rs.numbers("သုံးသောင်း") == [30000]
    assert rs.numbers("နှစ်ထောင့်ငါးရာ") == [2500]
    assert rs.numbers("ဆယ့်ငါး") == [15]


def test_amount_hit_word_ref_digit_hyp():
    assert rs.amount_hit("ကော်ဖီ သုံးထောင် ပေးခဲ့တယ်", "ကော်ဖီ ၃၀၀၀ ပေးခဲ့တယ်။") is True
    assert rs.amount_hit("သုံးထောင်", "သုံးသောင်း") is False


def test_amount_hit_allows_extra_numbers_in_hyp():
    assert rs.amount_hit("သုံးထောင်", "၂ ခု ၃၀၀၀ ကျပ်") is True
    assert rs.amount_hit("သုံးထောင် နှစ်ရာ", "သုံးထောင်") is False


def test_ordinary_words_are_not_numbers():
    assert rs.numbers("ဝယ်ခဲ့တယ်") == []
    assert rs.numbers("ရာခိုင်နှုန်း ရာစု") == []
    assert rs.amount_hit("ဝယ်ခဲ့တယ်", "ဝယ်ခဲ့တယ်") is False


def test_summarize_counts_amount_hits_and_silence():
    clips = [{"id": "b1", "kind": "budget", "ref": "သုံးထောင်"},
             {"id": "s1", "kind": "silence", "ref": ""}]
    rows = [{"id": "b1", "kind": "budget", "text": "၃၀၀၀", "audio_ms": 2000,
             "prompt_ms": 10.0, "predicted_ms": 90.0, "predicted_n": 20},
            {"id": "s1", "kind": "silence", "text": "", "audio_ms": 5000,
             "prompt_ms": 5.0, "predicted_ms": 5.0, "predicted_n": 4}]
    s = rs.summarize(rows, clips)
    assert s["amount_hits"] == 1 and s["amount_total"] == 1
    assert s["silence_nonempty"] == 0 and s["tokens_per_audio_s"] == 10.0


def test_run_clip_forced_keeps_raw_unmodified(monkeypatch, tmp_path):
    import wave
    p = tmp_path / "a.wav"
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000)
        w.writeframes(b"\0\0" * 16000)
    monkeypatch.setattr(rs, "transcribe", lambda server, wav, forced: {
        "content": "ကော်ဖီ", "timings": {"predicted_n": 3, "prompt_ms": 1.0, "predicted_ms": 2.0}})
    row = rs.run_clip("x", {"id": "c", "wav": str(p), "kind": "fleurs"}, "Burmese")
    assert row["raw"] == "ကော်ဖီ" and row["text"] == "ကော်ဖီ" and row["language"] == "Burmese"
    assert row["audio_ms"] == 1000
