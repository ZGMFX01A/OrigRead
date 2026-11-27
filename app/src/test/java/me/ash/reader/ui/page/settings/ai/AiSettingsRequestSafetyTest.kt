package me.ash.reader.ui.page.settings.ai

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.ash.reader.infrastructure.ai.AiSettings
import me.ash.reader.infrastructure.ai.AiSettingsRepository
import me.ash.reader.infrastructure.ai.AiSummaryService
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiSettingsRequestSafetyTest {
    @Test
    fun `save during provider removal cannot recreate its secret`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val initial = AiSettings()
            val first = requireNotNull(initial.defaultProvider())
            val remaining = first.copy(id = "remaining", name = "Remaining")
            val settings = MutableStateFlow(initial.copy(providers = listOf(first, remaining)))
            val repository = mock<AiSettingsRepository>()
            whenever(repository.current()).thenAnswer { settings.value }
            whenever(repository.settings).thenReturn(settings)
            whenever(repository.getApiKey(any())).thenReturn("stored-key")
            org.mockito.Mockito.doAnswer {
                settings.value = initial.copy(providers = listOf(remaining), defaultProviderId = remaining.id)
                null
            }.`when`(repository).removeProvider(first.id)
            val viewModel = AiSettingsViewModel(repository, mock(), dispatcher)
            runCurrent()
            viewModel.removeSelectedProvider()
            // Deletion has started, while the UI still displays the old Provider.
            viewModel.saveApiKey()
            runCurrent()
            verify(repository).removeProvider(first.id)
            verify(repository, never()).setApiKey(eq(first.id), any())
            assertEquals(remaining.id, viewModel.uiState.value.selectedProviderId)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `reset invalidates queued saves and refuses another save until reset completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val settings = AiSettings()
            val repository = mock<AiSettingsRepository>()
            whenever(repository.current()).thenReturn(settings)
            whenever(repository.settings).thenReturn(MutableStateFlow(settings))
            whenever(repository.getApiKey(any())).thenReturn("stored-key")
            val viewModel = AiSettingsViewModel(repository, mock(), dispatcher)
            runCurrent()
            viewModel.saveApiKey()
            viewModel.restoreDefaults()
            viewModel.saveApiKey()
            runCurrent()
            verify(repository).restoreDefaults()
            verify(repository, never()).setApiKey(any(), any())
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `model discovery waits for persisted credentials instead of using empty initial draft`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val settings = AiSettings()
            val repository = mock<AiSettingsRepository>()
            whenever(repository.current()).thenReturn(settings)
            whenever(repository.settings).thenReturn(MutableStateFlow(settings))
            whenever(repository.getApiKey(any())).thenReturn("stored-key")
            val service = mock<AiSummaryService>()
            org.mockito.Mockito.doAnswer { listOf("model") }.`when`(service).listModels(any(), any())
            val viewModel = AiSettingsViewModel(repository, service, dispatcher)
            viewModel.loadModels()
            verify(service, never()).listModels(any(), any())
            runCurrent()
            verify(service).listModels(requireNotNull(settings.defaultProvider()).id, "stored-key")
            assertNull(viewModel.uiState.value.loadingModelsProviderId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late connection response cannot replace newer result`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val settings = AiSettings()
            val provider = requireNotNull(settings.defaultProvider())
            val repository = mock<AiSettingsRepository>()
            whenever(repository.current()).thenReturn(settings)
            whenever(repository.settings).thenReturn(MutableStateFlow(settings))
            whenever(repository.getApiKey(any())).thenReturn("")
            val service = mock<AiSummaryService>()
            lateinit var oldResponse: Continuation<Any?>
            var requests = 0
            // Kotlin Result is unboxed for immediate return, but boxed when resumed via Continuation.
            org.mockito.Mockito.doAnswer { invocation ->
                if (++requests == 1) {
                    oldResponse = invocation.rawArguments.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                } else "new"
            }.`when`(service).testProvider(any(), any())
            val viewModel = AiSettingsViewModel(repository, service, dispatcher)
            runCurrent()
            viewModel.testProvider("success", "failure")
            runCurrent()
            assertEquals(provider.id, viewModel.uiState.value.testingProviderId)
            viewModel.testProvider("success", "failure")
            runCurrent()
            assertEquals("success：new", viewModel.uiState.value.testResult?.message)
            oldResponse.resume(Result.success("old"))
            runCurrent()
            assertEquals("success：new", viewModel.uiState.value.testResult?.message)
            assertNull(viewModel.uiState.value.testingProviderId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `changed credentials invalidate pending model discovery before persistence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val settings = AiSettings()
            val repository = mock<AiSettingsRepository>()
            whenever(repository.current()).thenReturn(settings)
            whenever(repository.settings).thenReturn(MutableStateFlow(settings))
            whenever(repository.getApiKey(any())).thenReturn("stored-key")
            val service = mock<AiSummaryService>()
            lateinit var oldResponse: Continuation<Any?>
            org.mockito.Mockito.doAnswer { invocation ->
                oldResponse = invocation.rawArguments.last() as Continuation<Any?>
                COROUTINE_SUSPENDED
            }.`when`(service).listModels(any(), any())
            val viewModel = AiSettingsViewModel(repository, service, dispatcher)
            runCurrent()
            viewModel.loadModels()
            runCurrent()
            viewModel.updateApiKeyDraft("new-key")
            oldResponse.resume(Result.success(listOf("stale-model")))
            runCurrent()
            verify(repository, never()).setProviderModels(any(), any())
            assertEquals("new-key", viewModel.uiState.value.apiKeyDraft)
            assertNull(viewModel.uiState.value.loadingModelsProviderId)
            assertNull(viewModel.uiState.value.modelLoadError)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
