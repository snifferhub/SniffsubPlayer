package com.sniffsub.sniff_sub_player

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiActionExecutorTest {
    @Test
    fun io_failure_is_contained_and_published_instead_of_crashing_scope() = runTest {
        val messages = mutableListOf<String>()
        val executor = SafeUiActionExecutor(this) { message -> messages.add(message) }

        val job = executor.launch("保存失败") {
            throw IOException("磁盘写入失败")
        }
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertFalse(job.isCancelled)
        assertEquals(listOf("磁盘写入失败"), messages)
    }

    @Test
    fun cancellation_is_rethrown_and_never_converted_to_an_error_message() = runTest {
        val messages = mutableListOf<String>()
        val executor = SafeUiActionExecutor(this) { message -> messages.add(message) }

        var rethrown = false
        try {
            executor.execute("保存失败") {
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun repeated_global_message_text_gets_distinct_event_ids() {
        val controller = UiMessageController()

        controller.show("保存失败")
        val first = controller.message.value
        controller.show("保存失败")
        val second = controller.message.value

        assertEquals("保存失败", second?.text)
        assertTrue(first != null && second != null && first.id != second.id)
    }
}
