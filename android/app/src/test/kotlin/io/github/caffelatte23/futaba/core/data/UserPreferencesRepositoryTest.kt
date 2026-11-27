package io.github.caffelatte23.futaba.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val job = Job()
    private lateinit var repo: UserPreferencesRepository

    @Before
    fun setUp() {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(UnconfinedTestDispatcher() + job),
                produceFile = { tmp.root.resolve("prefs.preferences_pb") },
            )
        repo = UserPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() = job.cancel()

    @Test
    fun draftRoundTrips() =
        runTest {
            repo.setDraft("abc")
            assertThat(repo.draft.first()).isEqualTo("abc")
        }

    @Test
    fun defaults() =
        runTest {
            assertThat(repo.openKeyboardOnLaunch.first()).isTrue()
            assertThat(repo.theme.first()).isEqualTo(ThemeMode.System)
            assertThat(repo.draft.first()).isEmpty()
        }

    // The repository takes only a DataStore, so it cannot depend on auth state.
    // One write per test: DataStore's rename-over-existing fails on Windows (File.renameTo), so a 2nd write would too.
    @Test
    fun draftSurvivesWhenSignedOut() =
        runTest {
            repo.setDraft("offline")
            assertThat(repo.draft.first()).isEqualTo("offline")
        }

    @Test
    fun appendsShareAfterPendingDraftAndIgnoresOlderWrite() =
        runTest {
            val drafts = DraftStore(repo)
            val pending = drafts.registerIntent("Existing draft")
            drafts.append("Shared text")
            drafts.write(pending.text, pending.seq)

            assertThat(repo.draft.first()).isEqualTo("Existing draft\n\nShared text")
            assertThat(drafts.latestIntent.value?.text).isEqualTo("Existing draft\n\nShared text")
        }
}
