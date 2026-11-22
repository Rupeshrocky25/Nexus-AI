package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.core.attemptAutoSignIn
import com.example.core.onGoogleSignInClicked
import com.example.ui.components.NexusAuroraBackground
import com.example.ui.components.NexusCard
import com.example.ui.components.NexusStarLogo
import com.example.ui.theme.NexusBorderBright
import com.example.ui.theme.NexusCyan
import com.example.ui.theme.NexusEmeraldLight
import com.example.ui.theme.NexusError
import com.example.ui.theme.NexusSurfaceElevated
import com.example.ui.theme.NexusTextPrimary
import com.example.ui.theme.NexusTextSecondary
import com.example.ui.theme.NexusViolet
import com.example.ui.theme.NexusVioletLight

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var isLoading by remember { mutableStateOf(false) }
    var isCheckingSession by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isCheckingSession = false
                onAuthSuccess()
            },
            onUnauthenticated = {
                isCheckingSession = false
            },
            scope = scope
        )
    }

    NexusAuroraBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NexusStarLogo(size = 44.dp)
                    Text(
                        text = "NEXUS AI",
                        style = MaterialTheme.typography.headlineMedium,
                        color = NexusTextPrimary
                    )
                }

                Text(
                    text = buildAnnotatedString {
                        append("Your knowledge.\n")
                        withStyle(SpanStyle(color = NexusVioletLight)) {
                            append("Your intelligence. ")
                        }
                        withStyle(SpanStyle(color = NexusCyan)) {
                            append("Your control.")
                        }
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = NexusTextPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Privacy-first personal knowledge vault powered by Firebase AI Logic Hybrid Inference — on-device Gemini Nano whenever supported, seamless cloud reasoning when needed.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexusTextSecondary,
                    textAlign = TextAlign.Center
                )

                NexusCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = NexusBorderBright.copy(alpha = 0.65f)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AuthFeatureRow(
                            icon = Icons.Default.Memory,
                            tint = NexusEmeraldLight,
                            title = "Hybrid On-Device + Cloud AI",
                            subtitle = "PREFER_ON_DEVICE for private notes, Gemini 3.8 Flash for deep synthesis."
                        )
                        AuthFeatureRow(
                            icon = Icons.Default.Lock,
                            tint = NexusCyan,
                            title = "Zero-Trust Vault & App Check",
                            subtitle = "User-isolated Firestore rules, offline disk cache, and truthful source telemetry."
                        )
                        AuthFeatureRow(
                            icon = Icons.Default.CloudSync,
                            tint = NexusVioletLight,
                            title = "Seamless Sync & Offline Access",
                            subtitle = "Sign in with Google to unlock your encrypted Knowledge Vault."
                        )
                    }
                }

                if (errorMessage != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = NexusError.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, NexusError.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexusError,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Button(
                    onClick = {
                        errorMessage = null
                        isLoading = true
                        onGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isLoading = false
                                onAuthSuccess()
                            },
                            onAuthError = { msg ->
                                isLoading = false
                                errorMessage = msg
                            },
                            scope = scope,
                            onAuthCancelled = {
                                isLoading = false
                            }
                        )
                    },
                    enabled = !isLoading && !isCheckingSession,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("google_sign_in_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = NexusTextPrimary
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(NexusViolet, Color(0xFF5B3CE8), NexusCyan.copy(alpha = 0.85f))
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading || isCheckingSession) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = NexusTextPrimary,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = if (isCheckingSession) "Restoring session..." else "Signing in...",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NexusTextPrimary
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Google Sign-In",
                                    tint = NexusTextPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Sign in with Google",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NexusTextPrimary
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Powered by Firebase AI Logic • Firebase App Check • Cloud Firestore",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexusTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun AuthFeatureRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = tint.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, tint.copy(alpha = 0.30f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = NexusTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NexusTextSecondary
            )
        }
    }
}
