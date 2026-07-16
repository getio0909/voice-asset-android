package com.voiceasset.android

data class AppUiState(
    val initializationStatus: InitializationStatus,
    val serverStatus: ServerStatus,
)

enum class InitializationStatus {
    INITIALIZED,
}

enum class ServerStatus {
    NOT_CONFIGURED,
}

fun initialAppUiState(): AppUiState =
    AppUiState(
        initializationStatus = InitializationStatus.INITIALIZED,
        serverStatus = ServerStatus.NOT_CONFIGURED,
    )
