#include "fingerprint.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>

namespace {

constexpr int kFftSize = 512;
constexpr int kHalfFft = 256;
constexpr int kHop = 256;
constexpr size_t kChunkFrames = 1000;
constexpr size_t kChunkSamples = kChunkFrames * kHop;
constexpr size_t kOverlapSamples = 64 * kHop + kFftSize;

struct Peak { int bin; int frame; };
struct FramePeaks {
    Peak peaks[4];
    int count = 0;
};

void computeFramePeaks(const int16_t* samples, size_t frameCount, std::vector<FramePeaks>& out) {
    constexpr float pi = 3.14159265358979323846f;
    static const auto window = [] {
        std::array<float, kFftSize> values{};
        for (int i = 0; i < kFftSize; ++i) values[i] = 0.5f - 0.5f * cosf(2.0f * pi * i / (kFftSize - 1));
        return values;
    }();
    static const auto rev256 = [] {
        std::array<uint8_t, kHalfFft> values{};
        for (int i = 0; i < kHalfFft; ++i) {
            unsigned r = 0;
            for (int j = 0; j < 8; ++j) r = (r << 1) | ((i >> j) & 1);
            values[i] = static_cast<uint8_t>(r);
        }
        return values;
    }();
    static const auto tw = [] {
        struct T { float cos[kHalfFft / 2]; float sin[kHalfFft / 2]; } t{};
        for (int k = 0; k < kHalfFft / 2; ++k) {
            float angle = -2.0f * pi * k / kHalfFft;
            t.cos[k] = cosf(angle);
            t.sin[k] = sinf(angle);
        }
        return t;
    }();
    static const auto unpackTw = [] {
        struct U { float cos[kHalfFft]; float sin[kHalfFft]; } u{};
        for (int k = 0; k < kHalfFft; ++k) {
            float angle = -2.0f * pi * k / kFftSize;
            u.cos[k] = cosf(angle);
            u.sin[k] = sinf(angle);
        }
        return u;
    }();

    out.resize(frameCount);
    const int edges[] = {19, 38, 77, 154, 256};

    for (size_t f = 0, base = 0; f < frameCount; ++f, base += kHop) {
        float re[kHalfFft], im[kHalfFft];
        for (int i = 0; i < kHalfFft; ++i) {
            uint8_t r = rev256[i];
            re[r] = samples[base + 2 * i] * window[2 * i];
            im[r] = samples[base + 2 * i + 1] * window[2 * i + 1];
        }
        for (int len = 2, step = kHalfFft / 2; len <= kHalfFft; len <<= 1, step >>= 1) {
            int half = len >> 1;
            for (int i = 0; i < kHalfFft; i += len) {
                for (int j = 0, twIdx = 0; j < half; ++j, twIdx += step) {
                    float wr = tw.cos[twIdx], wi = tw.sin[twIdx];
                    float br = re[i + j + half], bi = im[i + j + half];
                    float tr = wr * br - wi * bi;
                    float ti = wr * bi + wi * br;
                    float ar = re[i + j], ai = im[i + j];
                    re[i + j] = ar + tr;
                    im[i + j] = ai + ti;
                    re[i + j + half] = ar - tr;
                    im[i + j + half] = ai - ti;
                }
            }
        }
        float power[kHalfFft];
        for (int k = 19; k < kHalfFft; ++k) {
            int mk = kHalfFft - k;
            float xr = re[k], xi = im[k];
            float yr = re[mk], yi = im[mk];
            float er = 0.5f * (xr + yr);
            float ei = 0.5f * (xi - yi);
            float or_ = 0.5f * (xi + yi);
            float oi = 0.5f * (yr - xr);
            float wc = unpackTw.cos[k], ws = unpackTw.sin[k];
            float tr = wc * or_ - ws * oi;
            float ti = wc * oi + ws * or_;
            float fr = er + tr;
            float fi = ei + ti;
            power[k] = fr * fr + fi * fi;
        }
        auto& fp = out[f];
        fp.count = 0;
        for (int band = 0; band < 4; ++band) {
            float sum = 0.0f, maximum = 0.0f;
            int best = edges[band];
            for (int k = edges[band]; k < edges[band + 1]; ++k) {
                float p = power[k];
                sum += p;
                if (p > maximum) { maximum = p; best = k; }
            }
            if (maximum > 1e7f && maximum > 3.0f * sum / (edges[band + 1] - edges[band])) {
                fp.peaks[fp.count++] = {best, static_cast<int>(f)};
            }
        }
    }
}

inline int16_t readLe16(const uint8_t* p) {
    return static_cast<int16_t>(static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8));
}

}  // namespace

std::vector<FingerprintPoint> fingerprint(const int16_t* samples, size_t sampleCount) {
    if (sampleCount < kFftSize) return {};
    size_t totalFrames = (sampleCount - kFftSize) / kHop + 1;
    std::vector<FramePeaks> frames;
    computeFramePeaks(samples, totalFrames, frames);

    std::vector<FingerprintPoint> result;
    result.reserve(totalFrames * 2);
    for (size_t i = 0; i < frames.size(); i += 4) {
        for (int a = 0; a < frames[i].count; ++a) {
            const auto& anchor = frames[i].peaks[a];
            for (size_t j = i + 4; j < frames.size() && j <= i + 60; j += 8) {
                for (int b = 0; b < frames[j].count; ++b) {
                    const auto& target = frames[j].peaks[b];
                    uint32_t hash = (anchor.bin << 17) | (target.bin << 9) | (target.frame - anchor.frame);
                    result.push_back({hash, static_cast<uint32_t>(i * 32)});
                }
            }
        }
    }
    return result;
}

std::vector<FingerprintPoint> fingerprint(const std::vector<int16_t>& samples) {
    return fingerprint(samples.data(), samples.size());
}

