package com.yunx.app.ui

import com.yunx.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

enum class DesktopTaskMenuAction {
    SHOW_TASKS, PAUSE_ALL, RESUME_ALL, OPEN_DOWNLOAD_DIRECTORY
}

data class DesktopTaskMenuState(
    val downloading: Int = 0,
    val pending: Int = 0,
    val resumable: Int = 0,
    val currentTasks: List<String> = emptyList()
) {
    val canPause: Boolean get() = downloading + pending > 0
    val canResume: Boolean get() = resumable > 0
    val summary: String get() = if (canPause) {
        "下载中 $downloading · 等待中 $pending · 可继续 $resumable"
    } else if (canResume) {
        "暂无进行中的任务 · 可继续 $resumable"
    } else {
        "暂无进行中的任务"
    }

    companion object {
        /** 菜单只预览前五个任务，完整列表由「查看下载列表」打开。 */
        fun from(tasks: List<DownloadTaskEntity>): DesktopTaskMenuState {
            val downloading = tasks.filter { it.status == DownloadTaskEntity.STATUS_DOWNLOADING }
            val pending = tasks.filter { it.status == DownloadTaskEntity.STATUS_PENDING }
            return DesktopTaskMenuState(
                downloading = downloading.size,
                pending = pending.size,
                resumable = tasks.count {
                    it.status == DownloadTaskEntity.STATUS_PAUSED || it.status == DownloadTaskEntity.STATUS_FAILED
                },
                currentTasks = (downloading.asSequence() + pending.asSequence()).take(5).map { task ->
                    val name = task.fileName.replace(Regex("\\s+"), " ").trim().let {
                        if (it.length > 36) it.take(35) + "…" else it
                    }
                    val status = if (task.status == DownloadTaskEntity.STATUS_PENDING) "等待中" else {
                        if (task.totalSize > 0) {
                            val percent = (task.downloadedSize.toDouble() / task.totalSize * 100).toInt().coerceIn(0, 100)
                            "下载中 $percent%"
                        } else "下载中"
                    }
                    "$status  $name"
                }.toList()
            )
        }
    }
}

/** 主窗口隐藏时依然接收菜单操作；每次打开托盘菜单时读取最新快照。 */
class DesktopTaskMenuController {
    private val mutableState = MutableStateFlow(DesktopTaskMenuState())
    val state = mutableState.asStateFlow()
    private val actionChannel = Channel<DesktopTaskMenuAction>(Channel.BUFFERED)
    val actions = actionChannel.receiveAsFlow()

    fun update(tasks: List<DownloadTaskEntity>) {
        mutableState.value = DesktopTaskMenuState.from(tasks)
    }

    fun send(action: DesktopTaskMenuAction) {
        actionChannel.trySend(action)
    }
}
