package `in`.medizon.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** What the alarm buttons do. Shared by AlarmActivity and the notification action buttons. */
object AlarmActions {
    const val TAKEN = "in.medizon.app.action.TAKEN"
    const val SKIP = "in.medizon.app.action.SKIP"
    const val SNOOZE = "in.medizon.app.action.SNOOZE"
    const val DISMISS = "in.medizon.app.action.DISMISS"
    const val SNOOZE_ALL = "in.medizon.app.action.SNOOZE_ALL"
    const val CLEAR_DUE = "in.medizon.app.action.CLEAR_DUE"
    /** 2 min after a phone button stopped the ring: forget the alarms that were ringing then. */
    const val EXPIRE_DUE = "in.medizon.app.action.EXPIRE_DUE"
    const val EXTRA_IDS = "in.medizon.app.ids"


    /** Main thread. Returns the toast text, or null. */
    fun perform(c: Context, action: String, item: AlarmItem): String? {
        val msg = when (action) {
            TAKEN -> { answer(c, item, "TAKEN"); "Marked taken" }
            SKIP -> { answer(c, item, "SKIPPED"); "Dose skipped" }
            SNOOZE -> { snooze(c, item); "Snoozed for ${AppSettings.snoozeMinutes(c)} minutes" }
            DISMISS -> null
            else -> return null
        }
        // Same removal as when another device answers (DOSE_DONE push): all copies of this dose go.
        if ((action == TAKEN || action == SKIP) && item.planId != null && item.at.isNotBlank()) {
            AlarmService.dismissDose(c, item.planId, item.at)
        }
        Notifications.cancelReminder(c, item)
        AlarmService.resolve(c, item.id)
        // Phone notification only (no in-app / email): "Metformin — taken · Taken by you at 9:05 AM".
        if ((action == TAKEN || action == SKIP) && item.isDose && item.at.isNotBlank()) {
            Notifications.doseMarked(c, item, taken = action == TAKEN)
        }
        return msg
    }

    fun snoozeAll(c: Context) {
        AlarmService.activeAlarms(c).forEach { snooze(c, it); AlarmService.resolve(c, it.id) }
    }

    private fun snooze(c: Context, item: AlarmItem) {
        val ms = AppSettings.snoozeMinutes(c) * 60_000L
        AlarmScheduler.scheduleOneOff(c, item.snoozed(System.currentTimeMillis() + ms))
    }

    /** Test alarm (kind TEST): rings or notifies per the current settings. */
    fun scheduleTest(c: Context, delayMs: Long) {
        val now = System.currentTimeMillis()
        AlarmScheduler.scheduleOneOff(c, AlarmItem(
            id = "test-$now", kind = "TEST", planId = null, reminderId = null, at = "",
            atMillis = now + delayMs, title = "Test alarm",
            body = "This is how dose alarms will ring.", path = "/",
        ))
    }

    private fun answer(c: Context, item: AlarmItem, status: String) {
        val planId = item.planId ?: return
        if (item.at.isBlank()) return
        Store.queueDose(c, planId, item.at, status)
        Sync.flushDosesSoon(c)
    }
}

/** Notification action buttons (no UI). */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == AlarmActions.CLEAR_DUE) {
            AlarmService.clearDue(context)
            return
        }
        if (action == AlarmActions.EXPIRE_DUE) {
            val ids = intent.getStringArrayExtra(AlarmActions.EXTRA_IDS)?.toSet() ?: return
            AlarmService.expire(context, ids)
            return
        }
        if (action == AlarmActions.SNOOZE_ALL) {
            AlarmActions.snoozeAll(context)
            Toast.makeText(context, "Snoozed for ${AppSettings.snoozeMinutes(context)} minutes", Toast.LENGTH_SHORT).show()
            return
        }
        val item = AlarmItem.fromIntent(intent) ?: return
        AlarmActions.perform(context, action, item)?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Fired by AlarmManager at the alarm time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_FOLLOW_UP) {
            AlarmItem.fromIntent(intent)?.let { followUp(context, it) }
            return
        }
        if (intent.action != AlarmScheduler.ACTION_FIRE) return
        val item = AlarmItem.fromIntent(intent) ?: return
        Store.removeOneOff(context, item.id)
        // Way overdue (e.g. device was off): don't ring, just tell the user.
        if (System.currentTimeMillis() - item.atMillis > 30 * 60 * 1000L) {
            Notifications.missed(context, item)
            return
        }
        // Not answered 30 min from now -> "Missed dose" notification (phone only).
        if (item.isDose) AlarmScheduler.scheduleFollowUp(context, item)
        // "Ring phone" on or off, the alarm works the same way: full-screen popup, and a notification that can't be
        // cleared until 2 minutes after the alert ends. Off only means no ringtone (AlarmService.startRinging).
        try {
            AlarmService.fire(context, item)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException if the OS refuses; at least leave a notification.
            Notifications.missed(context, item)
        }
    }

    /**
     * 30 min after a dose alarm rang. Answers cancel this check, so getting here means the dose is
     * still unmarked as far as this phone knows. Unmarked doses are closed at the end of the day anyway,
     * so this is only a nudge.
     */
    private fun followUp(c: Context, check: AlarmItem) {
        AlarmScheduler.removeFollowUp(c, check.id)
        val planId = check.planId ?: return
        val t = check.doseInstant ?: return
        val now = System.currentTimeMillis()
        if (AlarmService.isDismissed(check)) return
        // Answered on this phone but not sent yet (offline).
        if (Store.doseQueue(c).any { it.optLong("planId") == planId && instantOf(it.optString("at")) == t }) return
        // Snoozed and will ring again: that ring sets its own check.
        if (Store.oneOffs(c).any { it.isSameDose(planId, t) && it.atMillis > now }) return
        Notifications.missedDose(c, check)
    }

    private fun instantOf(at: String): Long? = try {
        java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}

/** Boot / app update / clock change: re-arm from storage, then sync. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                AlarmScheduler.rescheduleAllFromStore(context)
                if (Store.isLinked(context)) {
                    Sync.syncNow(context)
                    Sync.ensurePeriodic(context)
                }
            }
        }
    }
}
