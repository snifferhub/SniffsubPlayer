package com.sniffsub.sniff_sub_player

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiMessageEvent(
    val id: Long,
    val text: String,
)

class UiMessageController {
    private val mutableMessage = MutableStateFlow<UiMessageEvent?>(null)
    private var nextId = 0L

    val message: StateFlow<UiMessageEvent?> = mutableMessage.asStateFlow()

    fun show(text: String) {
        nextId += 1L
        mutableMessage.value = UiMessageEvent(nextId, text)
    }

    fun consume(id: Long) {
        if (mutableMessage.value?.id == id) mutableMessage.value = null
    }
}

class SafeUiActionExecutor(
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    fun launch(
        fallbackMessage: String,
        action: suspend () -> Unit,
    ): Job = scope.launch {
        execute(fallbackMessage = fallbackMessage, action = action)
    }

    suspend fun execute(
        fallbackMessage: String,
        publishError: Boolean = true,
        action: suspend () -> Unit,
    ): Result<Unit> = try {
        action()
        Result.success(Unit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        if (publishError) {
            onError(error.message?.takeIf(String::isNotBlank) ?: fallbackMessage)
        }
        Result.failure(error)
    }
}

@Composable
internal fun UiMessageHost(
    message: UiMessageEvent?,
    onConsumed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    LaunchedEffect(message?.id) {
        val event = message ?: return@LaunchedEffect
        snackbarScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = event.text,
                duration = SnackbarDuration.Long,
            )
        }
        onConsumed(event.id)
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = modifier,
    )
}
