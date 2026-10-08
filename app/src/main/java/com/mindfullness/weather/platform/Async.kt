package com.mindfullness.weather.platform

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors

/** Minimal background/main-thread helper; the app has no coroutine dependency. */
object Async {
    private val executor = Executors.newFixedThreadPool(4)

    /** Separate pool for best-effort background refreshes, so they never delay user actions. */
    private val lowPriority = Executors.newFixedThreadPool(2) { runnable ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply { isDaemon = true }
    }
    val main = Handler(Looper.getMainLooper())

    /** Runs [work] on a background thread and delivers its result on the main thread. */
    fun <T> run(work: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(work)
            main.post { onResult(result) }
        }
    }

    fun <T> runLow(work: () -> T, onResult: (Result<T>) -> Unit) {
        lowPriority.execute {
            val result = runCatching(work)
            main.post { onResult(result) }
        }
    }

    fun background(work: () -> Unit) {
        executor.execute(work)
    }
}