StreamingAudioAnalyzer::StreamingAudioAnalyzer(
    int sampleRate,
    int channels,
    int targetRate,
    int envelopeRate,
    bool enableFingerprint
)
    : sampleRate_(sampleRate),
      channels_(channels > 0 ? channels : 1),
      targetRate_(targetRate > 0 ? targetRate : 8000),
      envelopeRate_(envelopeRate),
      enableFingerprint_(enableFingerprint) {
    if (sampleRate_ < targetRate_) sampleRate_ = targetRate_;
    if (envelopeRate_ > 0) envStep_ = std::max(1, targetRate_ / envelopeRate_);
    if (enableFingerprint_) pcm_.reserve(kChunkSamples + kOverlapSamples + 8192);
}

void StreamingAudioAnalyzer::configure(int sampleRate, int channels) {
    if (sampleRate > 0) sampleRate_ = sampleRate >= targetRate_ ? sampleRate : targetRate_;
    if (channels > 0) channels_ = channels;
}

inline void StreamingAudioAnalyzer::pushResampledSample(int16_t sample) {
    if (envStep_ > 0) {
        int32_t a = std::abs(static_cast<int32_t>(sample));
        if (a > envPeak_) envPeak_ = a;
        if (++envSampleCount_ >= envStep_) {
            envelope_.push_back(envPeak_);
            envPeak_ = 0;
            envSampleCount_ = 0;
        }
    }
    if (enableFingerprint_) {
        pcm_.push_back(sample);
    }
}

void StreamingAudioAnalyzer::feedPcmBytes(const uint8_t* bytes, size_t byteLength) {
    if (!bytes || byteLength == 0 || finished_) return;
    const size_t frameBytes = static_cast<size_t>(channels_) * 2;
    const size_t frameCount = byteLength / frameBytes;
    const int sr = sampleRate_;
    const int tr = targetRate_;
    const int ch = channels_;

    if (ch == 1) {
        for (size_t i = 0; i < frameCount; ++i) {
            sum_ += readLe16(bytes + i * 2);
            count_++;
            phase_ += tr;
            if (phase_ >= sr) {
                pushResampledSample(static_cast<int16_t>(sum_ / count_));
                phase_ -= sr;
                sum_ = 0;
                count_ = 0;
            }
        }
    } else if (ch == 2) {
        for (size_t i = 0; i < frameCount; ++i) {
            const uint8_t* p = bytes + i * 4;
            int32_t s0 = readLe16(p);
            int32_t s1 = readLe16(p + 2);
            sum_ += (s0 + s1) / 2;
            count_++;
            phase_ += tr;
            if (phase_ >= sr) {
                pushResampledSample(static_cast<int16_t>(sum_ / count_));
                phase_ -= sr;
                sum_ = 0;
                count_ = 0;
            }
        }
    } else {
        for (size_t i = 0; i < frameCount; ++i) {
            const uint8_t* p = bytes + i * frameBytes;
            int64_t mono = 0;
            for (int c = 0; c < ch; ++c) mono += readLe16(p + c * 2);
            sum_ += mono / ch;
            count_++;
            phase_ += tr;
            if (phase_ >= sr) {
                pushResampledSample(static_cast<int16_t>(sum_ / count_));
                phase_ -= sr;
                sum_ = 0;
                count_ = 0;
            }
        }
    }

    if (enableFingerprint_) {
        while (pcm_.size() >= kChunkSamples + kOverlapSamples) {
            processChunk(kChunkFrames);
            pcm_.erase(pcm_.begin(), pcm_.begin() + kChunkSamples);
            timeOffsetMs_ += static_cast<uint32_t>((kChunkSamples * 1000ULL) / static_cast<uint64_t>(targetRate_));
        }
    }
}

void StreamingAudioAnalyzer::processChunk(size_t maxAnchorFrames) {
    if (pcm_.size() < kFftSize) return;
    size_t totalFrames = (pcm_.size() - kFftSize) / kHop + 1;
    size_t anchorLimit = std::min(totalFrames, maxAnchorFrames);
    size_t neededFrames = std::min(totalFrames, anchorLimit + 61);
    std::vector<FramePeaks> frames;
    computeFramePeaks(pcm_.data(), neededFrames, frames);

    for (size_t i = 0; i < anchorLimit; i += 4) {
        uint32_t timeMs = timeOffsetMs_ + static_cast<uint32_t>(i * 32);
        uint32_t sec = timeMs / 1000u;
        if (sec != lastSec_) {
            lastSec_ = sec;
            secCount_ = 0;
        }
        if (secCount_ >= 8) continue;
        for (int a = 0; a < frames[i].count; ++a) {
            const auto& anchor = frames[i].peaks[a];
            for (size_t j = i + 4; j < frames.size() && j <= i + 60; j += 8) {
                for (int b = 0; b < frames[j].count; ++b) {
                    const auto& target = frames[j].peaks[b];
                    uint32_t hash = (anchor.bin << 17) | (target.bin << 9) | (target.frame - anchor.frame);
                    bool seen = false;
                    for (int k = 0; k < secCount_; ++k) {
                        if (secHashes_[k] == hash) { seen = true; break; }
                    }
                    if (!seen) {
                        secHashes_[secCount_++] = hash;
                        packed_.push_back((static_cast<uint64_t>(hash) << 32) | timeMs);
                        if (secCount_ >= 8) break;
                    }
                }
                if (secCount_ >= 8) break;
            }
            if (secCount_ >= 8) break;
        }
    }
}

void StreamingAudioAnalyzer::finish() {
    if (finished_) return;
    finished_ = true;
    if (enableFingerprint_ && pcm_.size() >= kFftSize) {
        processChunk(SIZE_MAX);
        pcm_.clear();
    }
}
