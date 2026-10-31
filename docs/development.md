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

- **Model**: [whisper.cpp](https://github.com/ggerganov/whisper.cpp) v1.9.5 (MIT), vendored as the git submodule `third_party/whisper.cpp`, with the English-only `ggml-tiny.en-q8_0.bin` model (44 MB). It is not bundled: it is downloaded from Hugging Face into `files/models/` the first time an episode reaches the transcript pass, which is skipped until the download succeeds. `WHISPER_MODEL_NAME` in `Transcriber.kt` picks the model; `base.en` is noticeably more accurate and about twice as slow. `tiny.en` mishears names (Sentry became "century") but keeps the cue phrases intact, which is all the classifier needs.
- **CPU variants**: ggml is built as shared libraries with its CPU backend loaded at runtime (`GGML_BACKEND_DL`). On arm64 the APK carries four backend builds (`armv8.0`, `armv8.2` with dotprod, with dotprod and fp16, and `armv8.6` with i8mm) and ggml picks the best one the phone's CPU supports, so phones from about 2018 on get the dot-product kernels, which are 2 to 3× faster on quantised weights, while older ones still run. `NativeWhisper.backendName()` reports which one loaded. This needs the native libraries extracted at install time (`useLegacyPackaging`), since ggml scans the library directory.
- **Audio**: the episode is decoded once to 16 kHz mono; the fingerprint passes read an 8 kHz copy made by averaging sample pairs (`halveSampleRate`), so nothing is decoded twice. Whisper reads five-minute windows straight from the PCM file in native code (no sample copy through the JVM), each cut at the quietest moment in the window's last ten seconds so no word straddles a cut. It runs greedily with no temperature fallback (`temperature_inc = 0`), so a hard section cannot trigger repeated decoding. Word timestamps come from whisper's token timestamps (`max_len = 1`, `split_on_word`); they are rough, which is why the edges are refined afterwards.
- **Settings that were tried and rejected** (host, Apple M2 Pro, 4 threads, a 34-minute talk episode): reducing the encoder context (`audio_ctx` 768, `WHISPER_AUDIO_CTX`) halves encoder work but sends `tiny.en` into repetition loops on some audio (a 148-word clip came back as 293 words, and on the emulator took 16 minutes instead of 6 s), so it stays at the full 1500. Silero voice activity detection (`WHISPER_USE_VAD`, model 0.9 MB) made the same episode 17% slower (61 s against 53 s) because a talk show has nothing to skip; the code path is kept, off by default, for feeds with long music beds. `tiny.en-q8_0` (44 MB) transcribes 12% faster than `tiny.en-q5_1` on CPUs with dot-product instructions (53 s against 60 s) and is the default.
- **Cancellation**: cancelling the processing coroutine sets whisper's abort flag through `NativeWhisper.cancel`, so a deleted episode or stopped worker frees the CPU within a few hundred milliseconds rather than at the end of the window.
- **Classifier** (`sponsorReads` in `TranscriptAds.kt`): a list of weighted regular expressions over the normalised words. Strong cues (3 points) are sponsorship announcements ("brought to you by", "sponsored today by", "our friends at acme.com", "I'm here with the CTO of"), promo and offer codes, and a domain with a path ("acme.com/podcast", "acme dot com slash podcast"); medium cues (2) are bare domains, discounts, free trials, "terms apply" and the like; weak cues (1) are "sign up", "discount", "that's acme dot". Cues within 90 s of each other form a cluster (the announcement and the code at the end of a read can be that far apart), and a cluster holding an opening cue is joined to a following one holding only closing cues up to 240 s later, for interview-style reads whose cues sit at the two ends. A cluster counts as a read when it scores at least 6 and contains a strong cue; a score of 9 or more is stored at `TRANSCRIPT_CONFIDENCE`, less at `TRANSCRIPT_WEAK_CONFIDENCE`. The read is widened from its first cue back to the nearest pause between words (at least 350 ms, within 8 s) and from its last cue forward to the next one (within 10 s), then `refineEdges` moves each edge to the longest run of near-silent 100 ms frames within 6 s (quieter than a tenth of the median loudness, at least 300 ms long), which tells the gap between sections from the gap between words. Reads shorter than 10 s or longer than 8 min are dropped. The transcript is kept as `transcript.tsv` next to the audio, so "Scan again" reclassifies without transcribing again.
- **Labelled corpus**: `app/src/test/resources/transcripts/` holds word transcripts of 16 episodes, 20 hours of audio (10 from Syntax, 2023, and 6 from The Changelog, 2025), made with the shipped settings, with sponsor spans taken from the publishers' own chapter timestamps. Three Syntax episodes whose show-note timestamps were unusable were dropped, leaving 13. `TranscriptCorpusTest` measures the classifier on them and fails if segment precision drops below 0.3 or recall below 0.35, floors set just under the current scores so that a change to the cue list cannot silently make things worse. A labelled read counts as found when a detected segment covers at least half of it, and a detection counts as false when less than half of it lies inside a labelled read. **The current scores are low: segment precision 0.33 (8 of 24) and recall 0.40 (10 of 25); by time, precision 0.43 and recall 0.30.** Reading the misses explains why. The Changelog's reads are three-minute interviews with the sponsor's CTO ("Well friends, I'm here with the CTO of Buildkite... learn more at buildkite.com") with no code and only a domain at the end; a cue-based classifier has little to hold on to, and the open/close bracketing added for them (`BRACKET_GAP_MS`) finds a few but also over-extends some Syntax reads into the chat that follows. Most of the "false positives" are sponsor-like wording the publishers do not chapter as ads: the hosts' own course plugs with a coupon code, the "thank you to our partners at fly.io" pre-roll, and a guest who is talking about their own product's website. On the Syntax reads that do carry a code or URL, 6 of 7 are found. This is the measurement that was missing before; it says the classifier is usable as a "Skip ad" hint and nowhere near auto-skip quality, and that the next gain is not more cue phrases but a model that scores a whole passage (for example a small text classifier over 30 s windows), evaluated on this same corpus. Run it on another directory of `<name>.tsv` and `<name>.json` files with `./gradlew :app:testDebugUnitTest --tests '*TranscriptCorpusTest*' -Punsplice.corpus=<dir>`; that prints the report without asserting. The transcripts were produced with whisper-cli on a Mac (`-tpi 0 -ml 1 -sow`), which uses the same model and settings as the device.
- **Cost**: the native libraries add about 3.2 MB to the universal debug APK (26.2 MB, versus 23.0 MB before the transcript source; the arm64 slice holds 5.1 MB of libraries, 2.5 MB compressed), plus the 44 MB model download. Transcribing the 34-minute Syntax episode took 53 s with 4 threads on an Apple M2 Pro (38× real time). On the arm64 emulator on the same machine (one virtual core), the whole pass over a 50 s clip (decode, model load, transcription, loudness envelope) took 5.6 s, of which transcription was 4 s, with ggml picking the `armv8.2` dotprod and fp16 backend. whisper.cpp's published figures for `tiny` on recent phone CPUs with dotprod are around 10× real time, so expect a few minutes per hour of audio on a mid-range phone. The pass uses up to four threads; it runs whether or not the phone is charging.

### What stops this working on every feed

Nothing in RSS says where the ads are, so coverage depends on the host:

- **Dynamic insertion (Omny, Megaphone, Acast, Art19, Spreaker, Libsyn, Podbean, and others)**: ads are stitched into the file per download. Only Omny publishes the break positions and a clean waveform; the other hosts expose nothing, so those feeds fall back to the `repeat` detector, which only catches an ad once it has appeared in two downloaded episodes.
- **Host-read ads baked into the audio**: identical across listeners, but unique per episode, so neither markers nor repeats catch them. Chapters catch them when the publisher labels them; otherwise only the `transcript` source does.
- **The `repeat` detector** ignores the first three minutes, needs at least 15 s of matching audio, and treats any repeated audio as an ad, so recurring segments such as a long outro are flagged and a one-off ad never is.
- **The `transcript` source** only knows English, needs the one-off model download, and takes minutes per episode. It recognises reads by their wording, so an ad with no code, no web address and no "sponsored by" is missed, and a host who sends listeners to "example.com/show, use code SHOW" for their own merch is flagged. On the labelled corpus it finds 4 in 10 reads and a third of what it flags is a labelled read, so its segments are not auto-skipped.

To get it closer to covering every feed: improve `sponsorReads` against the bundled corpus (the misses are interview-style reads with no code; a passage-level text classifier is the likely next step), raise `TRANSCRIPT_CONFIDENCE` to or above `AUTO_SKIP_CONFIDENCE` once precision is good enough, and consider the `base.en` model if recall is limited by transcription errors.

To build, install Android SDK 35, NDK 27.1.12297006, CMake 3.22.1, and a Java 17 or newer JDK. Set `ANDROID_HOME` to the Android SDK directory, fetch the whisper.cpp submodule, then run the tests and build:

```sh
git submodule update --init
```

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The instrumented test `TranscriptTest` runs the speech model over a synthetic clip on a connected device or emulator, checks that a dotprod CPU backend was picked on arm64, and checks that cancellation stops the model promptly (it downloads the models, so the device needs network access):

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
