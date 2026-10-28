#pragma once

#include <string>

// ChatML prompt with one <__media__> marker. Empty forced_language means auto mode.
std::string raj_build_prompt(const std::string & context, const std::string & forced_language);

// Output is NOT sanitized (no UTF-8 validation, repeats or ZWSP left in); callers must use
// raj_postprocess for display text.
// Splits "language X<asr_text>transcript". Returns the trimmed transcript and sets *language_out
// (may be null) to the label, or "" when absent or "None". No tag returns the whole trimmed text.
std::string raj_strip_asr_prefix(const std::string & raw, std::string * language_out);

// Port of qwen_asr detect_and_fix_repetitions, operating on code points.
std::string raj_fix_repetitions(const std::string & s, int threshold = 20);

// Strip prefix, fix repeats, drop U+200B, collapse spaces, trim.
std::string raj_postprocess(const std::string & raw);

// UTF-8 to UTF-16 for JNI NewString (NewStringUTF rejects 4-byte sequences). Each invalid byte
// becomes U+FFFD and a sequence cut off by the end of input is dropped, so unsanitized model
// output (raw) is safe to convert. Code points above U+FFFF become surrogate pairs.
std::u16string raj_utf8_to_utf16(const std::string & s);
