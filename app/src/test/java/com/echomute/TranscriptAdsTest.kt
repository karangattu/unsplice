package com.echomute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAdsTest {
    /** Builds word timings from a script: each word lasts 300 ms with a 50 ms gap; "|" marks a 700 ms pause. */
    private fun words(script: String, startMs: Long = 0): List<TranscriptWord> {
        var at = startMs
        return script.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapNotNull { token ->
            if (token == "|") { at += 700; null } else TranscriptWord(at, at + 300, token).also { at += 350 }
        }
    }


    private val chatter = "So anyway I was talking to my sister last weekend about the garden and she said the tomatoes had finally come in which was a relief after the frost we had in May honestly I did not think they would make it | "

    @Test fun findsHostReadWithCodeAndUrl() {
        val read = "This episode is brought to you by Acme Mattress. Acme makes the most comfortable bed I have ever slept on. | Go to acme.com/podcast and use code PODCAST for twenty percent off your first order. |"
        val transcript = words("$chatter $read Alright, back to the show. $chatter")
        val found = sponsorReads(transcript)
        assertEquals(1, found.size)
        val read0 = transcript.first { it.text == "This" }
        val readEnd = transcript.first { it.text == "order." }
        assertEquals(read0.startMs, found[0].segment.startMs)
        assertEquals(readEnd.endMs, found[0].segment.endMs)
        assertEquals(TRANSCRIPT_CONFIDENCE, transcriptConfidence(found[0].score))
    }

    @Test fun findsSpokenDomainAndSupportComesFrom() {
        val read = "Support for this podcast comes from Zed Insurance. Zed covers your car your home and your pets in one simple policy with no paperwork and no waiting on hold. | Head over to zed dot com slash listen to get a quote in minutes. Terms and conditions apply. |"
        val found = sponsorReads(words("$chatter $read $chatter"))
        assertEquals(1, found.size)
        assertTrue(found[0].score >= TRANSCRIPT_STRONG_SCORE)
    }

    @Test fun weakerReadGetsLowerConfidence() {
        val read = "Today's sponsor is Brightline and they have been great to work with over the last few months while we got the studio set up. | You can find them at brightline.com and they have a free trial right now so there is no reason not to give it a go. |"
        val found = sponsorReads(words("$chatter $read $chatter"))
        assertEquals(1, found.size)
        assertEquals(TRANSCRIPT_WEAK_CONFIDENCE, transcriptConfidence(found[0].score))
    }

    @Test fun separatesReadsFarApart() {
        val first = "This episode is sponsored by Acme who make the coffee I drink every single morning before we record this thing. | Use code ACME at checkout for ten percent off your whole order. |"
        val second = "And now a word from our sponsor Zed who have been keeping the lights on around here for most of this year. | Visit zed.com/show for a free month on us. |"
        val filler = (1..12).joinToString(" ") { chatter } // well over 90 seconds
        val found = sponsorReads(words("$first $filler $second"))
        assertEquals(2, found.size)
        assertTrue(found[0].segment.endMs < found[1].segment.startMs)
    }

    @Test fun ignoresConversationThatMentionsSponsorsAndWebsites() {
        val talk = "Our sponsor for the conference pulled out at the last minute so we had to scramble. | $chatter $chatter $chatter $chatter $chatter $chatter " +
            "I ended up reading about it on wikipedia.org which was honestly more useful than the panel. | $chatter"
        assertEquals(emptyList<ScoredSegment>(), sponsorReads(words(talk)))
    }

    @Test fun findsReadWhoseCodeComesLongAfterTheAnnouncement() {
        val opening = "Let's talk about one of our sponsors first though, that is sentry.io. Sentry is the saviour for figuring out what is going on with your website. |"
        val closing = "So check it out, sentry.io, use coupon code TASTYTREAT for two months free. |"
        val story = (1..4).joinToString(" ") { chatter } // about 70 s of anecdote between the two
        val found = sponsorReads(words("$chatter $opening $story $closing $chatter"))
        assertEquals(1, found.size)
        assertTrue(found[0].segment.endMs - found[0].segment.startMs > 70_000)
    }

    @Test fun ignoresMarketingTalkWithoutACluster() {
        val talk = "They ran a promo code campaign last year and it flopped. | $chatter The discount was tiny and nobody used it. | $chatter"
        assertEquals(emptyList<ScoredSegment>(), sponsorReads(words(talk)))
    }

    @Test fun ignoresSingleWebsiteMention() {
        assertEquals(emptyList<ScoredSegment>(), sponsorReads(words("$chatter you can sign up at example.com if you want | $chatter")))
    }

    @Test fun handlesEmptyTranscript() {
        assertEquals(emptyList<ScoredSegment>(), sponsorReads(emptyList()))
    }

    @Test fun boundariesFallBackWhenNoPauseIsNear() {
        val read = "this episode is brought to you by acme who make the best coffee you will ever taste and ship it right to your door go to acme.com/pod and use code pod for twenty percent off"
        val transcript = words("$chatter".replace("|", "") + " " + read + " " + chatter.replace("|", ""))
        val found = sponsorReads(transcript)
        assertEquals(1, found.size)
        val first = transcript.first { it.text == "this" }
        val last = transcript.first { it.text == "off" }
        assertEquals(first.startMs - 1000, found[0].segment.startMs)
        assertEquals(last.endMs + 1000, found[0].segment.endMs)
    }

    @Test fun refineEdgesSnapsToLongestPause() {
        val envelope = FloatArray(600) { 1000f }
        for (i in 96..98) envelope[i] = 10f // a 300 ms pause ending at 9.9 s
        envelope[102] = 5f // a single quiet frame between words, nearer but too short
        for (i in 402..406) envelope[i] = 20f // a 500 ms pause starting at 40.2 s
        for (i in 384..386) envelope[i] = 0f // a shorter pause, closer to the edge
        assertEquals(Segment(9_900, 40_200), refineEdges(Segment(10_000, 40_000), envelope))
    }

    @Test fun refineEdgesKeepsEdgeWithoutAPause() {
        val envelope = FloatArray(600) { 1000f + (it % 7) }
        envelope[100] = 1f
        envelope[101] = 1f
        assertEquals(Segment(10_000, 40_000), refineEdges(Segment(10_000, 40_000), envelope))
        assertEquals(Segment(10_000, 40_000), refineEdges(Segment(10_000, 40_000), FloatArray(0)))
    }

    @Test fun confidenceStaysBelowAutoSkipByDefault() {
        assertTrue(TRANSCRIPT_CONFIDENCE < AUTO_SKIP_CONFIDENCE)
        assertTrue(TRANSCRIPT_WEAK_CONFIDENCE < TRANSCRIPT_CONFIDENCE)
    }
}
