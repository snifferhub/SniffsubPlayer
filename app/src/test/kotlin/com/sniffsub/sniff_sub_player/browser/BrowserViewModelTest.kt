package com.sniffsub.sniff_sub_player.browser

import com.sniffsub.sniff_sub_player.data.AppPreferences
import com.sniffsub.sniff_sub_player.data.AppPreferencesRepository
import com.sniffsub.sniff_sub_player.data.Bookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun navigation_resets_sniffer_and_previous_page_candidates() {
        val state = BrowserUiState(
            snifferEnabled = true,
            candidates = listOf(candidate("https://cdn.test/old.mp4")),
        )

        val navigated = reduceBrowserEvent(
            state,
            BrowserEvent.PageStarted("https://next.test"),
        )

        assertFalse(navigated.snifferEnabled)
        assertTrue(navigated.candidates.isEmpty())
        assertEquals("https://next.test", navigated.currentPageUrl)
        assertEquals("https://next.test", navigated.address)
        assertTrue(navigated.loading)
    }

    @Test
    fun loading_progress_is_clamped_and_navigation_flags_are_reduced() {
        val progressed = reduceBrowserEvent(
            BrowserUiState(),
            BrowserEvent.LoadingChanged(loading = true, progress = 4.0),
        )
        val navigable = reduceBrowserEvent(
            progressed,
            BrowserEvent.NavigationChanged(canGoBack = true, canGoForward = false),
        )

        assertEquals(1.0, navigable.progress, 0.0)
        assertTrue(navigable.canGoBack)
        assertFalse(navigable.canGoForward)
    }

    @Test
    fun navigation_updates_accumulator_page_before_accepting_payload() = runTest {
        val preferences = FakePreferencesRepository()
        val accumulator = CandidateAccumulator(clock = { 99L })
        val viewModel = BrowserViewModel(preferences, accumulator, clock = { 7L })
        viewModel.updatePage("https://page.test/watch", started = true)
        viewModel.enableSniffer()

        assertTrue(
            viewModel.acceptSnifferPayload(
                """{"url":"https://cdn.test/master.m3u8","source":"fetch"}""",
            ),
        )

        val captured = viewModel.uiState.value.candidates.single()
        assertEquals("https://page.test/watch", accumulator.currentPageUrl)
        assertEquals("https://page.test/watch", captured.pageUrl)
        assertEquals("https://page.test/watch", captured.referer)
        assertEquals(99L, captured.detectedAtEpochMs)
    }

    @Test
    fun payloads_are_ignored_until_sniffer_is_enabled_and_clear_is_explicit() = runTest {
        val viewModel = BrowserViewModel(FakePreferencesRepository(), CandidateAccumulator())
        val json = """{"url":"https://cdn.test/video.mp4","source":"video"}"""

        assertFalse(viewModel.acceptSnifferPayload(json))
        viewModel.enableSniffer()
        assertTrue(viewModel.acceptSnifferPayload(json))
        assertEquals(1, viewModel.uiState.value.candidates.size)

        viewModel.clearCandidates()
        assertTrue(viewModel.uiState.value.candidates.isEmpty())
    }

    @Test
    fun repeated_user_requests_open_sheet_and_increment_scan_revision() = runTest {
        val viewModel = BrowserViewModel(FakePreferencesRepository())
        viewModel.requestSniff()
        assertFalse(viewModel.uiState.value.candidateSheetVisible)
        viewModel.updatePage("https://page.test/watch", started = true)
        viewModel.requestSniff()
        assertFalse(viewModel.uiState.value.snifferEnabled)
        viewModel.setLoading(false)
        viewModel.requestSniff()
        val revision = viewModel.uiState.value.sniffRequestRevision
        assertTrue(viewModel.uiState.value.candidateSheetVisible)
        assertTrue(viewModel.uiState.value.snifferEnabled)
        viewModel.dismissCandidateSheet()
        assertFalse(viewModel.uiState.value.candidateSheetVisible)
        viewModel.requestSniff()
        assertTrue(viewModel.uiState.value.candidateSheetVisible)
        assertEquals(revision + 1, viewModel.uiState.value.sniffRequestRevision)
    }

    @Test
    fun page_change_clears_accumulator_and_rejects_delayed_previous_page_payloads() = runTest {
        val viewModel = BrowserViewModel(FakePreferencesRepository())
        viewModel.updatePage("https://old.test", started = true)
        viewModel.enableSniffer()
        val oldJson = """{"url":"https://cdn.test/old.mp4","source":"video","pageUrl":"https://old.test"}"""
        assertTrue(viewModel.acceptSnifferPayload(oldJson))
        viewModel.updatePage("https://new.test", started = true)
        assertTrue(viewModel.uiState.value.candidates.isEmpty())
        assertFalse(viewModel.uiState.value.candidateSheetVisible)
        viewModel.enableSniffer()
        assertFalse(viewModel.acceptSnifferPayload(oldJson))
        val newJson = """{"url":"https://cdn.test/new.mp4","source":"video","pageUrl":"https://new.test"}"""
        assertTrue(viewModel.acceptSnifferPayload(newJson))
        assertEquals("https://cdn.test/new.mp4", viewModel.uiState.value.candidates.single().url)
    }

    @Test
    fun submit_address_normalizes_input_and_blank_input_is_ignored() = runTest {
        val viewModel = BrowserViewModel(FakePreferencesRepository(), CandidateAccumulator())

        assertEquals("https://example.com", viewModel.submitAddress(" example.com "))
        assertEquals("https://example.com", viewModel.uiState.value.address)
        assertNull(viewModel.submitAddress("   "))
        assertEquals("https://example.com", viewModel.uiState.value.address)
    }

    @Test
    fun preferences_feed_ui_and_toggle_bookmark_is_persisted_atomically() = runTest {
        val existing = Bookmark("saved", "Saved", "https://saved.test", 1L)
        val preferences = FakePreferencesRepository(
            AppPreferences(userAgent = "Saved UA", bookmarks = listOf(existing)),
        )
        val viewModel = BrowserViewModel(preferences, CandidateAccumulator(), clock = { 7L })
        advanceUntilIdle()

        assertEquals("Saved UA", viewModel.uiState.value.userAgent)
        assertEquals(listOf(existing), viewModel.uiState.value.bookmarks)

        viewModel.updatePage("https://page.test", pageTitle = "Page")
        viewModel.toggleBookmark()
        advanceUntilIdle()

        assertEquals("https://page.test", preferences.current.bookmarks.first().url)
        assertEquals("Page", preferences.current.bookmarks.first().title)
        assertEquals(7L, preferences.current.bookmarks.first().createdAtEpochMs)
        assertEquals("已收藏", viewModel.uiState.value.transientMessage)
    }

    @Test
    fun request_interception_only_consults_rules_for_http_and_https() {
        val consulted = mutableListOf<String>()
        val blocker: (String) -> Boolean = { url ->
            consulted += url
            true
        }

        assertTrue(shouldBlockWebRequest("http://ads.test/a.js", blocker))
        assertTrue(shouldBlockWebRequest("https://ads.test/a.js", blocker))
        assertFalse(shouldBlockWebRequest("blob:https://page.test/id", blocker))
        assertFalse(shouldBlockWebRequest("data:text/plain,hello", blocker))
        assertFalse(shouldBlockWebRequest("content://media/external/video/1", blocker))
        assertEquals(
            listOf("http://ads.test/a.js", "https://ads.test/a.js"),
            consulted,
        )
    }

    @Test
    fun renderer_exit_requests_a_fresh_webview_and_keeps_page_address() {
        val state = BrowserUiState(
            address = "https://page.test",
            currentPageUrl = "https://page.test",
            loading = true,
            webViewGeneration = 2,
        )

        val recovered = reduceBrowserEvent(state, BrowserEvent.RendererGone)

        assertEquals(3, recovered.webViewGeneration)
        assertEquals("https://page.test", recovered.address)
        assertFalse(recovered.loading)
        assertEquals("网页渲染进程已退出，请重新加载", recovered.transientMessage)
    }

    @Test
    fun repeated_transient_message_text_receives_a_new_event_revision() {
        val first = reduceBrowserEvent(
            BrowserUiState(),
            BrowserEvent.MessageChanged("更新失败"),
        )
        val second = reduceBrowserEvent(
            first,
            BrowserEvent.MessageChanged("更新失败"),
        )

        assertEquals("更新失败", second.transientMessage)
        assertEquals(1L, first.transientMessageRevision)
        assertEquals(2L, second.transientMessageRevision)
    }

    @Test
    fun cancelled_bookmark_write_is_not_converted_to_an_error_message() = runTest {
        val preferences = FailingPreferencesRepository(CancellationException("cancelled"))
        val viewModel = BrowserViewModel(preferences)
        viewModel.updatePage("https://page.test", pageTitle = "Page")

        viewModel.toggleBookmark()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.transientMessage)
    }

    private fun candidate(url: String) = MediaCandidate(
        url = url,
        kind = MediaKind.MP4,
        source = "test",
        pageUrl = "https://page.test",
        referer = "https://page.test",
        userAgent = "UA",
        detectedAtEpochMs = 1L,
    )
}

private class FakePreferencesRepository(
    initial: AppPreferences = AppPreferences(),
) : AppPreferencesRepository {
    private val mutableValues = MutableStateFlow(initial)

    override val values: Flow<AppPreferences> = mutableValues

    val current: AppPreferences
        get() = mutableValues.value

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        mutableValues.value = transform(mutableValues.value)
    }
}

private class FailingPreferencesRepository(
    private val failure: Throwable,
) : AppPreferencesRepository {
    override val values: Flow<AppPreferences> = MutableStateFlow(AppPreferences())

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        throw failure
    }
}
