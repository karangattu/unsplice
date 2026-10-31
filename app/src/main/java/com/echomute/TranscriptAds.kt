package com.echomute

/** A transcribed word with its position in the episode. */
data class TranscriptWord(val startMs: Long, val endMs: Long, val text: String)

/** A candidate ad segment and the score its wording earned. */
data class ScoredSegment(val segment: Segment, val score: Int)

/**
 * Confidence stored for `transcript` segments. It is below [AUTO_SKIP_CONFIDENCE], so the player only offers a
 * "Skip ad" button, until the classifier's precision has been measured on real feeds. Raise it to auto-skip.
 */
const val TRANSCRIPT_CONFIDENCE = 0.45f

/** Clusters scoring at least this much are stored at [TRANSCRIPT_CONFIDENCE]; lower-scoring ones at [TRANSCRIPT_WEAK_CONFIDENCE]. */
const val TRANSCRIPT_STRONG_SCORE = 9
const val TRANSCRIPT_WEAK_CONFIDENCE = 0.3f

/** Minimum cluster score to count as a sponsor read; the cluster must also contain a strong cue. */
private const val MIN_SCORE = 6
private const val STRONG = 3
private const val MEDIUM = 2
private const val WEAK = 1

/** Cues at most this far apart belong to the same read: the announcement and the code at the end can be 80 s apart. */
private const val CLUSTER_GAP_MS = 90_000L
private const val MIN_LENGTH_MS = 10_000L
private const val MAX_LENGTH_MS = 8 * 60_000L
/** A silence between words at least this long is treated as a sentence boundary. */
private const val PAUSE_MS = 350L
private const val LOOK_BEFORE_MS = 8_000L
private const val LOOK_AFTER_MS = 10_000L
private const val MERGE_GAP_MS = 5_000L

private val domain = """[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|co|net|org|io|fm|tv|app|ai|shop|me|us|uk|ca)"""
private val spokenDomain = """[a-z0-9-]+ dot (?:com|co|net|org|io|fm|tv|app|ai|shop|me)"""

private data class Cue(val pattern: Regex, val weight: Int)

private fun cue(weight: Int, pattern: String) = Cue(Regex("""\b(?:$pattern)\b"""), weight)

private val cues = listOf(
    // Sponsorship announcements
    cue(STRONG, "brought to you by"),
    cue(STRONG, "sponsored by"),
    cue(STRONG, "(?:this|today's|the) (?:episode|show|podcast) is (?:sponsored|brought|supported|presented)"),
    cue(STRONG, "support for (?:this|the|today's) (?:show|podcast|episode|program) comes from"),
    cue(STRONG, "(?:our|today's|this week's|this episode's) (?:sponsors?|partners?)"),
    cue(STRONG, "thanks? to (?:our|today's) (?:sponsors?|partners?)"),
    cue(STRONG, "(?:a |another )?(?:word|message) from our (?:sponsors?|partners?)"),
    cue(STRONG, "paid (?:partnership|promotion|advertisement)"),
    cue(STRONG, "(?:in partnership|partnered) with"),
    // Calls to action
    cue(STRONG, "(?:promo|offer|coupon|discount|referral) code"),
    cue(STRONG, "use (?:the |my |our )?code"),
    cue(STRONG, "code \\w+ at checkout"),
    cue(STRONG, "(?:$domain|$spokenDomain) ?(?:/|slash) ?[a-z0-9-]+"),
    cue(MEDIUM, "at checkout"),
    cue(MEDIUM, "(?:go|head|head over|get over) (?:on over )?to (?:$domain|$spokenDomain|[a-z0-9-]+ dot)"),
    cue(MEDIUM, "visit (?:$domain|$spokenDomain)"),
    cue(MEDIUM, "$domain|$spokenDomain"),
    cue(MEDIUM, "(?:\\d+ ?%|\\d+ percent|\\w+ percent) off"),
    cue(MEDIUM, "(?:\\d+|\\w+) (?:dollars|bucks) off"),
    cue(MEDIUM, "free (?:trial|shipping|month|sample|gift|returns)"),
    cue(MEDIUM, "(?:\\d+|\\w+) (?:months?|weeks?|days?|years?) free"),
    cue(MEDIUM, "free for (?:\\d+|\\w+) (?:months?|weeks?|days?)"),
    cue(MEDIUM, "(?:first|your first) (?:month|order|purchase|box|visit)"),
    cue(MEDIUM, "terms (?:and conditions )?apply"),
    cue(MEDIUM, "money[- ]back guarantee"),
    cue(MEDIUM, "risk[- ]free"),
    cue(MEDIUM, "limited time"),
    cue(MEDIUM, "new customers"),
    cue(MEDIUM, "listeners (?:of this (?:show|podcast) )?(?:get|can get|receive|save)"),
    cue(MEDIUM, "(?:exclusive|special) (?:offer|deal|discount)"),
    cue(MEDIUM, "(?:link|links) in the (?:show notes|description|episode notes)"),
    cue(WEAK, "sign up|signing up"),
    cue(WEAK, "discount"),
    cue(WEAK, "download the app"),
    cue(WEAK, "cancel (?:any ?time|whenever)"),
    cue(WEAK, "(?:start|get started) today"),
    cue(WEAK, "that's [a-z0-9-]+(?:\\.| dot )"),
)

private data class Hit(val firstWord: Int, val lastWord: Int, val weight: Int)

/**
 * Finds sponsor reads from how they are worded: a cluster of cues (sponsor announcements, promo codes, "go to
 * example.com/show", discounts) close together in time. Boundaries are widened to the nearest pause before the first
 * cue and after the last one, since the cues sit inside the read rather than at its edges.
 */
