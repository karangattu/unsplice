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
}
