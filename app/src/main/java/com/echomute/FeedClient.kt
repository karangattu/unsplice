package com.echomute

import okhttp3.OkHttpClient
import okhttp3.Request

private val feedClient = OkHttpClient()

fun fetchFeed(url: String): Feed {
    require(httpUrl(url)) { "Enter a valid feed URL" }
    feedClient.newCall(Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)").build()).execute().use { response ->
        check(response.isSuccessful) { "Feed request failed: HTTP ${response.code}" }
        val source = requireNotNull(response.body).source()
        require(!source.request(5 * 1024 * 1024L + 1)) { "Feed is too large" }
        return parseFeed(source.readByteArray().inputStream())
    }
}
