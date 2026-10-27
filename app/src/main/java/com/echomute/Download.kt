package com.echomute

import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

private val downloadClient = OkHttpClient()

fun downloadAudio(url: String, file: File) {
    require(httpUrl(url)) { "Invalid audio URL" }
    val validator = File(file.path + ".etag")
    val etag = validator.takeIf { it.isFile }?.readText()?.takeIf { it.startsWith('"') && it.endsWith('"') }
    val size = if (etag != null && file.exists()) file.length() else 0L
    val builder = Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)")
    if (size > 0) builder.header("Range", "bytes=$size-").header("If-Range", etag!!)
    downloadClient.newCall(builder.build()).execute().use { response ->
        require(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
        val append = response.code == 206 && size > 0
        if (response.code == 206) {
            require(append && response.header("Content-Range")?.startsWith("bytes $size-") == true) { "Invalid download range" }
            require(response.header("ETag") == null || response.header("ETag") == etag) { "Download changed" }
        }
        val body = requireNotNull(response.body) { "Empty response" }
        file.parentFile?.mkdirs()
        FileOutputStream(file, append).use { output ->
            if (!append) {
                val newEtag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                if (newEtag == null) validator.delete() else validator.writeText(newEtag)
            }
            body.byteStream().use { it.copyTo(output) }
        }
    }
}
