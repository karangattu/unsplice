# Unsplice

<img src="artwork/unsplice-icon-source.png" alt="Unsplice logo" width="160">

Unsplice is an Android podcast app that finds and skips advertisements automatically. The app checks several signals for commercial breaks:

1. **Waveform comparison**: Compares loudness waveforms with publisher markers to calculate the duration of an ad.
2. **Chapter data**: Reads chapter data in the audio file and the feed to identify labeled sponsor segments.
3. **Audio fingerprinting**: Uses audio fingerprinting (digital summaries of sound) to find sponsor messages that repeat across episodes.
4. **Transcripts and audio changes**: Looks for sponsor reads in transcripts and for changes in sound or MP3 encoding. A change in sound or encoding on its own is a manual skip suggestion.

The player jumps past ads that qualify for automatic skipping. You can skip other suggestions manually, keep a specific segment, or turn off automatic skipping for a podcast.

<img src="artwork/screenshot.png" alt="Unsplice screenshot" width="300">
