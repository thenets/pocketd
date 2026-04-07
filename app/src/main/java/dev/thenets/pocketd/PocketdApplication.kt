package dev.thenets.pocketd

import android.app.Application
import dev.thenets.pocketd.service.NotificationHelper

/**
 * Application entry point.
 * Creates the notification channel at startup so it is always ready
 * before LlmServerService tries to post a foreground notification.
 */
class PocketdApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }
}
