package com.echomute

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

/** State of an in-app update download, shown in the Library update banner. */
data class UpdateDownload(val active: Boolean = false, val fraction: Float? = null)

private val apkClient = OkHttpClient()

/** APKs live under cacheDir/updates, the folder exposed to the installer by res/xml/file_paths.xml. */
fun updateApkFile(context: Context, release: AppRelease): File =
    File(context.cacheDir, "updates").resolve("unsplice-v${release.version}.apk")

/**
 * Downloads a release APK to [file] in-process.
 *
 * A GitHub release asset URL answers with a 302 to a short-lived signed URL. Handing that URL to a browser
 * with ACTION_VIEW leaves Chrome's download manager restarting the transfer (an endless "Downloading" that
 * never reaches the installer). OkHttp follows the redirect here, streams a bounded response, and verifies it
 * is complete before the APK is offered to Android. Returns the number of bytes written.
 */
fun downloadUpdateApk(
    url: String,
    file: File,
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
): Long {
    require(httpUrl(url)) { "Invalid update URL" }
    val request = Request.Builder().url(url)
        .header("User-Agent", "Unsplice/1.0 (Android)")
        .build()
    apkClient.newCall(request).execute().use { response ->
        require(response.isSuccessful) { "Update download failed: HTTP ${response.code}" }
        val body = requireNotNull(response.body) { "Empty update download" }
        val total = body.contentLength().takeIf { it >= 0 } ?: -1L
        file.parentFile?.mkdirs()
        var downloaded = 0L
        FileOutputStream(file, false).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
            }
        }
        require(downloaded > 0) { "Empty update download" }
        require(total < 0 || downloaded == total) { "Incomplete update download ($downloaded of $total bytes)" }
        return downloaded
    }
}

/** Opens Android's installer on the downloaded APK. A content:// Uri is required on API 26+. */
fun installUpdateApk(context: Context, file: File) {
    require(file.isFile && file.length() > 0) { "Update APK is missing" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
