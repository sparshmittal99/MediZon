package `in`.medizon.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/** Schedules / cancels alarms with AlarmManager. One PendingIntent per alarm id (unique data URI). */
object AlarmScheduler {
    private const val TAG = "MediZonAlarms"
    const val ACTION_FIRE = "in.medizon.app.action.ALARM_FIRE"

    private fun alarmManager(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(c).canScheduleExactAlarms()

    private fun operation(c: Context, id: String, item: AlarmItem?): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("medizon-alarm://alarm/" + Uri.encode(id)))
        if (item != null) item.putInto(intent)
        return PendingIntent.getBroadcast(
            c, id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun schedule(c: Context, item: AlarmItem) {
        if (item.atMillis <= System.currentTimeMillis()) return
        val am = alarmManager(c)
        val op = operation(c, item.id, item)
        val show = PendingIntent.getActivity(
            c, ("show-" + item.id).hashCode(),
            MainActivity.openIntent(c, item.path),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(item.atMillis, show), op)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "setAlarmClock not allowed, falling back", e)
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, op)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "setExactAndAllowWhileIdle not allowed, falling back", e)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.atMillis, op)
    }

    fun cancel(c: Context, id: String) {
        val op = operation(c, id, null)
        alarmManager(c).cancel(op)
        op.cancel()
    }

    /** Diff the new schedule against the stored one: cancel removed alarms, (re)schedule the rest. */
    fun applySchedule(c: Context, fresh: List<AlarmItem>) {
        synchronized(Store.lock) {
            val old = Store.schedule(c)
            val freshIds = fresh.map { it.id }.toHashSet()
            old.filter { it.id !in freshIds }.forEach { cancel(c, it.id) }
            val oldById = old.associateBy { it.id }
            val now = System.currentTimeMillis()
            fresh.filter { it.atMillis > now }.forEach { item ->
                // setAlarmClock with the same PendingIntent replaces, so re-scheduling unchanged ones is harmless;
                // but skip identical ones to keep AlarmManager churn low.
                if (oldById[item.id] != item || !isScheduled(c, item.id)) schedule(c, item)
            }
            Store.saveSchedule(c, fresh.filter { it.atMillis > now })
        }
    }

    private fun isScheduled(c: Context, id: String): Boolean {
        val intent = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("medizon-alarm://alarm/" + Uri.encode(id)))
        return PendingIntent.getBroadcast(
            c, id.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        ) != null
    }

    /** Re-arm everything from local storage (boot, app update, time change). No network needed. */
    fun rescheduleAllFromStore(c: Context) {
        val now = System.currentTimeMillis()
        Store.schedule(c).filter { it.atMillis > now }.forEach { schedule(c, it) }
        Store.oneOffs(c).forEach { if (it.atMillis > now) schedule(c, it) else Store.removeOneOff(c, it.id) }
        rescheduleFollowUps(c)
    }

    fun scheduleOneOff(c: Context, item: AlarmItem) {
        Store.addOneOff(c, item)
        schedule(c, item)
    }

    fun cancelEverything(c: Context) {
        Store.schedule(c).forEach { cancel(c, it.id) }
        Store.oneOffs(c).forEach { cancel(c, it.id) }
        Store.clearOneOffs(c)
        synchronized(Store.lock) {
            Store.followUps(c).forEach { cancelFollowUpAlarm(c, it.id) }
            Store.saveFollowUps(c, emptyList())
        }
    }

    // ---- missed-dose check, 30 min after a dose alarm rang ----

    const val ACTION_FOLLOW_UP = "in.medizon.app.action.DOSE_FOLLOW_UP"
    const val FOLLOW_UP_DELAY_MS = 30 * 60 * 1000L
    /** After a reboot, checks older than this are dropped instead of shown late. */
    private const val FOLLOW_UP_MAX_LATE_MS = 2 * 60 * 60 * 1000L

    private fun followUpOp(c: Context, id: String, item: AlarmItem?): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION_FOLLOW_UP)
            .setData(Uri.parse("medizon-followup://dose/" + Uri.encode(id)))
        if (item != null) item.putInto(intent)
        return PendingIntent.getBroadcast(
            c, ("followup-" + id).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * A dose alarm just went off: check again in 30 min whether it was answered. One check per dose;
     * a later ring of the same dose (snooze) moves it. Answering the dose cancels it ([cancelFollowUps]).
     */
    fun scheduleFollowUp(c: Context, dose: AlarmItem) {
        val planId = dose.planId ?: return
        val t = dose.doseInstant ?: return
        val check = dose.copy(id = "followup-$planId@$t", atMillis = System.currentTimeMillis() + FOLLOW_UP_DELAY_MS)
        synchronized(Store.lock) {
            Store.saveFollowUps(c, Store.followUps(c).filter { it.id != check.id } + check)
        }
        armFollowUp(c, check)
    }

    private fun armFollowUp(c: Context, check: AlarmItem) {
        // Not setAlarmClock: a background check shouldn't show the alarm-clock icon.
        val am = alarmManager(c)
        val op = followUpOp(c, check.id, check)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, check.atMillis, op)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "exact follow-up not allowed, falling back", e)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, check.atMillis, op)
    }

    private fun cancelFollowUpAlarm(c: Context, id: String) {
        val op = followUpOp(c, id, null)
        alarmManager(c).cancel(op)
        op.cancel()
    }

    /** The dose was answered (here, on another phone or the website): no missed-dose notification. */
    fun cancelFollowUps(c: Context, planId: Long, instant: Long) {
        synchronized(Store.lock) {
            val all = Store.followUps(c)
            val hit = all.filter { it.isSameDose(planId, instant) }
            if (hit.isEmpty()) return
            hit.forEach { cancelFollowUpAlarm(c, it.id) }
            Store.saveFollowUps(c, all.filter { !it.isSameDose(planId, instant) })
        }
    }

    /** The check went off: forget it. */
    fun removeFollowUp(c: Context, id: String) {
        synchronized(Store.lock) {
            val all = Store.followUps(c)
            if (all.any { it.id == id }) Store.saveFollowUps(c, all.filter { it.id != id })
        }
    }

    private fun rescheduleFollowUps(c: Context) {
        val now = System.currentTimeMillis()
        synchronized(Store.lock) {
            val (keep, drop) = Store.followUps(c).partition { now - it.atMillis < FOLLOW_UP_MAX_LATE_MS }
            keep.forEach { armFollowUp(c, it) } // a past time fires right away
            if (drop.isNotEmpty()) Store.saveFollowUps(c, keep)
        }
    }

    fun countScheduled(c: Context): Int {
        val now = System.currentTimeMillis()
        return Store.schedule(c).count { it.atMillis > now } + Store.oneOffs(c).count { it.atMillis > now }
    }
}
