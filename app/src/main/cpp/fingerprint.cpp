#include "fingerprint.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>

std::vector<FingerprintPoint> fingerprint(const std::vector<int16_t>& samples) {
    constexpr int n = 512, hop = 256;
    constexpr double pi = 3.14159265358979323846;
    static const auto window = [] {
        std::array<double, n> values{};
        for (int i = 0; i < n; ++i) values[i] = 0.5 - 0.5 * cos(2 * pi * i / (n - 1));
        return values;
    }();
    static const auto reversedBins = [] {
        std::array<unsigned, n> values{};
        for (int i = 0; i < n; ++i) {
            unsigned reversed = 0;
            for (int j = 0; j < 9; ++j) reversed = (reversed << 1) | ((i >> j) & 1);
            values[i] = reversed;
        }
        return values;
    }();
    static const auto steps = [] {
        std::array<std::complex<double>, 9> values{};
        for (int len = 2, stage = 0; len <= n; len *= 2, ++stage)
            values[stage] = std::polar(1.0, -2 * pi / len);
        return values;
    }();
    struct Peak { int bin; int frame; };
    std::vector<std::vector<Peak>> frames;
    for (size_t base = 0; base + n <= samples.size(); base += hop) {
        std::array<std::complex<double>, n> fft;
        for (int i = 0; i < n; ++i) {
            fft[reversedBins[i]] = samples[base + i] * window[i];
        }
        for (int len = 2, stage = 0; len <= n; len *= 2, ++stage) {
            auto step = steps[stage];
            for (int i = 0; i < n; i += len) {
                std::complex<double> w = 1;
                for (int j = 0; j < len / 2; ++j) {
                    auto a = fft[i + j], b = w * fft[i + j + len / 2];
                    fft[i + j] = a + b; fft[i + j + len / 2] = a - b; w *= step;
                }
            }
        }
        std::vector<Peak> peaks;
        const int edges[] = {19, 38, 77, 154, 256};
        for (int band = 0; band < 4; ++band) {
            double sum = 0, maximum = 0; int best = edges[band];
            for (int k = edges[band]; k < edges[band + 1]; ++k) {
                double power = std::norm(fft[k]); sum += power;
                if (power > maximum) { maximum = power; best = k; }
            }
            if (maximum > 1e7 && maximum > 3 * sum / (edges[band + 1] - edges[band])) peaks.push_back({best, static_cast<int>(frames.size())});
        }
        frames.push_back(std::move(peaks));
    }
    std::vector<FingerprintPoint> result;
    for (size_t i = 0; i < frames.size(); i += 4) {
        for (const auto& anchor : frames[i]) {
            for (size_t j = i + 4; j < frames.size() && j <= i + 60; j += 8) {
                for (const auto& target : frames[j]) {
                    uint32_t hash = (anchor.bin << 17) | (target.bin << 9) | (target.frame - anchor.frame);
                    result.push_back({hash, static_cast<uint32_t>(i * 32)});
                }
            }
        }
    }
    return result;
}
