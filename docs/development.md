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
| `transcript` | The episode is transcribed on the device (whisper.cpp, `tiny.en` model) and sponsor reads are recognised from their wording: "brought to you by", "promo code", "go to example.com/show", "twenty percent off" and similar cues clustered within 90 s. Edges snap to the longest pause nearby. Works on any feed, including host-read ads. | 0.45 (strong cluster) or 0.3 |

Sources are checked in that order. While this runs the episode shows "Finding ads…"; an episode with no ads found offers "Scan again", which clears its segments and reruns detection without downloading again. A later segment that overlaps an earlier one is not stored.

The `transcript` source is the slow one, so it runs after the others and after the episode has become READY: it is playable while the transcript is still being made, and its `transcript` segments appear when it finishes. The segments stay below `AUTO_SKIP_CONFIDENCE` until the classifier's precision has been measured on real feeds, so they only get a "Skip ad" button; raise `TRANSCRIPT_CONFIDENCE` in `TranscriptAds.kt` to auto-skip them.

### Transcript source

- **Model**: [whisper.cpp](https://github.com/ggerganov/whisper.cpp) v1.9.5 (MIT), vendored as the git submodule `third_party/whisper.cpp` and linked statically into `libechomute.so`, with the English-only `ggml-tiny.en-q5_1.bin` model (32 MB). The model is not bundled: it is downloaded from Hugging Face into `files/models/` the first time an episode reaches the transcript pass, and the pass is skipped until that succeeds. `WHISPER_MODEL_NAME` in `Transcriber.kt` picks the model; `base.en-q5_1` (57 MB) is noticeably more accurate and about twice as slow. `tiny.en` mishears names (Sentry became "century") but keeps the cue phrases intact, which is all the classifier needs.
- **Audio**: the episode is decoded a second time to 16 kHz mono (`decodeAudio` with `targetRate`), since the 8 kHz PCM used for fingerprinting loses too much of the speech band. Whisper is fed five-minute windows cut at the quietest moment in the window's last ten seconds, so no word straddles a cut; a window costs about 20 MB of float samples. Word timestamps come from whisper's token timestamps (`max_len = 1`, `split_on_word`); they are rough, which is why the edges are refined afterwards.
- **Classifier** (`sponsorReads` in `TranscriptAds.kt`): a list of weighted regular expressions over the normalised words. Strong cues (3 points) are sponsorship announcements, promo and offer codes, and a domain with a path ("acme.com/podcast", "acme dot com slash podcast"); medium cues (2) are bare domains, discounts, free trials, "terms apply" and the like; weak cues (1) are "sign up", "discount", "that's acme dot". Cues within 45 s of each other form a cluster. A cluster counts as a read when it scores at least 6 and contains a strong cue; a score of 9 or more is stored at `TRANSCRIPT_CONFIDENCE`, less at `TRANSCRIPT_WEAK_CONFIDENCE`. The read is widened from its first cue back to the nearest pause between words (at least 350 ms, within 8 s) and from its last cue forward to the next one (within 10 s), then `refineEdges` moves each edge to the longest run of near-silent 100 ms frames within 6 s (quieter than a tenth of the median loudness, at least 300 ms long), which tells the gap between sections from the gap between words. Reads shorter than 10 s or longer than 8 min are dropped. The transcript is kept as `transcript.tsv` next to the audio, so "Scan again" reclassifies without transcribing again.
- **Cost**: the native library adds 4.7 MB to the universal debug APK (23.0 MB to 27.8 MB, about 1.3 MB per ABI after `--gc-sections`), plus the 32 MB model download. Transcribing a 58-minute episode took 96 s with 4 threads on an Apple M2 Pro (36× real time); on the arm64 Android emulator on the same machine the whole pass over a 50 s clip (decode, model load, transcription, loudness envelope) took 5.5 s, about 9× real time. whisper.cpp's published figures for `tiny` on recent phone CPUs are around 5–10× real time, so expect a few minutes per hour of audio on a mid-range phone, and less on devices with the ARM dot-product instructions. The pass uses up to four threads; it runs whether or not the phone is charging.
- **Measured so far**: a synthetic clip (macOS `say`) with a 19 s read is found at 16.1–35.3 s against a true span of 16.1–35.5 s. On real episodes, see `TranscriptAdsTest` for the look-alike cases that must not match and the notes in the next section. Precision on a labelled set of real feeds has not been measured, which is why the confidence stays below the auto-skip threshold.

Playback places an ExoPlayer `PlayerMessage` at the start of each auto-skip segment, so the jump happens at the exact boundary. A message only fires when playback crosses its position, so when an episode starts (including when the queue advances) or resumes, the service also checks whether the current position is already inside a segment and jumps out of it. This is what catches pre-roll ads at 0:00 on the next queued episode. Seeking by hand into the middle of a segment plays it. "Restore last skipped section" removes that one segment; other ads in the episode and later episodes are still skipped.

### What stops this working on every feed

Nothing in RSS says where the ads are, so coverage depends on the host:

- **Dynamic insertion (Omny, Megaphone, Acast, Art19, Spreaker, Libsyn, Podbean, and others)**: ads are stitched into the file per download. Only Omny publishes the break positions and a clean waveform; the other hosts expose nothing, so those feeds fall back to the `repeat` detector, which only catches an ad once it has appeared in two downloaded episodes.
- **Host-read ads baked into the audio**: identical across listeners, but unique per episode, so neither markers nor repeats catch them. Chapters catch them when the publisher labels them; otherwise only the `transcript` source does.
- **The `repeat` detector** ignores the first three minutes, needs at least 15 s of matching audio, and treats any repeated audio as an ad, so recurring segments such as a long outro are flagged and a one-off ad never is.
- **The `transcript` source** only knows English, needs the one-off model download, and takes minutes per episode. It recognises reads by their wording, so an ad with no code, no web address and no "sponsored by" is missed, and a host who sends listeners to "example.com/show, use code SHOW" for their own merch is flagged. Until its precision is measured its segments are not auto-skipped.

To get it closer to covering every feed: build a labelled set of real episodes (word transcripts plus true ad spans), measure precision and recall of `sponsorReads` on it, then raise `TRANSCRIPT_CONFIDENCE` to or above `AUTO_SKIP_CONFIDENCE` once precision is good enough, and consider the `base.en` model if recall is limited by transcription errors.

To build, install Android SDK 35, NDK 27.1.12297006, CMake 3.22.1, and a Java 17 or newer JDK. Set `ANDROID_HOME` to the Android SDK directory, fetch the whisper.cpp submodule, then run the tests and build:

```sh
git submodule update --init
```

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The instrumented test `TranscriptTest` runs the speech model over a synthetic clip on a connected device or emulator (it downloads the model, so the device needs network access):

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.echomute.TranscriptTest
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
