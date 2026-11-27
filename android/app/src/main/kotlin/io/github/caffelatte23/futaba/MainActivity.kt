package io.github.caffelatte23.futaba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import dagger.hilt.android.AndroidEntryPoint
import io.github.caffelatte23.futaba.core.designsystem.FutabaTheme
import io.github.caffelatte23.futaba.navigation.FutabaNavDisplay

/** Set by launchers (Task 14) to focus the composer regardless of the setting. */
const val EXTRA_FOCUS_COMPOSER = "focus_composer"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FutabaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FutabaNavDisplay(focusComposer = intent.getBooleanExtra(EXTRA_FOCUS_COMPOSER, false))
                }
            }
        }
    }
}
