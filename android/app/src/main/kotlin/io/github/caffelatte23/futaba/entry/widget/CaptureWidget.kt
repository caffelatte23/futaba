package io.github.caffelatte23.futaba.entry.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.Button
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.caffelatte23.futaba.EXTRA_FOCUS_COMPOSER
import io.github.caffelatte23.futaba.MainActivity
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.designsystem.FutabaWidgetColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val CompactSize = DpSize(40.dp, 40.dp)
private val WideSize = DpSize(180.dp, 88.dp)
private val RefreshVersion = longPreferencesKey("refresh_version")
private const val NOTES_TIMEOUT_MS = 2_000L

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CaptureWidgetEntryPoint {
    fun noteRepository(): NoteRepository
}

class CaptureWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(CompactSize, WideSize))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            GlanceTheme(colors = FutabaWidgetColors) {
                if (LocalSize.current == WideSize) {
                    RecentIdeasWidget()
                } else {
                    CompactCaptureWidget()
                }
            }
        }
    }

    internal suspend fun refreshAll(context: Context) {
        // updateAll reuses active Glance sessions. A state change tells them to read a fresh snapshot.
        for (id in GlanceAppWidgetManager(context).getGlanceIds(javaClass)) {
            updateAppWidgetState(context, id) { state ->
                state[RefreshVersion] = (state[RefreshVersion] ?: 0L) + 1
            }
        }
        updateAll(context)
    }
}

private fun captureAction(context: Context) =
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_FOCUS_COMPOSER, true),
    )

@Composable
private fun CompactCaptureWidget(modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.primary)
                .clickable(captureAction(context)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_add),
            contentDescription = context.getString(R.string.widget_new_idea),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
}

@Composable
private fun RecentIdeasWidget(modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val rows by produceState<List<String>?>(initialValue = null, key1 = currentState(RefreshVersion)) {
        val repository = EntryPointAccessors.fromApplication<CaptureWidgetEntryPoint>(context).noteRepository()
        val notes = withTimeoutOrNull(NOTES_TIMEOUT_MS) { repository.notes.first() }.orEmpty()
        value = captureWidgetRows(notes)
    }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.background)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
            val previews = rows
            if (previews?.isEmpty() == true) {
                Text(
                    text = context.getString(R.string.widget_empty),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    maxLines = 1,
                )
            }
            previews.orEmpty().forEach { preview ->
                Row(
                    modifier =
                        GlanceModifier
                            .fillMaxWidth()
                            .defaultWeight()
                            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preview,
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 14.sp),
                        maxLines = 1,
                    )
                }
            }
        }
        Button(
            text = context.getString(R.string.widget_new_idea),
            onClick = captureAction(context),
            modifier = GlanceModifier.fillMaxWidth().height(32.dp),
            maxLines = 1,
        )
    }
}
