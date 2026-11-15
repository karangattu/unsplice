package com.echomute

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class VersionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun libraryShowsInstalledAppVersion() {
        val context = compose.activity
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        compose.onNodeWithText("v$version").assertExists()
    }

    @Test fun settingsOfferAManualUpdateCheck() {
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithText("Check for updates").assertExists()
    }
}
