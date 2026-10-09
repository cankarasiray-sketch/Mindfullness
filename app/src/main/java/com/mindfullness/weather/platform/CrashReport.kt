package com.mindfullness.weather.platform

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on disk, so the next start can show it and let the user send it on. On a
 * phone we cannot debug, this is the only way to learn why the app stopped.
 */
object CrashReport {
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_TRACE_CHARS = 8_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(describe(app, thread, error)) }
            // Let the system handle the crash as usual (closing the app).
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? {
        val file = file(context)
        return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun describe(context: Context, thread: Thread, error: Throwable): String {
        @Suppress("DEPRECATION")
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCode})"
        }.getOrDefault("?")
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            append("Hava Uyarı hata raporu\n")
            append("Zaman: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append('\n')
            append("Sürüm: ").append(version).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("İş parçacığı: ").append(thread.name).append("\n\n")
            append(trace.take(MAX_TRACE_CHARS))
        }
    }
}
