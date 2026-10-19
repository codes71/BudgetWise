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
