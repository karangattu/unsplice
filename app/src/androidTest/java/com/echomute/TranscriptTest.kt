package com.echomute

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the speech model over a 50 s synthetic clip whose sponsor read spans roughly 16 s to 35 s. Needs the model, which is downloaded on first use. */
@RunWith(AndroidJUnit4::class)
class TranscriptTest {
    @Test fun findsSponsorReadInSynthesisedEpisode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "transcript-test").apply { deleteRecursively(); mkdirs() }
        val audio = File(directory, "audio")
        InstrumentationRegistry.getInstrumentation().context.assets.open("sponsor-read.mp3").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val model = try { whisperModel(context) } catch (e: Exception) { assumeTrue("Model download failed: ${e.message}", false); return }
        assertTrue(model.length() > 30_000_000)

        val started = System.currentTimeMillis()
        val found = transcriptSegments(context, audio, directory) { true }
        val words = readTranscript(File(directory, "transcript.tsv"))
        Log.i("TranscriptTest", "Transcribed ${words.size} words of a 50 s clip in ${System.currentTimeMillis() - started} ms: " + words.joinToString(" ") { it.text })
        val text = words.joinToString(" ") { it.text.lowercase() }
        assertTrue(text, text.contains("brought to you by"))
        assertTrue(text, text.contains("code"))

        assertEquals(found.toString(), 1, found.size)
        val (segment, confidence) = found[0]
        assertTrue(segment.toString(), segment.startMs in 13_000..18_000)
        assertTrue(segment.toString(), segment.endMs in 33_000..38_000)
        assertTrue(confidence < AUTO_SKIP_CONFIDENCE)
    }
}
