package com.voiceasset.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceAssetAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startupShowsInitializedAndServerNotConfiguredStates() {
        val initialized = composeRule.activity.getString(R.string.initialized)
        val serverNotConfigured = composeRule.activity.getString(R.string.server_not_configured)

        composeRule.onNodeWithText(initialized).assertIsDisplayed()
        composeRule.onNodeWithText(serverNotConfigured).assertIsDisplayed()
    }
}
