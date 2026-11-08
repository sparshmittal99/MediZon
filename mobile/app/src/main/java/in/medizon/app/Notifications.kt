package `in`.medizon.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat

object Notifications {
    const val CHANNEL_CALLS = "medicine_calls"
    const val CHANNEL_REMINDERS = "medicine_reminders"
    const val CHANNEL_MISSED = "missed_alarms"
    const val CHANNEL_ANNOUNCEMENTS = "announcements"
    const val CHANNEL_UPDATES = "updates"
    const val CHANNEL_MISSED_DOSES = "missed_doses"
    /** Pop-up (heads-up) channel; replaces the earlier low-key "dose_updates". */
    const val CHANNEL_DOSE_UPDATES = "dose_confirmations"
    /** Unanswered alarms after the ring stops: alerting (so it shows on the lock screen) but without sound. */
    const val CHANNEL_DUE = "due_alarms"
    const val RINGING_ID = 1001

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun reminderNotificationId(item: AlarmItem) = ("rem-" + item.id).hashCode()

    private const val EXTRA_PLAN_ID = "in.medizon.app.planId"
    private const val EXTRA_DOSE_AT = "in.medizon.app.doseAt"

    /** Cancels notification-only reminders for this dose, found by the extras they carry. */
    fun cancelDoseNotifications(c: Context, planId: Long, instant: Long) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        try {
            nm.activeNotifications.forEach { sbn ->
                val x = sbn.notification.extras
                if (x.getLong(EXTRA_PLAN_ID, -1L) == planId && x.getLong(EXTRA_DOSE_AT, -1L) == instant) {
                    nm.cancel(sbn.tag, sbn.id)
                }
            }
        } catch (e: Exception) { /* best effort */ }
    }

    /** Opens the popup for whatever is ringing in AlarmService. */
    fun alarmActivityIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 7001,
        Intent(c, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        FLAGS
    )

    /** Opens the popup (silently) for one notification-only alarm. */
    private fun silentPopupIntent(c: Context, item: AlarmItem): PendingIntent = PendingIntent.getActivity(
        c, ("open-" + item.id).hashCode(),
        item.putInto(Intent(c, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
        FLAGS
    )

    private fun action(c: Context, action: String, item: AlarmItem): PendingIntent = PendingIntent.getBroadcast(
        c, (action + item.id).hashCode(),
        item.putInto(Intent(c, AlarmActionReceiver::class.java).setAction(action)),
        FLAGS
    )

    private var logoBitmap: Bitmap? = null
    private fun logo(c: Context): Bitmap {
        logoBitmap?.let { return it }
        val d = ContextCompat.getDrawable(c, R.drawable.ic_mark)!!
        val size = (96 * c.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        logoBitmap = bmp
        return bmp
    }

    private fun titleFor(items: List<AlarmItem>): String =
        if (items.size == 1) items[0].title else "${items[0].title} (+${items.size - 1} more)"

    /** The ongoing incoming-call notification while AlarmService rings. */
    fun ringing(c: Context, items: List<AlarmItem>): Notification {
        val first = items.first()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return callStyle(c, items)
            } catch (e: Exception) {
                Log.w("MediZonNotif", "CallStyle failed, using plain alarm notification", e)
            }
        }
        val fullScreen = alarmActivityIntent(c)
        val b = NotificationCompat.Builder(c, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setLargeIcon(logo(c))
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(titleFor(items))
            .setContentText(first.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(items.joinToString("\n") { "${it.title} · ${it.body}" }))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            // Not setSilent(): that marks it a silent group alert, and Android then blocks both the heads-up and the
            // full-screen popup. The "Medicine calls" channel has no sound; AlarmService plays the ringtone.
            .setOnlyAlertOnce(true)
            .setWhen(first.atMillis)
            .setShowWhen(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
        addAnswerActions(c, b, first)
        return b.build()
    }

    private fun callStyle(c: Context, items: List<AlarmItem>): Notification {
        val first = items.first()
        val person = Person.Builder()
            .setName(titleFor(items))
            .setIcon(IconCompat.createWithBitmap(logo(c)))
            .setImportant(true)
            .build()
        val answer = action(c, if (first.isDose) AlarmActions.TAKEN else AlarmActions.DISMISS, first)
        val decline = action(c, if (first.isDose) AlarmActions.SKIP else AlarmActions.DISMISS, first)
        val fullScreen = alarmActivityIntent(c)
        return NotificationCompat.Builder(c, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(titleFor(items))
            .setContentText(first.body.ifBlank { "Medicine reminder" })
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(person, decline, answer)
                    .setAnswerButtonColorHint(0xFF1FAA59.toInt())
                    .setDeclineButtonColorHint(ContextCompat.getColor(c, R.color.mz_red))
            )
            .addAction(0, AppSettings.snoozeLabel(c), action(c, AlarmActions.SNOOZE, first))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            // Not setSilent(): that marks it a silent group alert, and Android then blocks both the heads-up and the
            // full-screen popup. The "Medicine calls" channel has no sound; AlarmService plays the ringtone.
            .setOnlyAlertOnce(true)
            .setWhen(first.atMillis)
            .setShowWhen(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()
    }

    private fun addAnswerActions(c: Context, b: NotificationCompat.Builder, item: AlarmItem) {
        if (item.isDose) {
            b.addAction(0, c.getString(R.string.taken), action(c, AlarmActions.TAKEN, item))
            b.addAction(0, c.getString(R.string.skip), action(c, AlarmActions.SKIP, item))
        } else {
            b.addAction(0, c.getString(R.string.ok), action(c, AlarmActions.DISMISS, item))
        }
        b.addAction(0, AppSettings.snoozeLabel(c), action(c, AlarmActions.SNOOZE, item))
    }

    /**
     * After the ring stops (time out, or Back/Home on the popup): same alarms, silent, with the answer
     * buttons. Tapping it reopens the popup (silently). Stays for 2 minutes, then the alarms are forgotten.
     */
    fun pending(c: Context, items: List<AlarmItem>, timeoutMs: Long? = null): Notification {
        val first = items.first()
        // Not setSilent(): silent notifications are hidden on the lock screen. The channel has no sound.
        val b = NotificationCompat.Builder(c, CHANNEL_DUE)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setLargeIcon(logo(c))
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(titleFor(items))
            .setContentText(first.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(items.joinToString("\n") { "${it.title} · ${it.body}" }))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setWhen(first.atMillis)
            .setShowWhen(true)
            .setContentIntent(alarmActivityIntent(c))
            .setDeleteIntent(PendingIntent.getBroadcast(
                c, 7003, Intent(c, AlarmActionReceiver::class.java).setAction(AlarmActions.CLEAR_DUE), FLAGS
            ))
        // Within its 2 minutes: can't be cleared (Clear all skips it; a swipe on Android 14+ re-posts it via
        // the delete intent), then removed by the system.
        if (timeoutMs != null) b.setOngoing(true).setTimeoutAfter(timeoutMs)
        addAnswerActions(c, b, first)
        return b.build()
    }

    /** FCM data message -> one heads-up notification. */
    @SuppressLint("MissingPermission")
    fun push(c: Context, nid: String, title: String, body: String, link: String) {
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val path = link.trim().ifBlank { "/notifications" }
        val open = PendingIntent.getActivity(c, ("push-" + nid).hashCode(), MainActivity.openIntent(c, path), FLAGS)
        val n = NotificationCompat.Builder(c, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(title.ifBlank { "MediZon" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { nm.notify(nid.hashCode(), n) } catch (e: SecurityException) { }
    }

    /** "Ring phone" OFF: one heads-up notification with the default sound; tap opens the silent popup. */
    @SuppressLint("MissingPermission")
    fun reminder(c: Context, item: AlarmItem) {
        val b = NotificationCompat.Builder(c, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setLargeIcon(logo(c))
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setWhen(item.atMillis)
            .setShowWhen(true)
            .setContentIntent(silentPopupIntent(c, item))
        addAnswerActions(c, b, item)
        val instant = item.doseInstant
        if (item.planId != null && instant != null) {
            b.addExtras(android.os.Bundle().apply {
                putLong(EXTRA_PLAN_ID, item.planId)
                putLong(EXTRA_DOSE_AT, instant)
            })
        }
        try {
            NotificationManagerCompat.from(c).notify(reminderNotificationId(item), b.build())
        } catch (e: SecurityException) { /* notifications not permitted */ }
    }

    fun cancelReminder(c: Context, item: AlarmItem) {
        NotificationManagerCompat.from(c).cancel(reminderNotificationId(item))
    }

    class Notice(val id: String, val title: String, val body: String, val path: String)

    /**
     * Posts each notice from the schedule once. Shown ids are remembered and pruned to the ids the
     * server still sends. If notifications aren't allowed, nothing is posted or marked as shown.
     */
    @SuppressLint("MissingPermission")
    fun showNotices(c: Context, notices: List<Notice>) {
        val shown = Store.shownNotices(c)
        val current = notices.map { it.id }.toSet()
        val kept = shown.intersect(current).toMutableSet()
        val nm = NotificationManagerCompat.from(c)
        if (nm.areNotificationsEnabled()) {
            for (n in notices) {
                if (n.id in kept) continue
                val open = PendingIntent.getActivity(c, ("notice-" + n.id).hashCode(), MainActivity.openIntent(c, n.path), FLAGS)
                val notification = NotificationCompat.Builder(c, CHANNEL_ANNOUNCEMENTS)
                    .setSmallIcon(R.drawable.ic_stat_capsule)
                    .setColor(ContextCompat.getColor(c, R.color.mz_green))
                    .setContentTitle(n.title)
                    .setContentText(n.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                try {
                    nm.notify(("notice-" + n.id).hashCode(), notification)
                    kept += n.id
                } catch (e: SecurityException) {
                    break
                }
            }
        }
        if (kept != shown) Store.setShownNotices(c, kept)
    }

    private fun timeText(c: Context, millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(millis))

    /**
     * 30 min after the alarm, the dose is still not marked. Taken / Skip buttons; tapping opens the
     * popup silently. Carries the dose extras so answering the dose anywhere removes it.
     */
    @SuppressLint("MissingPermission")
    fun missedDose(c: Context, check: AlarmItem) {
        val planId = check.planId ?: return
        val t = check.doseInstant ?: return
        val dose = check.copy(atMillis = t) // the popup shows the dose time, not the check time
        val name = dose.medicineName
        val member = dose.memberName
        val due = timeText(c, t)
        val title = if (member == null) "Missed dose: $name" else "Missed dose: $name for $member"
        val text = if (member == null) {
            "Your $due dose isn't marked yet. Take it now and tap Taken. Already taken it? Tap Taken, or just ignore this."
        } else {
            "$member's $due dose isn't marked yet. Give it now and tap Taken. Already taken? Tap Taken, or just ignore this."
        }
        val b = NotificationCompat.Builder(c, CHANNEL_MISSED_DOSES)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setLargeIcon(logo(c))
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setWhen(t)
            .setShowWhen(true)
            .setContentIntent(silentPopupIntent(c, dose))
            .addAction(0, c.getString(R.string.taken), action(c, AlarmActions.TAKEN, dose))
            .addAction(0, c.getString(R.string.skip), action(c, AlarmActions.SKIP, dose))
            .addExtras(android.os.Bundle().apply {
                putLong(EXTRA_PLAN_ID, planId)
                putLong(EXTRA_DOSE_AT, t)
            })
        try {
            NotificationManagerCompat.from(c).notify(("missed-dose-$planId@$t").hashCode(), b.build())
        } catch (e: SecurityException) { /* notifications not permitted */ }
    }

    /** "9:00 AM", or "8 Nov, 9:00 PM" when it isn't today. */
    private fun whenText(c: Context, millis: Long): String =
        if (android.text.format.DateUtils.isToday(millis)) timeText(c, millis)
        else java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(millis)) +
            ", " + timeText(c, millis)

    /**
     * Right after Taken / Skip on an alarm:
     *   Dose taken · Metformin
     *   1 tablet of Metformin was taken by you at 9:05 AM. Scheduled for 9:00 AM.
     */
    @SuppressLint("MissingPermission")
    fun doseMarked(c: Context, item: AlarmItem, taken: Boolean) {
        val planId = item.planId ?: return
        val t = item.doseInstant ?: return
        val now = System.currentTimeMillis()
        val by = item.memberName ?: "you"
        val name = item.medicineName
        // The server sends the body as "Take 1 tablet · after food"; the amount is "1 tablet".
        val first = item.body.substringBefore(" · ").trim()
        val amount = if (first.startsWith("Take ")) first.removePrefix("Take ").trim().ifBlank { "1 dose" } else "1 dose"
        val title = "${if (taken) "Dose taken" else "Dose skipped"} · $name"
        val text = "$amount of $name was ${if (taken) "taken" else "skipped"} by $by at ${timeText(c, now)}. " +
            "Scheduled for ${whenText(c, t)}."
        val open = PendingIntent.getActivity(
            c, ("done-open-$planId@$t").hashCode(), MainActivity.openIntent(c, item.path), FLAGS
        )
        val n = NotificationCompat.Builder(c, CHANNEL_DOSE_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setWhen(now)
            .setShowWhen(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(("done-$planId@$t").hashCode(), n)
        } catch (e: SecurityException) { /* notifications not permitted */ }
    }

    @SuppressLint("MissingPermission")
    fun missed(c: Context, item: AlarmItem) {
        val open = PendingIntent.getActivity(c, ("missed-" + item.id).hashCode(), MainActivity.openIntent(c, item.path), FLAGS)
        val n = NotificationCompat.Builder(c, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_capsule)
            .setColor(ContextCompat.getColor(c, R.color.mz_green))
            .setContentTitle("Missed: ${item.title}")
            .setContentText(item.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(item.atMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(("missed-" + item.id).hashCode(), n)
        } catch (e: SecurityException) { /* notifications not permitted */ }
    }
}
