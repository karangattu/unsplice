package com.echomute

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A published build of the app: its version name and the URL of its signed APK. */
data class AppRelease(val version: String, val apkUrl: String)

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/karangattu/unsplice/releases/latest"
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private val updateClient = OkHttpClient()

/**
 * Looks for a newer release than the installed one. Every release is signed with the same key and carries a
 * higher version code, so Android installs the APK over the current app and keeps its data.
 * Debug builds are signed with the local debug key, which a release cannot update in place, so they are never offered one.
 */
suspend fun checkForUpdate(context: Context): AppRelease? {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return null
    val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return null
    val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    return withContext(Dispatchers.IO) { checkForUpdate(prefs, current) }
}

/** Asks GitHub for the latest release at most once per [minIntervalMs], reusing the last answer in between. */
suspend fun checkForUpdate(
    prefs: SharedPreferences,
    currentVersion: String,
    now: Long = System.currentTimeMillis(),
    minIntervalMs: Long = CHECK_INTERVAL_MS,
    fetcher: suspend () -> String = { fetchLatestRelease() }
): AppRelease? {
    val checkedAt = prefs.getLong("checkedAt", 0)
    val cached = prefs.getString("version", null)?.let { version -> prefs.getString("apkUrl", null)?.let { AppRelease(version, it) } }
    val release = if (cached != null && now - checkedAt in 0 until minIntervalMs) cached else {
        runCatching { parseLatestRelease(fetcher()) }.getOrNull()?.also {
            prefs.edit().putLong("checkedAt", now).putString("version", it.version).putString("apkUrl", it.apkUrl).apply()
        }
    }
    return release?.takeIf { isNewerVersion(currentVersion, it.version) }
}

/** Reads GitHub's release JSON into the version and its APK asset, or null when the release has no APK. */
fun parseLatestRelease(json: String): AppRelease? {
    val release = JSONObject(json)
    val version = release.optString("tag_name").removePrefix("v")
    if (version.isBlank()) return null
    val assets = release.optJSONArray("assets") ?: return null
    for (i in 0 until assets.length()) {
        val asset = assets.optJSONObject(i) ?: continue
        val url = asset.optString("browser_download_url")
        if (asset.optString("name").endsWith(".apk") && url.startsWith("https://")) return AppRelease(version, url)
    }
    return null
}

/** Compares dotted version names numerically, so 0.10.0 is newer than 0.9.0. Names that do not parse are never newer. */
fun isNewerVersion(current: String, candidate: String): Boolean {
    val installed = versionParts(current) ?: return false
    val offered = versionParts(candidate) ?: return false
    for (i in 0 until maxOf(installed.size, offered.size)) {
        val a = installed.getOrElse(i) { 0 }
        val b = offered.getOrElse(i) { 0 }
        if (a != b) return b > a
    }
    return false
}

private fun versionParts(version: String): List<Int>? =
    version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

private fun fetchLatestRelease(): String {
    val request = Request.Builder().url(LATEST_RELEASE_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Unsplice/1.0 (Android)")
        .build()
    return updateClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body?.string().orEmpty()
    }
}
