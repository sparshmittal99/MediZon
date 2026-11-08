package `in`.medizon.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.core.content.getSystemService

class MediZonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyTheme(AppSettings.theme(this))
        Push.init(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        // Pre-release channel id; replaced by the two below.
        nm.deleteNotificationChannel("medicine_alarms")

        // "Ring phone" ON: the service plays the ringtone and vibrates, so the channel itself is silent.
        val calls = NotificationChannel(
            Notifications.CHANNEL_CALLS, getString(R.string.channel_calls), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_calls_desc)
            setSound(null, null as AudioAttributes?)
            enableVibration(false)
            setBypassDnd(true)
        }
        // "Ring phone" OFF: a normal heads-up notification with the default sound, once.
        val reminders = NotificationChannel(
            Notifications.CHANNEL_REMINDERS, getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_reminders_desc)
            enableVibration(true)
        }
        val missed = NotificationChannel(
            Notifications.CHANNEL_MISSED, getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_missed_desc)
        }
        val announcements = NotificationChannel(
            Notifications.CHANNEL_ANNOUNCEMENTS, getString(R.string.channel_announcements), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_announcements_desc)
        }
        val updates = NotificationChannel(
            Notifications.CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_updates_desc)
        }
        // 30 min after an unanswered dose alarm: heads-up with sound.
        val missedDoses = NotificationChannel(
            Notifications.CHANNEL_MISSED_DOSES, getString(R.string.channel_missed_doses), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_missed_doses_desc)
            enableVibration(true)
        }
        // "Taken by you at 9:05 AM" right after answering an alarm: pops up. (A channel's importance can't be
        // raised after creation, so the earlier default-importance channel is replaced.)
        nm.deleteNotificationChannel("dose_updates")
        val doseUpdates = NotificationChannel(
            Notifications.CHANNEL_DOSE_UPDATES, getString(R.string.channel_dose_updates), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_dose_updates_desc)
        }
        // Unanswered alarms after the ring stops: alerting (shown on the lock screen), but no sound or vibration.
        val due = NotificationChannel(
            Notifications.CHANNEL_DUE, getString(R.string.channel_due), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_due_desc)
            setSound(null, null as AudioAttributes?)
            enableVibration(false)
        }
        nm.createNotificationChannels(listOf(calls, reminders, due, missed, missedDoses, doseUpdates, announcements, updates))
    }
}
