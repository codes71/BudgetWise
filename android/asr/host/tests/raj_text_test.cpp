#include "raj_text.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#define CHECK_EQ(a, b)                                                                     \
    do {                                                                                   \
        const std::string _a = (a), _b = (b);                                              \
        if (_a != _b) {                                                                    \
            std::fprintf(stderr, "FAIL %s:%d: %s != %s\n  got:  [%s]\n  want: [%s]\n",     \
                         __FILE__, __LINE__, #a, #b, _a.c_str(), _b.c_str());              \
            std::exit(1);                                                                  \
        }                                                                                  \
    } while (0)

static std::string read_fixture(const char * name) {
    std::ifstream f(std::string(RAJ_FIXTURE_DIR) + "/" + name, std::ios::binary);
    if (!f) {
        std::fprintf(stderr, "FAIL cannot open fixture %s\n", name);
        std::exit(1);
    }
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

static std::string repeat(const std::string & s, int n) {
    std::string out;
    for (int i = 0; i < n; i++) out += s;
    return out;
}

static void strips_language_prefix() {
    std::string lang;
    CHECK_EQ(raj_strip_asr_prefix("language Burmese<asr_text> မင်္ဂလာပါ ", &lang), "မင်္ဂလာပါ");
    CHECK_EQ(lang, "Burmese");
}

static void language_none_is_empty() {
    std::string lang = "x";
    CHECK_EQ(raj_strip_asr_prefix("language None<asr_text>", &lang), "");
    CHECK_EQ(lang, "");
    CHECK_EQ(raj_postprocess("language None<asr_text>"), "");
}

static void no_tag_returns_whole_text() {
    std::string lang = "x";
    CHECK_EQ(raj_strip_asr_prefix("  ကော်ဖီ  ", &lang), "ကော်ဖီ");
    CHECK_EQ(lang, "");
    CHECK_EQ(raj_strip_asr_prefix("abc", nullptr), "abc");
}

static void collapses_char_run_over_threshold() {
    CHECK_EQ(raj_fix_repetitions(repeat("a", 21)), "a");
    CHECK_EQ(raj_fix_repetitions("x" + repeat("အ", 30) + "y"), "xအy");
    CHECK_EQ(raj_fix_repetitions(repeat("a", 20)), repeat("a", 20));
}

static void collapses_repeated_phrase() {
    const std::string phrase = "ကော်ဖီ ";
    CHECK_EQ(raj_fix_repetitions(repeat(phrase, 25)), phrase);
}

static void postprocess_collapses_repeated_phrase() {
    CHECK_EQ(raj_postprocess("language None<asr_text>" + repeat("ကော်ဖီ ", 25)), "ကော်ဖီ");
}

static void postprocess_loop_without_trailing_space() {
    // Upstream-faithful: the final copy lacks the trailing space so it is not part of the
    // matched run and survives as a leftover tail.
    CHECK_EQ(raj_postprocess(repeat("ကော်ဖီ ", 24) + "ကော်ဖီ"), "ကော်ဖီ ကော်ဖီ");
}

static void keeps_short_repeats() {
    CHECK_EQ(raj_fix_repetitions("၅၅၅"), "၅၅၅");
    CHECK_EQ(raj_postprocess("၅၅၅"), "၅၅၅");
}

static void postprocess_cleans_whitespace_and_zwsp() {
    CHECK_EQ(raj_postprocess("language Burmese<asr_text>  က\xE2\x80\x8B" "ခ \t\n  ဂ  "), "ကခ ဂ");
}

static void prompt_matches_spike() {
    CHECK_EQ(raj_build_prompt("", "Burmese"), read_fixture("prompt_forced.txt"));
}

static void prompt_matches_spike_auto() {
    CHECK_EQ(raj_build_prompt("", ""), read_fixture("prompt_auto.txt"));
}

static void prompt_context_in_system() {
    CHECK_EQ(raj_build_prompt("ctx", ""),
             "<|im_start|>system\nctx<|im_end|>\n<|im_start|>user\n<__media__><|im_end|>\n<|im_start|>assistant\n");
}

int main() {
    strips_language_prefix();
    language_none_is_empty();
    no_tag_returns_whole_text();
    collapses_char_run_over_threshold();
    collapses_repeated_phrase();
    postprocess_collapses_repeated_phrase();
    postprocess_loop_without_trailing_space();
    keeps_short_repeats();
    postprocess_cleans_whitespace_and_zwsp();
    prompt_matches_spike();
    prompt_matches_spike_auto();
    prompt_context_in_system();
    std::puts("raj_text: all tests passed");
    return 0;
}
