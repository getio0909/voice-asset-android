package com.voiceasset.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voiceasset.android.ui.theme.VoiceAssetTheme

@Composable
fun VoiceAssetApp(uiState: AppUiState = initialAppUiState()) {
    VoiceAssetTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold { contentPadding ->
                VoiceAssetHomeScreen(
                    uiState = uiState,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
private fun VoiceAssetHomeScreen(
    uiState: AppUiState,
    contentPadding: PaddingValues,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.app_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StatusCard(
            label = stringResource(R.string.app_status),
            value =
                when (uiState.initializationStatus) {
                    InitializationStatus.INITIALIZED -> stringResource(R.string.initialized)
                },
            supportingText = stringResource(R.string.initialized_description),
        )

        StatusCard(
            label = stringResource(R.string.server_status),
            value =
                when (uiState.serverStatus) {
                    ServerStatus.NOT_CONFIGURED -> stringResource(R.string.server_not_configured)
                },
            supportingText = stringResource(R.string.server_not_configured_description),
        )
    }
}

@Composable
private fun StatusCard(
    label: String,
    value: String,
    supportingText: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VoiceAssetAppPreview() {
    VoiceAssetApp()
}
