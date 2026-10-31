package com.echomute

import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the classifier on a labelled corpus: word transcripts made on the device's settings
 * (`tiny.en`, `audio_ctx` 768, VAD) with sponsor spans taken from the publishers' own chapters. The bundled corpus
 * is in `src/test/resources/transcripts` (`<name>.tsv.gz` plus `<name>.json`); point `-Punsplice.corpus=<dir>` at
 * a directory of the same layout to measure another set, which prints a report without asserting.
 *
 * A labelled read counts as found when a detected segment covers at least half of it; a detected segment counts
 * as a false positive when it covers less than half of any labelled read. Time-based figures count seconds.
 */
class TranscriptCorpusTest {
    data class Result(val episode: String, val labels: Int, val found: Int, val detected: Int, val falsePositives: Int, val labelledMs: Long, val detectedMs: Long, val overlapMs: Long)

    @Test fun precisionAndRecallOnBundledCorpus() {
        val external = System.getProperty("unsplice.corpus").orEmpty()
        val results = if (external.isNotEmpty()) evaluate(File(external).listFiles().orEmpty().toList()) else evaluateBundled()
        val report = report(results)
        println(report)
        if (external.isNotEmpty()) return
        assertTrue(report, results.isNotEmpty())
        val precision = results.sumOf { it.detected - it.falsePositives }.toDouble() / results.sumOf { it.detected }
        val recall = results.sumOf { it.found }.toDouble() / results.sumOf { it.labels }
        assertTrue("precision $precision\n$report", precision >= MIN_PRECISION)
        assertTrue("recall $recall\n$report", recall >= MIN_RECALL)
    }

    private fun evaluateBundled(): List<Result> {
        val names = javaClass.getResourceAsStream("/transcripts/index.txt")?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        return names.map { name ->
            val words = GZIPInputStream(javaClass.getResourceAsStream("/transcripts/$name.tsv.gz")!!).bufferedReader().readLines().mapNotNull(::parseWord)
            val labels = parseLabels(javaClass.getResourceAsStream("/transcripts/$name.json")!!.bufferedReader().readText())
            evaluate(name, words, labels)
        }
    }

    private fun evaluate(files: List<File>): List<Result> = files.filter { it.name.endsWith(".json") }.sortedBy { it.name }.mapNotNull { json ->
        val name = json.name.removeSuffix(".json")
        val tsv = File(json.parentFile, "$name.tsv").takeIf { it.isFile } ?: return@mapNotNull null
        evaluate(name, tsv.readLines().mapNotNull(::parseWord), parseLabels(json.readText()))
    }

    private fun evaluate(name: String, words: List<TranscriptWord>, labels: List<Segment>): Result {
        val detected = sponsorReads(words).map { it.segment }
        if (System.getProperty("unsplice.corpus.verbose") == "true") {
            fun text(s: Segment) = words.filter { it.startMs >= s.startMs && it.endMs <= s.endMs }.joinToString(" ") { it.text }.take(400)
            labels.forEach { println("$name LABEL ${it.startMs / 1000}-${it.endMs / 1000}s: ${text(it)}") }
            detected.forEach { println("$name FOUND ${it.startMs / 1000}-${it.endMs / 1000}s: ${text(it)}") }
        }
        fun overlap(a: Segment, b: Segment) = (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0)
        val found = labels.count { label -> detected.any { overlap(it, label) * 2 >= label.endMs - label.startMs } }
        val falsePositives = detected.count { d -> labels.none { overlap(d, it) * 2 >= d.endMs - d.startMs } }
        val overlapMs = detected.sumOf { d -> labels.sumOf { overlap(d, it) } }
        return Result(name, labels.size, found, detected.size, falsePositives, labels.sumOf { it.endMs - it.startMs }, detected.sumOf { it.endMs - it.startMs }, overlapMs)
    }

    private fun report(results: List<Result>): String {
        val lines = results.map { "%-14s labels %2d found %2d  detected %2d false %2d  time: labelled %5ds detected %5ds overlap %5ds".format(it.episode, it.labels, it.found, it.detected, it.falsePositives, it.labelledMs / 1000, it.detectedMs / 1000, it.overlapMs / 1000) }
        val labels = results.sumOf { it.labels }; val found = results.sumOf { it.found }; val detected = results.sumOf { it.detected }; val falsePositives = results.sumOf { it.falsePositives }
        val labelledMs = results.sumOf { it.labelledMs }; val detectedMs = results.sumOf { it.detectedMs }; val overlapMs = results.sumOf { it.overlapMs }
        val summary = "segments: precision %.2f (%d of %d) recall %.2f (%d of %d); time: precision %.2f recall %.2f".format(
            if (detected == 0) 0.0 else (detected - falsePositives).toDouble() / detected, detected - falsePositives, detected,
            if (labels == 0) 0.0 else found.toDouble() / labels, found, labels,
            if (detectedMs == 0L) 0.0 else overlapMs.toDouble() / detectedMs, if (labelledMs == 0L) 0.0 else overlapMs.toDouble() / labelledMs)
        return (lines + summary).joinToString("\n")
    }

    private fun parseWord(line: String): TranscriptWord? {
        val parts = line.split('\t', limit = 3)
        return if (parts.size < 3 || parts[2].isBlank()) null else TranscriptWord(parts[0].toLong(), parts[1].toLong(), parts[2])
    }

    private fun parseLabels(json: String): List<Segment> {
        val array = JSONObject(json).getJSONArray("labels")
        return (0 until array.length()).map { array.getJSONObject(it).let { l -> Segment(l.getLong("startMs"), l.getLong("endMs")) } }
    }

    companion object {
        /** Regression floors just under the measured scores (precision 0.33, recall 0.40 on the bundled corpus). */
        const val MIN_PRECISION = 0.3
        const val MIN_RECALL = 0.35
    }
}
