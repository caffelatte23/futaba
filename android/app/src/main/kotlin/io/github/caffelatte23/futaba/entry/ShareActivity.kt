package io.github.caffelatte23.futaba.entry

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.caffelatte23.futaba.R
import io.github.caffelatte23.futaba.core.data.AuthRepository
import io.github.caffelatte23.futaba.core.data.DraftStore
import io.github.caffelatte23.futaba.core.data.NoteRepository
import io.github.caffelatte23.futaba.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var auth: AuthRepository

    @Inject lateinit var notes: NoteRepository

    @Inject lateinit var drafts: DraftStore

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text =
            buildShareText(
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
            )
        when {
            savedInstanceState != null || intent.action != Intent.ACTION_SEND ||
                intent.type != "text/plain" || text.isEmpty() -> {
                finish()
            }

            shareGoesToDraft(auth.signedIn.value) -> {
                saveDraft(text)
            }

            else -> {
                notes.add(text)
                showToast(R.string.share_added)
                finish()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any draft save failure needs visible feedback.
    private fun saveDraft(text: String) {
        appScope.launch(Dispatchers.Main.immediate) {
            try {
                drafts.append(text)
                showToast(R.string.share_draft_saved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ShareActivity", "Failed to save shared text to draft", error)
                showToast(R.string.share_draft_failed)
            } finally {
                finish()
            }
        }
    }

    private fun showToast(message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
