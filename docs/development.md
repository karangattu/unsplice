# Unsplice

Unsplice is an Android podcast player with podcast search, RSS subscriptions, downloads, background playback, and automatic skipping of detected ad segments. Detection is experimental: it can miss ads or identify other repeated audio as an ad. You can turn auto-skip off for a podcast or keep an individual detected segment.

Use Discover to find a podcast or enter a feed URL. In Library, open a podcast to play, download, or add episodes to Up Next. Episodes show their publish date and how much is left to listen; the list is newest first and can be flipped to oldest first. Feeds are refreshed on launch (at most every 15 minutes) and with the refresh button on a podcast. The queue keeps the current episode at the top; use the arrows to reorder upcoming episodes or the remove button to take one out. The queue and playback position are saved between sessions, and each episode resumes where it left off.

Playing or queueing an episode downloads it and finds its ads in the background, so by the time the queue reaches it the ads are known. Until then it streams. Once the download exists the queue item switches to it, because a stream can carry different ads from the file that was analysed. An episode that plays to the end is marked as played.

The matcher excludes the first three minutes and matches lasting three minutes or more. The fingerprint generator reads a whole PCM file into memory; its speed, accuracy, and memory use on real episodes have not been measured.

## Ad detection

Each detected segment records its `source` and a `confidence` from 0 to 1. Segments at or above `AUTO_SKIP_CONFIDENCE` (0.5) are skipped automatically when the podcast has auto-skip on. Lower-confidence segments only show a "Skip ad" button while playing.

| Source | How it is found | Confidence |
| --- | --- | --- |
| `marker` | For Omny Studio episodes (including iHeart shows): the clip API lists where ad breaks go in the ad-free episode and publishes a loudness waveform of it. Sliding that waveform along the downloaded audio after each marker gives each break's length. Breaks that line up poorly (correlation below 0.4) are not auto-skipped. | 0.9 |
| `chapter` | ID3 `CHAP` frames in the audio file whose title names an ad or sponsor | 0.9 |
| `chapter` | Podcasting 2.0 `<podcast:chapters>` JSON from the feed, used only when the file has no ID3 chapters. These may not account for dynamically inserted ads, so they are trusted less. | 0.7 |
| `repeat` | Audio fingerprints that repeat within an episode or across any downloaded episode in the library, since networks insert the same ads into every show | 0.6 |

Sources are checked in that order. While this runs the episode shows "Finding ads…"; an episode with no ads found offers "Scan again", which clears its segments and reruns detection without downloading again. A later segment that overlaps an earlier one is not stored.

Playback places an ExoPlayer `PlayerMessage` at the start of each auto-skip segment, so the jump happens at the exact boundary. A message only fires when playback crosses its position, so when an episode starts (including when the queue advances) or resumes, the service also checks whether the current position is already inside a segment and jumps out of it. This is what catches pre-roll ads at 0:00 on the next queued episode. Seeking by hand into the middle of a segment plays it. "Restore last skipped section" removes that one segment; other ads in the episode and later episodes are still skipped.

### What stops this working on every feed

Nothing in RSS says where the ads are, so coverage depends on the host:

- **Dynamic insertion (Omny, Megaphone, Acast, Art19, Spreaker, Libsyn, Podbean, and others)**: ads are stitched into the file per download. Only Omny publishes the break positions and a clean waveform; the other hosts expose nothing, so those feeds fall back to the `repeat` detector, which only catches an ad once it has appeared in two downloaded episodes.
- **Host-read ads baked into the audio**: identical across listeners, but unique per episode, so neither markers nor repeats catch them. Only chapters (when the publisher labels them) do, and most feeds have none.
- **The `repeat` detector** ignores the first three minutes, needs at least 15 s of matching audio, and treats any repeated audio as an ad, so recurring segments such as a long outro are flagged and a one-off ad never is.

The route to covering every feed is content-based detection: transcribing the audio on the device and classifying sponsor reads from their wording ("brought to you by", "promo code", "go to example.com/podcast"), as a fourth source with its own confidence. That is a separate project (an on-device speech model, boundary refinement from silence gaps, and a labelled test set).

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

The debug APK is a signed, universal APK for Android 8.0 (API 26) or newer.

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