fun sponsorReads(words: List<TranscriptWord>): List<ScoredSegment> {
    if (words.isEmpty()) return emptyList()
    val tokens = words.map { normalise(it.text) }
    val offsets = IntArray(tokens.size)
    val text = StringBuilder()
    for ((i, token) in tokens.withIndex()) {
        offsets[i] = text.length
        text.append(token).append(' ')
    }
    val joined = text.toString()
    val hits = cues.flatMap { cue ->
        cue.pattern.findAll(joined).map { match -> Hit(wordAt(offsets, match.range.first), wordAt(offsets, match.range.last), cue.weight) }
    }.sortedBy { it.firstWord }

    val clusters = mutableListOf<MutableList<Hit>>()
    for (hit in hits) {
        val current = clusters.lastOrNull()
        if (current != null && words[hit.firstWord].startMs - words[current.last().lastWord].endMs <= CLUSTER_GAP_MS) current.add(hit)
        else clusters.add(mutableListOf(hit))
    }

    val found = clusters.mapNotNull { cluster ->
        val score = cluster.sumOf { it.weight }
        if (score < MIN_SCORE || cluster.none { it.weight == STRONG }) return@mapNotNull null
        val first = cluster.minOf { it.firstWord }
        val last = cluster.maxOf { it.lastWord }
        val start = pauseBefore(words, first) ?: (words[first].startMs - 1000).coerceAtLeast(0)
        val end = pauseAfter(words, last) ?: (words[last].endMs + 1000)
        val length = end - start
        if (length < MIN_LENGTH_MS || length > MAX_LENGTH_MS) null else ScoredSegment(Segment(start, end), score)
    }
    return merge(found)
}

/** Confidence to store for a cluster with [score]. */
fun transcriptConfidence(score: Int) = if (score >= TRANSCRIPT_STRONG_SCORE) TRANSCRIPT_CONFIDENCE else TRANSCRIPT_WEAK_CONFIDENCE

/**
 * Moves each edge of [segment] to the longest pause within [windowMs] of it. A pause is a run of at least
 * [minPauseFrames] frames quieter than a tenth of the median loudness around the edge, which separates the gap
 * between sections from the gap between words. The start edge moves to where speech resumes after the pause and
 * the end edge to where speech stops before it, so only the read itself is skipped. [envelope] holds one RMS
 * value per [frameMs] of audio.
 */
fun refineEdges(segment: Segment, envelope: FloatArray, frameMs: Int = 100, windowMs: Long = 6_000, minPauseFrames: Int = 3): Segment {
    if (envelope.isEmpty()) return segment
    fun snap(edgeMs: Long, isStart: Boolean): Long {
        val centre = (edgeMs / frameMs).toInt()
        val radius = (windowMs / frameMs).toInt()
        val from = (centre - radius).coerceAtLeast(0)
        val to = (centre + radius).coerceAtMost(envelope.size - 1)
        if (from > to) return edgeMs
        val sorted = (from..to).map { envelope[it] }.sorted()
        val threshold = sorted[sorted.size / 2] * 0.1f
        var bestFrom = -1
        var bestLength = 0
        var bestDistance = Int.MAX_VALUE
        var i = from
        while (i <= to) {
            if (envelope[i] >= threshold) { i++; continue }
            val runFrom = i
            while (i <= to && envelope[i] < threshold) i++
            val length = i - runFrom
            val distance = minOf(Math.abs(runFrom - centre), Math.abs(i - 1 - centre))
            if (length > bestLength || (length == bestLength && distance < bestDistance)) {
                bestFrom = runFrom; bestLength = length; bestDistance = distance
            }
        }
        if (bestLength < minPauseFrames) return edgeMs
        return (if (isStart) bestFrom + bestLength else bestFrom).toLong() * frameMs
    }
    val start = snap(segment.startMs, true)
    val end = snap(segment.endMs, false)
    return if (end > start) Segment(start, end) else segment
}

private fun normalise(word: String): String {
    val lower = word.trim().lowercase()
    val kept = lower.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '/' || it == '\'' || it == '%' }
    return kept.trim('.', '-', '/', '\'')
}

private fun wordAt(offsets: IntArray, charIndex: Int): Int {
    var low = 0
    var high = offsets.size - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (offsets[mid] <= charIndex) low = mid else high = mid - 1
    }
    return low
}

private fun pauseBefore(words: List<TranscriptWord>, index: Int): Long? {
    val limit = words[index].startMs - LOOK_BEFORE_MS
    var i = index
    while (i > 0 && words[i].startMs >= limit) {
        if (words[i].startMs - words[i - 1].endMs >= PAUSE_MS) return words[i].startMs
        i--
    }
    return if (i == 0 && words[0].startMs >= limit) 0 else null
}

private fun pauseAfter(words: List<TranscriptWord>, index: Int): Long? {
    val limit = words[index].endMs + LOOK_AFTER_MS
    var i = index
    while (i < words.size - 1 && words[i].endMs <= limit) {
        if (words[i + 1].startMs - words[i].endMs >= PAUSE_MS) return words[i].endMs
        i++
    }
    return if (i == words.size - 1 && words[i].endMs <= limit) words[i].endMs else null
}

private fun merge(segments: List<ScoredSegment>): List<ScoredSegment> {
    val result = mutableListOf<ScoredSegment>()
    for (s in segments.sortedBy { it.segment.startMs }) {
        val last = result.lastOrNull()
        if (last != null && s.segment.startMs - last.segment.endMs <= MERGE_GAP_MS) {
            result[result.size - 1] = ScoredSegment(Segment(last.segment.startMs, maxOf(last.segment.endMs, s.segment.endMs)), last.score + s.score)
        } else result.add(s)
    }
    return result
}
