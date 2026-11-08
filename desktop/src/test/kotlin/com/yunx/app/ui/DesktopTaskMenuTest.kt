package com.yunx.app.ui

import com.yunx.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTaskMenuTest {
    private fun task(id: Long, status: Int, name: String = "文件$id.zip", done: Long = 25, total: Long = 100) =
        DownloadTaskEntity(
            id = id, url = "https://example.com/$id", fileName = name,
            status = status, downloadedSize = done, totalSize = total
        )

    @Test
    fun emptyAndCompletedQueuesDisableBatchActions() {
        for (tasks in listOf(emptyList(), listOf(task(1, DownloadTaskEntity.STATUS_COMPLETED)))) {
            val menu = DesktopTaskMenuState.from(tasks)
            assertFalse(menu.canPause)
            assertFalse(menu.canResume)
            assertEquals("暂无进行中的任务", menu.summary)
            assertTrue(menu.currentTasks.isEmpty())
        }
    }

    @Test
    fun pendingTasksCanBePausedAndFailedTasksCanBeResumed() {
        val menu = DesktopTaskMenuState.from(listOf(
            task(1, DownloadTaskEntity.STATUS_PENDING),
            task(2, DownloadTaskEntity.STATUS_PAUSED),
            task(3, DownloadTaskEntity.STATUS_FAILED),
            task(4, DownloadTaskEntity.STATUS_COMPLETED)
        ))
        assertTrue(menu.canPause)
        assertTrue(menu.canResume)
        assertEquals("下载中 0 · 等待中 1 · 可继续 2", menu.summary)
        assertEquals(listOf("等待中  文件1.zip"), menu.currentTasks)
    }

    @Test
    fun previewPrioritizesDownloadsAndLimitsLongQueues() {
        val menu = DesktopTaskMenuState.from(
            listOf(task(1, DownloadTaskEntity.STATUS_PENDING)) +
                (2L..7L).map { task(it, DownloadTaskEntity.STATUS_DOWNLOADING) }
        )
        assertEquals(6, menu.downloading)
        assertEquals(1, menu.pending)
        assertEquals((2L..6L).map { "下载中 25%  文件$it.zip" }, menu.currentTasks)
    }

    @Test
    fun previewHandlesUnknownSizeAndClampsProgressWithoutOverflow() {
        val menu = DesktopTaskMenuState.from(listOf(
            task(1, DownloadTaskEntity.STATUS_DOWNLOADING, "文件\n\t名称.zip", total = 0),
            task(2, DownloadTaskEntity.STATUS_DOWNLOADING, "很".repeat(60), done = Long.MAX_VALUE, total = Long.MAX_VALUE),
            task(3, DownloadTaskEntity.STATUS_DOWNLOADING, done = 200),
            task(4, DownloadTaskEntity.STATUS_DOWNLOADING, done = -1)
        ))
        assertEquals("下载中  文件 名称.zip", menu.currentTasks[0])
        assertEquals("下载中 100%  " + "很".repeat(35) + "…", menu.currentTasks[1])
        assertEquals("下载中 100%  文件3.zip", menu.currentTasks[2])
        assertEquals("下载中 0%  文件4.zip", menu.currentTasks[3])
    }

    @Test
    fun commandsWaitForConsumerAndStateTracksNewSnapshots() = runTest {
        val controller = DesktopTaskMenuController()
        controller.send(DesktopTaskMenuAction.SHOW_TASKS)
        assertEquals(DesktopTaskMenuAction.SHOW_TASKS, controller.actions.first())
        controller.update(listOf(task(1, DownloadTaskEntity.STATUS_DOWNLOADING)))
        assertTrue(controller.state.value.canPause)
        controller.update(listOf(task(1, DownloadTaskEntity.STATUS_PAUSED)))
        assertFalse(controller.state.value.canPause)
        assertTrue(controller.state.value.canResume)
        assertEquals("暂无进行中的任务 · 可继续 1", controller.state.value.summary)
    }
}
