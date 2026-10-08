package com.mindfullness.weather.platform

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Minimal background/main-thread helper; the app has no coroutine dependency. */
object Async {
    private val executor = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())

    /** Runs [work] on a background thread and delivers its result on the main thread. */
    fun <T> run(work: () -> T, onResult: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(work)
            main.post { onResult(result) }
        }
    }

    fun background(work: () -> Unit) {
        executor.execute(work)
    }
}
