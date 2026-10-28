# Unsplice

Unsplice is an unfinished Android prototype for local podcast repetition analysis. It is not yet a usable podcast player and does not automatically skip ads.

The project currently contains an Android/NDK build, an experimental native peak-pair fingerprint generator, and a standalone cross-episode matching prototype. The matcher excludes the first three minutes and matches lasting three minutes or more. These heuristics have only been tested on synthetic inputs; they cannot reliably identify ads without further validation.

Feed ingestion, downloads, decoding, persistent storage, background work, playback, user controls, intra-episode detection, theme recognition and cleanup remain to be implemented. The fingerprint generator currently reads a whole PCM file into memory and its speed, accuracy, and memory use on real episodes have not been measured. Do not use the detected intervals for automatic skipping yet.

## Ad detection

Each detected segment records its `source` and a `confidence` from 0 to 1. Segments at or above `AUTO_SKIP_CONFIDENCE` (0.5) are skipped automatically when the podcast has auto-skip on. Lower-confidence segments only show a "Skip ad" button while playing.

| Source | How it is found | Confidence |
| --- | --- | --- |
| `chapter` | ID3 `CHAP` frames in the audio file whose title names an ad or sponsor | 0.9 |
| `chapter` | Podcasting 2.0 `<podcast:chapters>` JSON from the feed, used only when the file has no ID3 chapters. These may not account for dynamically inserted ads, so they are trusted less. | 0.7 |
| `repeat` | Audio fingerprints that repeat within or across episodes of the same podcast | 0.6 |

Sources are checked in that order. A later segment that overlaps an earlier one is not stored.

Playback places an ExoPlayer `PlayerMessage` at the start of each auto-skip segment, so the jump happens at the exact boundary. Seeking by hand into the middle of a segment plays it.

To build, install Android SDK 35, NDK 27.1.12297006, CMake 3.22.1, and a Java 17 or newer JDK. Set `ANDROID_HOME` to the Android SDK directory, then run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

To run the host native smoke test with a C++17 compiler:

```sh
clang++ -O2 -std=c++17 tests/fingerprint_test.cpp app/src/main/cpp/fingerprint.cpp -o /tmp/echomute-fingerprint-test
/tmp/echomute-fingerprint-test
```

## Install on an Android phone or tablet

The debug APK is a signed, universal APK for Android 8.0 (API 26) or newer. It opens the prototype screen; podcast features are not implemented yet.

1. Build it with `./gradlew :app:assembleDebug`.
2. Copy `app/build/outputs/apk/debug/app-debug.apk` to your device and open it from the device's Files app.
3. If Android asks, allow your file manager to install apps from this source, then install Unsplice.

Alternatively, connect a device with USB debugging enabled and run `adb install -r app/build/outputs/apk/debug/app-debug.apk`. This build uses the local Android debug signing key. Builds signed with a different key cannot update it without uninstalling it first, which removes its app data.

The launcher artwork was generated with the built-in image generation tool using this prompt: "A distinctive, simple, modern audio waveform mark suggesting a gap being seamlessly joined or skipped, in bright mint and soft ivory on a deep midnight navy square background; bold, legible at small sizes, with no text." The original is in `artwork/unsplice-icon-source.png`.

## Build a release APK

Push a version tag to build and publish a signed APK on GitHub Releases:

```sh
git tag v0.1.0
git push origin v0.1.0
```

Use `vMAJOR.MINOR.PATCH`, with major at most 99 and minor and patch at most 999. Use increasing version numbers so Android can install an update. The tag supplies both the APK version and its filename; the release also includes a SHA-256 checksum. The workflow runs tests before publishing.

The workflow signs with the repository's `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` secrets. Keep a secure backup of the release keystore and its password: losing them prevents future APKs from updating existing installations. The release signing key differs from the local debug key, so installing a release over an existing debug build requires uninstalling the debug build first, which removes its app data.
