package com.echomute

import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

private val downloadClient = OkHttpClient()

fun downloadAudio(url: String, file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
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
        val startBytes = if (append) size else 0L
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength >= 0) startBytes + contentLength else -1L
        file.parentFile?.mkdirs()
        FileOutputStream(file, append).use { output ->
            if (!append) {
                val newEtag = response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                if (newEtag == null) validator.delete() else validator.writeText(newEtag)
            }
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = startBytes
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, totalBytes)
                }
            }
        }
    }
}
