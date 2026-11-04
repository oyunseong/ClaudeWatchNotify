package com.yunseong.claudewatch

import android.app.Application
import com.yunseong.claudewatch.notify.Channels

class ClaudeWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Channels.createAll(this)
    }
}
