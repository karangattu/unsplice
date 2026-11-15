package com.echomute

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private val latest = """{"tag_name":"v0.9.0","assets":[
        {"name":"unsplice-v0.9.0.apk.sha256","browser_download_url":"https://github.com/karangattu/unsplice/releases/download/v0.9.0/unsplice-v0.9.0.apk.sha256"},
        {"name":"unsplice-v0.9.0.apk","browser_download_url":"https://github.com/karangattu/unsplice/releases/download/v0.9.0/unsplice-v0.9.0.apk"}]}"""

    @Test fun picksTheApkAssetOfTheLatestRelease() {
        assertEquals(AppRelease("0.9.0", "https://github.com/karangattu/unsplice/releases/download/v0.9.0/unsplice-v0.9.0.apk"), parseLatestRelease(latest))
        assertNull(parseLatestRelease("""{"tag_name":"v0.9.0","assets":[]}"""))
        assertNull(parseLatestRelease("""{"assets":[]}"""))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("0.8.0", "0.9.0"))
        assertTrue(isNewerVersion("0.9.0", "0.10.0"))
        assertTrue(isNewerVersion("0.9", "0.9.1"))
        assertFalse(isNewerVersion("0.9.0", "0.9.0"))
        assertFalse(isNewerVersion("1.0.0", "0.99.999"))
        assertFalse(isNewerVersion("0.8.0", "nightly"))
    }

    @Test fun asksGitHubAtMostOncePerIntervalAndOnlyReportsNewerReleases() = runBlocking {
        val prefs = FakePrefs()
        var calls = 0
        suspend fun fetch(): String { calls++; return latest }
        assertEquals("0.9.0", checkForUpdate(prefs, "0.8.0", now = 1_000L, fetcher = ::fetch)?.version)
        assertEquals("0.9.0", checkForUpdate(prefs, "0.8.0", now = 2_000L, fetcher = ::fetch)?.version)
        assertEquals("Within the interval the cached answer is reused", 1, calls)
        assertNull(checkForUpdate(prefs, "0.9.0", now = 3_000L, fetcher = ::fetch))
        checkForUpdate(prefs, "0.8.0", now = 1_000L + 6 * 60 * 60 * 1000L, fetcher = ::fetch)
        assertEquals(2, calls)
    }

    @Test fun failuresAreSilentAndRetriedNextTime() = runBlocking {
        val prefs = FakePrefs()
        var calls = 0
        suspend fun fetch(): String { calls++; throw java.io.IOException("offline") }
        assertNull(checkForUpdate(prefs, "0.8.0", now = 1_000L, fetcher = ::fetch))
        assertNull(checkForUpdate(prefs, "0.8.0", now = 2_000L, fetcher = ::fetch))
        assertEquals(2, calls)
    }

    @Test fun forcedCheckBypassesTheCacheAndRefreshesIt() = runBlocking {
        val prefs = FakePrefs()
        var calls = 0
        suspend fun fetch(): String { calls++; return latest }
        checkForUpdate(prefs, "0.8.0", now = 1_000L, fetcher = ::fetch)
        assertEquals(1, calls)
        assertEquals(
            UpdateCheckResult.Available(AppRelease("0.9.0", "https://github.com/karangattu/unsplice/releases/download/v0.9.0/unsplice-v0.9.0.apk")),
            checkForUpdateNow(prefs, "0.8.0", now = 2_000L, fetcher = ::fetch)
        )
        assertEquals("A manual check must hit the network even inside the cache interval", 2, calls)
        assertEquals("0.9.0", checkForUpdate(prefs, "0.8.0", now = 3_000L, fetcher = ::fetch)?.version)
        assertEquals("The forced check refreshed the cache", 2, calls)
    }

    @Test fun forcedCheckReportsUpToDateAndFailures() = runBlocking {
        val prefs = FakePrefs()
        suspend fun fetch(): String = latest
        assertEquals(UpdateCheckResult.UpToDate, checkForUpdateNow(prefs, "0.9.0", fetcher = ::fetch))
        assertEquals(UpdateCheckResult.UpToDate, checkForUpdateNow(prefs, "1.0.0", fetcher = ::fetch))
        assertEquals(
            "The latest release has no APK",
            (checkForUpdateNow(prefs, "0.8.0", fetcher = { """{"tag_name":"v0.9.0","assets":[]}""" }) as UpdateCheckResult.Failed).reason
        )
        assertTrue(checkForUpdateNow(prefs, "0.8.0", fetcher = { throw java.io.IOException("offline") }) is UpdateCheckResult.Failed)
    }

    @Test fun aFailedForcedCheckKeepsTheCachedRelease() = runBlocking {
        val prefs = FakePrefs()
        suspend fun fetch(): String = latest
        checkForUpdate(prefs, "0.8.0", now = 1_000L, fetcher = ::fetch)
        assertTrue(checkForUpdateNow(prefs, "0.8.0", now = 2_000L, fetcher = { throw java.io.IOException("offline") }) is UpdateCheckResult.Failed)
        assertEquals("0.9.0", checkForUpdate(prefs, "0.8.0", now = 2_001L, fetcher = ::fetch)?.version)
    }
}
