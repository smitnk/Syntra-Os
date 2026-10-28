package com.syntra.os.device

import android.app.Notification
import android.service.notification.NotificationListenerService

/** Lets the agent read current notifications (full edition only; declared in src/full manifest). */
class SyntraNotifications : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    companion object {
        @Volatile var instance: SyntraNotifications? = null

        fun read(): String {
            val s = instance ?: error("Notification access is off (Setup → Phone control), or this is the Lite edition.")
            return s.activeNotifications.orEmpty().sortedByDescending { it.postTime }.take(25).joinToString("\n") { sbn ->
                val e = sbn.notification.extras
                val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                "${sbn.packageName}: $title — ${text.take(200)}"
            }.ifBlank { "No notifications." }
        }
    }
}
