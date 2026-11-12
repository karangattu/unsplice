#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

struct FingerprintPoint { uint32_t hash; uint32_t time_ms; };

std::vector<FingerprintPoint> fingerprint(const int16_t* samples, size_t sampleCount);
std::vector<FingerprintPoint> fingerprint(const std::vector<int16_t>& samples);

class StreamingAudioAnalyzer {
public:
    StreamingAudioAnalyzer(int sampleRate, int channels, int targetRate = 8000, int envelopeRate = 0, bool enableFingerprint = true);

    void configure(int sampleRate, int channels);
    void feedPcmBytes(const uint8_t* bytes, size_t byteLength);
    void finish();

    const std::vector<uint64_t>& fingerprints() const { return packed_; }
    const std::vector<int32_t>& envelope() const { return envelope_; }

private:
    void pushResampledSample(int16_t sample);
    void processChunk(size_t maxAnchorFrames);

    int sampleRate_;
    int channels_;
    int targetRate_;
    int envelopeRate_;
    bool enableFingerprint_;
    int envStep_ = 0;
    int envSampleCount_ = 0;
    int32_t envPeak_ = 0;

    int64_t phase_ = 0;
    int64_t sum_ = 0;
    int count_ = 0;

    uint32_t timeOffsetMs_ = 0;
    uint32_t lastSec_ = UINT32_MAX;
    uint32_t secHashes_[8]{};
    int secCount_ = 0;
    bool finished_ = false;

    std::vector<int16_t> pcm_;
    std::vector<uint64_t> packed_;
    std::vector<int32_t> envelope_;
};
