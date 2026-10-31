package com.echomute

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueSnapshotTest {
    @Test fun restoresQueueOrderCurrentEpisodePositionAndMetadata() {
        val saved = """{"items":[{"id":"one","uri":"https://example.com/one.mp3","title":"First","artist":"Show A"},{"id":"two","uri":"file:///episodes/two","title":"Second","artist":"Show B"}],"index":1,"position":42000,"speed":1.5}"""
        val snapshot = decodePlaybackQueue(saved)!!
        assertEquals(listOf("one", "two"), snapshot.items.map { it.id })
        assertEquals("file:///episodes/two", snapshot.items[1].uri)
        assertEquals("Second", snapshot.items[1].title)
        assertEquals("Show B", snapshot.items[1].artist)
        assertEquals(1, snapshot.index)
        assertEquals(42000L, snapshot.position)
        assertEquals(1.5f, snapshot.speed, 0.001f)
        assertEquals(snapshot, decodePlaybackQueue(encodePlaybackQueue(snapshot)))
    }

    @Test fun keepsPublishDatesAndReadsQueuesSavedWithoutThem() {
        val snapshot = PlaybackQueueSnapshot(listOf(QueueItem("one", "https://example.com/one.mp3", "First", "Show", publishedAt = 1_761_600_000_000L), QueueItem("two", "https://example.com/two.mp3", "Second", "Show")), 0, 0, 1f)
        val restored = decodePlaybackQueue(encodePlaybackQueue(snapshot))!!
        assertEquals(1_761_600_000_000L, restored.items[0].publishedAt)
        assertNull(restored.items[1].publishedAt)
        assertEquals(snapshot, restored)
    }

    @Test fun ignoresCorruptOrUnusableSavedQueue() {
        assertNull(decodePlaybackQueue("{broken"))
        assertNull(decodePlaybackQueue("""{"items":[{"id":"one","uri":""}],"index":0}"""))
        assertNull(decodePlaybackQueue("""{"items":[],"index":5}"""))
    }
}
