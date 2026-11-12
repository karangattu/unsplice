#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
struct FingerprintPoint { uint32_t hash; uint32_t time_ms; };
std::vector<FingerprintPoint> fingerprint(const int16_t* samples, size_t sampleCount);
std::vector<FingerprintPoint> fingerprint(const std::vector<int16_t>& samples);
