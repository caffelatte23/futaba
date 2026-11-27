package io.github.caffelatte23.futaba.feature.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.data.AuthRepository
import io.github.caffelatte23.futaba.core.data.LinkResult
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.core.data.ThemeMode
import io.github.caffelatte23.futaba.core.data.UserPreferencesRepository
import io.github.caffelatte23.futaba.core.domain.toMarkdown
import io.github.caffelatte23.futaba.core.model.Label
import io.github.caffelatte23.futaba.core.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

data class SettingsOperation(
    val busy: Boolean = false,
    val linkResult: LinkResult? = null,
    @param:StringRes val message: Int? = null,
)

data class SettingsNoteCounts(
    val active: Int,
    val trashed: Int,
)

private data class ExportSnapshot(
    val uid: String,
    val notes: List<Note>,
    val labels: List<Label>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val auth: AuthRepository,
        private val preferences: UserPreferencesRepository,
        notes: NoteRepository,
        @param:ApplicationContext private val application: Context,
    ) : ViewModel() {
        val account = auth.account
        val pendingWrites = notes.pendingWrites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)
        val theme = preferences.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), ThemeMode.System)
        val openKeyboardOnLaunch =
            preferences.openKeyboardOnLaunch.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)
        private val documents =
            auth.uid
                .flatMapLatest { uid ->
                    flow {
                        emit(null)
                        if (uid != null) {
                            emitAll(
                                combine(
                                    notes.notes,
                                    notes.labels,
                                ) { items, labels -> ExportSnapshot(uid, items, labels) },
                            )
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
        private val _operation = MutableStateFlow(SettingsOperation())
        val operation: StateFlow<SettingsOperation> = _operation
        val noteCounts =
            documents
                .map { snapshot ->
                    snapshot?.notes?.let { notes ->
                        val trashed = notes.count { it.deletedAt != null }
                        SettingsNoteCounts(active = notes.size - trashed, trashed = trashed)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

        fun linkGoogle(activity: Context) {
            if (_operation.value.busy) return
            _operation.value = SettingsOperation(busy = true)
            viewModelScope.launch {
                _operation.value = SettingsOperation(linkResult = auth.linkGoogle(activity))
            }
        }

        fun signOut() {
            if (_operation.value.busy) return
            _operation.value = SettingsOperation(busy = true)
            viewModelScope.launch {
                val signedIn = auth.signOut()
                _operation.value =
                    SettingsOperation(message = R.string.settings_anonymous_failed.takeUnless { signedIn })
            }
        }

        fun dismissSync() {
            _operation.update { it.copy(linkResult = null) }
        }

        fun clearMessage() {
            _operation.update { it.copy(message = null) }
        }

        fun setTheme(value: ThemeMode) {
            viewModelScope.launch { preferences.setTheme(value) }
        }

        fun setOpenKeyboardOnLaunch(value: Boolean) {
            viewModelScope.launch { preferences.setOpenKeyboardOnLaunch(value) }
        }

        fun exportFilename(): String = "futaba-${LocalDate.now()}.md"

        @Suppress("InjectDispatcher") // SAF streams and formatting run off the main thread.
        fun export(uri: Uri) {
            val snapshot = documents.value
            if (snapshot == null || snapshot.uid != auth.uid.value) {
                _operation.update { it.copy(message = R.string.settings_export_failed) }
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val markdown = toMarkdown(snapshot.notes, snapshot.labels, ZoneId.systemDefault())
                    val stream =
                        application.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No export stream")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(markdown) }
                    _operation.update { it.copy(message = R.string.settings_export_saved) }
                } catch (e: IOException) {
                    Log.w(TAG, "Couldn't write Markdown", e)
                    _operation.update { it.copy(message = R.string.settings_export_failed) }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Couldn't access export destination", e)
                    _operation.update { it.copy(message = R.string.settings_export_failed) }
                }
            }
        }
    }
