package io.github.caffelatte23.futaba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.caffelatte23.futaba.core.data.ComposerFocusRequests
import io.github.caffelatte23.futaba.core.data.ThemeMode
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.designsystem.FutabaTheme
import io.github.caffelatte23.futaba.di.ApplicationScope
import io.github.caffelatte23.futaba.entry.widget.CaptureWidget
import io.github.caffelatte23.futaba.navigation.FutabaNavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Set by launchers (Task 14) to focus the composer regardless of the setting. */
const val EXTRA_FOCUS_COMPOSER = "focus_composer"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preferences: UserPreferencesRepository

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope
    @Inject lateinit var focusRequests: ComposerFocusRequests

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not on recreation (rotation): the request was already served.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_FOCUS_COMPOSER, false)) focusRequests.request()
        setContent {
            val theme by preferences.theme.collectAsStateWithLifecycle(ThemeMode.System)
            val darkTheme =
                when (theme) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            FutabaTheme(darkTheme = darkTheme) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FutabaNavDisplay()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        appScope.launch { CaptureWidget().refreshAll(applicationContext) }
    }
}
