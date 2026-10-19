#include "raj_text.h"

#include <cctype>
#include <cstdint>

namespace {

const char * const kMarker = "<__media__>";
const char * const kAsrTag = "<asr_text>";

using u32 = std::u32string;

// Lenient UTF-8 decode: invalid bytes map to U+FFFD, one byte at a time.
u32 decode(const std::string & s) {
    u32 out;
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = (unsigned char) s[i];
        int len = 1;
        char32_t cp = 0xFFFD;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2;
            cp = c & 0x1F;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3;
            cp = c & 0x0F;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4;
            cp = c & 0x07;
        }
        if (len > 1) {
            bool ok = i + len <= n;
            for (int k = 1; ok && k < len; k++) {
                const unsigned char cc = (unsigned char) s[i + k];
                if ((cc & 0xC0) != 0x80) {
                    ok = false;
                } else {
                    cp = (cp << 6) | (cc & 0x3F);
                }
            }
            if (!ok) {
                len = 1;
                cp = 0xFFFD;
            }
        }
        out.push_back(cp);
        i += len;
    }
    return out;
}

std::string encode(const u32 & s) {
    std::string out;
    for (char32_t cp : s) {
        if (cp < 0x80) {
            out.push_back((char) cp);
        } else if (cp < 0x800) {
            out.push_back((char) (0xC0 | (cp >> 6)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back((char) (0xE0 | (cp >> 12)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        } else {
            out.push_back((char) (0xF0 | (cp >> 18)));
            out.push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
            out.push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            out.push_back((char) (0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

bool is_space(char32_t c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

std::string trim(const std::string & s) {
    size_t b = 0, e = s.size();
    while (b < e && std::isspace((unsigned char) s[b])) b++;
    while (e > b && std::isspace((unsigned char) s[e - 1])) e--;
    return s.substr(b, e - b);
}

std::string lower_ascii(std::string s) {
    for (char & c : s) c = (char) std::tolower((unsigned char) c);
    return s;
}

u32 fix_char_repeats(const u32 & s, int thresh) {
    u32 res;
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        size_t count = 1;
        while (i + count < n && s[i + count] == s[i]) count++;
        if (count > (size_t) thresh) {
            res.push_back(s[i]);
        } else {
            res.append(s, i, count);
        }
        i += count;
    }
    return res;
}

u32 fix_pattern_repeats(const u32 & s, int thresh, int max_len = 20) {
    const size_t n = s.size();
    const size_t t = (size_t) thresh;
    const size_t min_repeat_chars = t * 2;
    if (n < min_repeat_chars) return s;

    size_t i = 0;
    u32 result;
    bool found = false;
    while (i + min_repeat_chars <= n) {
        found = false;
        for (size_t k = 1; k <= (size_t) max_len; k++) {
            if (i + k * t > n) break;
            bool valid = true;
            for (size_t rep = 1; rep < t; rep++) {
                if (s.compare(i + rep * k, k, s, i, k) != 0) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                size_t end_index = i + t * k;
                while (end_index + k <= n && s.compare(end_index, k, s, i, k) == 0) end_index += k;
                result.append(s, i, k);
                result += fix_pattern_repeats(s.substr(end_index), thresh, max_len);
                found = true;
                break;
            }
        }
        if (found) break;
        result.push_back(s[i]);
        i++;
    }
    if (!found) result.append(s, i, u32::npos);
    return result;
}

}  // namespace

std::string raj_build_prompt(const std::string & context, const std::string & forced_language) {
    std::string p = "<|im_start|>system\n" + context + "<|im_end|>\n<|im_start|>user\n" + kMarker +
                    "<|im_end|>\n<|im_start|>assistant\n";
    if (!forced_language.empty()) {
        p += "language " + forced_language + kAsrTag;
    }
    return p;
}

namespace {

// Shared by raj_strip_asr_prefix (trimmed) and raj_postprocess (untrimmed, so the repetition
// fix sees the transcript exactly as decoded).
std::string strip_prefix(const std::string & raw, std::string * language_out, bool do_trim) {
    const auto fin = [do_trim](const std::string & t) { return do_trim ? trim(t) : t; };
    if (language_out) language_out->clear();
    const size_t pos = raw.find(kAsrTag);
    if (pos == std::string::npos) return fin(raw);

    const std::string head = raw.substr(0, pos);
    const std::string tail = fin(raw.substr(pos + std::char_traits<char>::length(kAsrTag)));

    // Mirror upstream parse_asr_output: "language none" means unknown language, and the label
    // is taken from the first non-empty line starting with "language".
    if (lower_ascii(head).find("language none") != std::string::npos) return tail;

    if (language_out) {
        size_t line_start = 0;
        while (line_start <= head.size()) {
            size_t nl = head.find('\n', line_start);
            std::string line = trim(head.substr(line_start, nl == std::string::npos ? std::string::npos : nl - line_start));
            if (!line.empty()) {
                const std::string prefix = "language ";
                if (lower_ascii(line).compare(0, prefix.size(), prefix) == 0) {
                    *language_out = trim(line.substr(prefix.size()));
                }
                break;
            }
            if (nl == std::string::npos) break;
            line_start = nl + 1;
        }
    }
    return tail;
}

}  // namespace

std::string raj_strip_asr_prefix(const std::string & raw, std::string * language_out) {
    return strip_prefix(raw, language_out, true);
}

// Ported from QwenLM/Qwen3-ASR qwen_asr/inference/utils.py detect_and_fix_repetitions,
// upstream commit 9567667698f195fa807b1581de5c03184e63d2b0 (last change to that file on main).
std::string raj_fix_repetitions(const std::string & s, int threshold) {
    if (threshold < 1) return s;
    return encode(fix_pattern_repeats(fix_char_repeats(decode(s), threshold), threshold));
}

std::string raj_postprocess(const std::string & raw) {
    const std::string fixed = raj_fix_repetitions(strip_prefix(raw, nullptr, false));
    u32 out;
    bool prev_space = false;
    for (char32_t c : decode(fixed)) {
        if (c == 0x200B) continue;
        if (is_space(c)) {
            if (!prev_space) out.push_back(' ');
            prev_space = true;
        } else {
            out.push_back(c);
            prev_space = false;
        }
    }
    return trim(encode(out));
}
