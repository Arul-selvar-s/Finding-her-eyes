package app.facefiles.ui

import androidx.compose.runtime.Composable

/** Android system Back / predictive back; no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
