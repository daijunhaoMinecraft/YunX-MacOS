package com.yunx.app.util

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载期间阻止系统睡眠：
 * - macOS：持有 `caffeinate -dimsu` 子进程，引用计数归零时结束；
 * - Windows：SendThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)。
 *
 * 对象名保留为 WindowsKeepAwake 以兼容现有调用与测试；行为已跨平台。
 */
object WindowsKeepAwake {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int

        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        }
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val isMac = System.getProperty("os.name").orEmpty().lowercase()
        .let { it.contains("mac") || it.contains("darwin") }

    /** 所有 Windows SetThreadExecutionState 调用固定在此线程（状态线程本地，跨线程会失调） */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yunx-keep-awake") }
    private val refs = AtomicInteger(0)

    @Volatile
    private var caffeinate: Process? = null

    fun acquire() {
        if (refs.getAndIncrement() != 0) return
        when {
            isMac -> {
                synchronized(this) {
                    caffeinate = runCatching {
                        ProcessBuilder("/usr/bin/caffeinate", "-dimsu")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                    }.onFailure {
                        Log.w("YunX-KeepAwake", "启动 caffeinate 失败：${it.message}")
                    }.getOrNull()
                }
            }
            isWindows -> executor.execute {
                runCatching { Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED) }
            }
        }
    }

    fun release() {
        if (refs.decrementAndGet() > 0) return
        refs.set(0)
        when {
            isMac -> synchronized(this) {
                caffeinate?.let { process ->
                    runCatching { process.destroy() }
                    if (!runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) {
                        runCatching { process.destroyForcibly() }
                    }
                }
                caffeinate = null
            }
            isWindows -> executor.execute {
                runCatching { Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS) }
            }
        }
    }
}
