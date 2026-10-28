#include "../app/src/main/cpp/fingerprint.h"
#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>
int main() {
    std::vector<int16_t> silence(8000 * 4, 0);
    assert(fingerprint(silence).empty());
    std::vector<int16_t> signal(8000 * 4);
    for (size_t i = 0; i < signal.size(); ++i)
        signal[i] = static_cast<int16_t>(10000 * sin(2 * 3.141592653589793 * (i < 16000 ? 500 : 1000) * i / 8000));
    auto result = fingerprint(signal);
    assert(!result.empty());
    for (auto point : result) assert(point.time_ms < 4000);

    // Catch changes to emitted hashes, timestamps, and their order on a mixed signal.
    std::vector<int16_t> mixed(8000 * 4);
    uint32_t rng = 1;
    for (size_t i = 0; i < mixed.size(); ++i) {
        rng = rng * 1664525u + 1013904223u;
        int period = (i / 16000) % 3 == 0 ? 16 : ((i / 16000) % 3 == 1 ? 25 : 40);
        int tone = i % period < static_cast<size_t>(period / 2) ? 6500 : -6500;
        mixed[i] = static_cast<int16_t>(tone + static_cast<int>((rng >> 24) & 255) * 16 - 2048);
    }
    auto mixedResult = fingerprint(mixed);
    uint64_t digest = 14695981039346656037ull;
    for (const auto& point : mixedResult) {
        digest = (digest ^ point.hash) * 1099511628211ull;
        digest = (digest ^ point.time_ms) * 1099511628211ull;
    }
    assert(mixedResult.size() == 2912);
    assert(digest == 0x7fbc8ee94d875aa5ull);
}
