package io.github.caffelatte23.futaba

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.caffelatte23.futaba.core.data.AuthRepository
import io.github.caffelatte23.futaba.core.data.ComposerFocusRequests
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.ThemeMode
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.designsystem.FutabaTheme
import io.github.caffelatte23.futaba.di.ApplicationScope
import io.github.caffelatte23.futaba.entry.widget.CaptureWidget
import io.github.caffelatte23.futaba.feature.login.LoginScreen
import io.github.caffelatte23.futaba.navigation.FutabaNavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Set by launchers (Task 14) to focus the composer regardless of the setting. */
const val EXTRA_FOCUS_COMPOSER = "focus_composer"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: UserPreferencesRepository

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject lateinit var focusRequests: ComposerFocusRequests

    @Inject lateinit var notes: NoteRepository

    @Inject lateinit var auth: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // One request per launch (not on rotation): forced by a launcher, else per the setting.
        if (savedInstanceState == null) {
            val forced = intent.getBooleanExtra(EXTRA_FOCUS_COMPOSER, false)
            lifecycleScope.launch { if (forced || preferences.openKeyboardOnLaunch.first()) focusRequests.request() }
        }
        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle(ThemeMode.System)
            val signedIn by auth.signedIn.collectAsStateWithLifecycle()
            val darkTheme =
                when (theme) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            FutabaTheme(darkTheme = darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    when (startScreen(signedIn)) {
                        StartScreen.Stream -> FutabaNavDisplay()
                        StartScreen.Login -> LoginScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FOCUS_COMPOSER, false)) focusRequests.request()
    }

    override fun onStart() {
        super.onStart()
        appScope.launch { notes.purgeTrash(Instant.now()) }
    }

    override fun onStop() {
        super.onStop()
        appScope.launch { CaptureWidget().refreshAll(applicationContext) }
    }
}

internal enum class StartScreen { Login, Stream }

internal fun startScreen(signedIn: Boolean): StartScreen = if (signedIn) StartScreen.Stream else StartScreen.Login
