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

    // StreamingAudioAnalyzer resamples stereo, extracts 5 Hz envelope, and caps 8 hashes/sec across 32s+ seams.
    const size_t totalStereoSamples = 16000 * 36;
    std::vector<uint8_t> stereoLe(totalStereoSamples * 4);
    for (size_t i = 0; i < totalStereoSamples; ++i) {
        int16_t s = static_cast<int16_t>(8000 * sin(2 * 3.141592653589793 * 700.0 * i / 16000.0));
        uint8_t lo = static_cast<uint8_t>(s & 0xFF);
        uint8_t hi = static_cast<uint8_t>((s >> 8) & 0xFF);
        stereoLe[i * 4] = lo;
        stereoLe[i * 4 + 1] = hi;
        stereoLe[i * 4 + 2] = lo;
        stereoLe[i * 4 + 3] = hi;
    }
    StreamingAudioAnalyzer analyzer(16000, 2, 8000, 5, true);
    const size_t stepBytes = 4096;
    for (size_t off = 0; off < stereoLe.size(); off += stepBytes) {
        analyzer.feedPcmBytes(stereoLe.data() + off, std::min(stepBytes, stereoLe.size() - off));
    }
    analyzer.finish();
    assert(analyzer.envelope().size() == 36 * 5);
    assert(!analyzer.fingerprints().empty());
    uint32_t prevSec = 0;
    int secCount = 0;
    for (uint64_t p : analyzer.fingerprints()) {
        uint32_t ms = static_cast<uint32_t>(p & 0xffffffffull);
        uint32_t sec = ms / 1000u;
        if (sec != prevSec) { prevSec = sec; secCount = 0; }
        secCount++;
        assert(secCount <= 8);
    }
}
