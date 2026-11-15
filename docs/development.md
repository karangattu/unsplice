# Unsplice

Unsplice is an Android podcast player with podcast search, RSS subscriptions, downloads, background playback, and automatic skipping of detected ad segments. Detection is experimental: it can miss ads or identify other repeated audio as an ad. You can turn auto-skip off for a podcast or keep an individual detected segment.

Use Discover to find a podcast or enter a feed URL. In Library, open a podcast to play, download, or add episodes to Up Next. Episodes show their publish date and how much is left to listen; the list is newest first and can be flipped to oldest first. Feeds are refreshed on launch (at most every 15 minutes) and with the refresh button on a podcast. The queue keeps the current episode at the top; use the arrows to reorder upcoming episodes or the remove button to take one out. The queue and playback position are saved between sessions, and each episode resumes where it left off.

Playing or queueing an episode downloads it and finds its ads in the background, so by the time the queue reaches it the ads are known. Until then it streams. Once the download exists the queue item switches to it, because a stream can carry different ads from the file that was analysed. An episode that plays to the end is marked as played.

The matcher excludes the first three minutes and matches lasting three minutes or more. The fingerprint generator reads a whole PCM file into memory; its speed, accuracy, and memory use on real episodes have not been measured.

## Ad detection

Each detected segment records its `source` and a `confidence` from 0 to 1. Segments at or above `AUTO_SKIP_CONFIDENCE` (0.5) are skipped automatically when the podcast has auto-skip on. Lower-confidence segments only show a "Skip ad" button while playing.

| Source | How it is found | Confidence |
| --- | --- | --- |
| `marker` | For Omny Studio and ART19 episodes with publisher markers and an ad-free waveform, compares the waveform to the downloaded audio to find each break's length. Breaks that line up poorly (correlation below 0.4) are manual suggestions. | 0.9 for a strong match |
| `chapter` | ID3 `CHAP` frames in the audio file whose title names an ad or sponsor | 0.9 |
| `chapter` | Chapters from the feed can qualify for automatic skipping when the feed duration matches the downloaded audio. If the duration is missing or differs, they are manual suggestions. | 0.85 for a verified sponsor chapter |
| `repeat` | Audio fingerprints that repeat within an episode or across any downloaded episode in the library, since networks insert the same ads into every show | 0.6 |
| `transcript` | Timed text with wording that suggests a sponsor read. A strong transcript cue can qualify for automatic skipping if the feed duration matches the audio and both edges match an MP3 splice. | 0.3–0.45 by itself |
| `splice` | A sustained change in MP3 encoding that can point to an inserted break. | 0.45 |
| `acoustic` | A louder stretch of audio separated by pauses or the start or end of an episode. | 0.45 |

While detection runs, the episode shows "Finding ads…". "Scan again" clears detected segments and runs detection on the existing download. A segment that qualifies for automatic skipping can replace an overlapping manual suggestion.

### What stops this working on every feed

Nothing in RSS says where the ads are, so coverage depends on the host:

- **Dynamic insertion (Omny, Megaphone, Acast, ART19, Spreaker, Libsyn, Podbean, and others)**: ads are stitched into the file per download. When publisher markers and a clean waveform are unavailable, Unsplice falls back to the other signals above.
- **Host-read ads baked into the audio**: identical across listeners, but unique per episode, so neither markers nor repeats catch them. Chapters catch them when the publisher labels them.
- **The `repeat` detector** ignores the first three minutes, needs at least 15 s of matching audio, and treats any repeated audio as an ad, so recurring segments such as a long outro are flagged and a one-off ad never is.

To build, install Android SDK 35, NDK 27.1.12297006, CMake 3.22.1, and a Java 17 or newer JDK. Set `ANDROID_HOME` to the Android SDK directory, then run the tests and build:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

To run the host native smoke test with a C++17 compiler:

```sh
clang++ -O2 -std=c++17 tests/fingerprint_test.cpp app/src/main/cpp/fingerprint.cpp -o /tmp/echomute-fingerprint-test
/tmp/echomute-fingerprint-test
```

## Install on an Android phone or tablet

Install the signed APK from the latest [GitHub release](https://github.com/karangattu/unsplice/releases/latest): open the `.apk` asset on the device and, if Android asks, allow the browser or file manager to install apps from this source. Every release is signed with the same key and carries a higher version code, so opening a newer release's APK updates the app in place and keeps its subscriptions, downloads and listening positions. The app checks the latest release about every six hours and shows a Download banner on the Library screen when a newer one is published. Tapping Download fetches the APK inside the app and hands the finished file to Android's installer; grant Unsplice the "install unknown apps" permission if Android asks. The app downloads the APK itself rather than sending the release URL to a browser, because the browser's redirect to GitHub's signed asset URL can restart the transfer indefinitely.

For development, build the debug APK with `./gradlew :app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk` from the device's Files app, or connect a device with USB debugging enabled and run `adb install -r app/build/outputs/apk/debug/app-debug.apk`. This build uses the local Android debug signing key, so it cannot update a release build and a release cannot update it; switching between the two means uninstalling first, which removes the app's data. Debug builds never show the update banner for that reason.

The launcher artwork was generated with the built-in image generation tool using this prompt: "A distinctive, simple, modern audio waveform mark suggesting a gap being seamlessly joined or skipped, in bright mint and soft ivory on a deep midnight navy square background; bold, legible at small sizes, with no text." The original is in `artwork/unsplice-icon-source.png`.

## Build a release APK

Push a version tag to build and publish a signed APK on GitHub Releases:

```sh
git tag v0.1.0
git push origin v0.1.0
```

Use `vMAJOR.MINOR.PATCH`, with major at most 99 and minor and patch at most 999. Use increasing version numbers so Android can install an update. The tag supplies both the APK version and its filename; the release also includes a SHA-256 checksum. The workflow runs tests before publishing.

The workflow signs with the repository's `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` secrets. Keep a secure backup of the release keystore and its password: losing them prevents future APKs from updating existing installations. The release signing key differs from the local debug key, so installing a release over an existing debug build requires uninstalling the debug build first, which removes its app data.
