package com.echomute

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class QueueItem(val id: String, val uri: String, val title: String, val artist: String)
data class PlaybackQueueSnapshot(val items: List<QueueItem>, val index: Int, val position: Long, val speed: Float)

fun decodePlaybackQueue(saved: String): PlaybackQueueSnapshot? = try {
    val json = JSONObject(saved)
    val array = json.getJSONArray("items")
    val items = (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        val id = item.getString("id")
        val uri = item.getString("uri")
        require(id.isNotBlank() && URI(uri).scheme?.lowercase() in setOf("https", "http", "file", "content"))
        QueueItem(id, uri, item.optString("title", ""), item.optString("artist", ""))
    }
    val index = json.getInt("index")
    require(index in items.indices)
    val speed = json.optDouble("speed", 1.0).toFloat()
    require(speed.isFinite() && speed in 0.1f..8f)
    PlaybackQueueSnapshot(items, index, json.optLong("position", 0).coerceAtLeast(0), speed)
} catch (_: Exception) { null }

fun encodePlaybackQueue(snapshot: PlaybackQueueSnapshot): String {
    val items = JSONArray()
    snapshot.items.forEach { item ->
        items.put(JSONObject().put("id", item.id).put("uri", item.uri).put("title", item.title).put("artist", item.artist))
    }
    return JSONObject().put("items", items).put("index", snapshot.index)
        .put("position", snapshot.position).put("speed", snapshot.speed.toDouble()).toString()
}
