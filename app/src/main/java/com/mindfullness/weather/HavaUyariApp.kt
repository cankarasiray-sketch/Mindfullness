package com.mindfullness.weather

import android.app.Application
import com.mindfullness.weather.platform.Notifier
import com.mindfullness.weather.platform.SettingsStore
import com.mindfullness.weather.platform.WorkScheduler

class HavaUyariApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        WorkScheduler.apply(this, SettingsStore(this).settings.value)
    }
}
