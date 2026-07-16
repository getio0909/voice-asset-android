package com.voiceasset.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUiStateTest {
    @Test
    fun initialStateIsReadyAndRequiresServerConfiguration() {
        val state = initialAppUiState()

        assertEquals(InitializationStatus.INITIALIZED, state.initializationStatus)
        assertEquals(ServerStatus.NOT_CONFIGURED, state.serverStatus)
    }
}
