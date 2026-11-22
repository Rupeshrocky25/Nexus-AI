package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.NetworkMonitor
import com.example.core.authStateFlow
import com.example.core.configureAppCheck
import com.example.data.ai.HybridAiService
import com.example.data.repository.VaultRepository
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.MainShell
import com.example.ui.theme.NexusAiTheme
import com.example.ui.viewmodel.NexusViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent BEFORE UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        // STEP 2: Enable full edge-to-edge dark obsidian presentation
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            NexusAiTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = { /* authStateFlow updates automatically */ })
    } else {
        val uid = user.uid
        val displayName = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore("@")
            ?: "Nexus User"
        val email = user.email
        val photoUrl = user.photoUrl?.toString()

        key(uid) {
            val viewModel: NexusViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY]) {
                            "APPLICATION_KEY missing from CreationExtras"
                        }
                        val databaseId = app.getString(R.string.firestore_database_id)
                        val db = VaultRepository.createConfiguredFirestore(databaseId)
                        val aiService = HybridAiService()
                        val capability = aiService.probeDeviceCapability(app)
                        NexusViewModel(
                            repository = VaultRepository(db),
                            aiService = aiService,
                            networkMonitor = NetworkMonitor(app),
                            currentUserId = uid,
                            userDisplayName = displayName,
                            userEmail = email,
                            userPhotoUrl = photoUrl,
                            deviceCapability = capability,
                        )
                    }
                }
            )
            MainShell(viewModel = viewModel)
        }
    }
}
