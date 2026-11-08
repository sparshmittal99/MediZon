package `in`.medizon.app

import android.app.Application
import android.app.Notification
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
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        // "Ring phone" OFF: a normal heads-up notification with the default sound, once.
        val reminders = NotificationChannel(
            Notifications.CHANNEL_REMINDERS, getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_reminders_desc)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(
            Notifications.CHANNEL_MISSED, getString(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_missed_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val announcements = NotificationChannel(
            Notifications.CHANNEL_ANNOUNCEMENTS, getString(R.string.channel_announcements), NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = getString(R.string.channel_announcements_desc) }
        val updates = NotificationChannel(
            Notifications.CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_HIGH
        ).apply { description = getString(R.string.channel_updates_desc) }
        // 30 min after an unanswered dose alarm: heads-up with sound.
        val missedDoses = NotificationChannel(
            Notifications.CHANNEL_MISSED_DOSES, getString(R.string.channel_missed_doses), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_missed_doses_desc)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // "Taken by you at 9:05 AM" right after answering an alarm.
        val doseUpdates = NotificationChannel(
            Notifications.CHANNEL_DOSE_UPDATES, getString(R.string.channel_dose_updates), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_dose_updates_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannels(listOf(calls, reminders, missed, missedDoses, doseUpdates, announcements, updates))
    }
}
