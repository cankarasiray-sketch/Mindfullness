package com.mindfullness.weather

import android.app.Application
import com.mindfullness.weather.platform.CrashReport
import com.mindfullness.weather.platform.JobScheduling
import com.mindfullness.weather.platform.Notifier
import com.mindfullness.weather.platform.SettingsStore
import com.mindfullness.weather.ui.AppController

class HavaUyariApp : Application() {
    /** Process-wide state holder, so screens survive activity re-creation. */
    val controller: AppController by lazy { AppController(this) }

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        Notifier.createChannels(this)
        JobScheduling.apply(this, SettingsStore(this).settings)
    }
}
