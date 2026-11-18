package `in`.medizon.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why an alarm didn't ring on this phone. Alarms are set with setAlarmClock, which Android delivers on time even in
 * Doze, so a silent phone almost always means one of:
 *  - the app was force-stopped (by a battery saver / "cleaner", a swipe on some phones, or Settings): Android then
 *    deletes all its alarms, and won't run its sync or deliver its pushes until the app is opened again;
 *  - the phone was off or restarting at the alarm time;
 *  - the alarm was delivered late (only when exact alarms aren't allowed), and became a "missed" notification.
 *
 * This keeps a small log on the phone (Settings → Alarm check): every ring and how late it was, every scheduled
 * alarm that never rang, and every time the alarms were found wiped, with what Android says happened. It also puts
 * wiped alarms back whenever the app wakes up for any reason (a push, the periodic sync, opening it).
 */
object AlarmHealth {
    private const val TAG = "MediZonAlarmHealth"
    private const val PREFS = "medizon-alarm-health"
    private const val K_FIRED = "fired"     // {alarmId: firedAtMillis}, last 3 days
    private const val K_LOG = "log"         // JSON array of events, newest first
    private const val KEEP_FIRED_MS = 3 * 24 * 60 * 60 * 1000L
    private const val AUDIT_WINDOW_MS = 2 * 24 * 60 * 60 * 1000L
    private const val MAX_LOG = 30
    /** Later than this counts as a late ring. */
    private const val LATE_MS = 60 * 1000L

    data class Event(val atMillis: Long, val type: String, val title: String, val detail: String)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- rings

    /**
     * An alarm is about to ring, from the phone's own alarm or from the server's dose-time backup push: only the
     * first of the two for the same alarm id gets true, so a dose rings once. Both run in this app's process and
     * check under [Store.lock], so even two arriving in the same instant can't both ring. Logs a late ring, and a
     * ring that had to come from the push (the phone's own alarm didn't go off).
     */
    fun claim(c: Context, item: AlarmItem, viaPush: Boolean): Boolean {
        val now = System.currentTimeMillis()
        // A snooze keeps its id ("…-snooze") through every snooze, so each snooze ring is its own, by time
        val key = if (item.id.endsWith("-snooze")) item.id + "@" + item.atMillis else item.id
        synchronized(Store.lock) {
            val fired = JSONObject(prefs(c).getString(K_FIRED, "{}") ?: "{}")
            if (fired.has(key)) return false
            fired.put(key, now)
            val keys = fired.keys().asSequence().toList()
            keys.filter { fired.optLong(it) < now - KEEP_FIRED_MS }.forEach { fired.remove(it) }
            prefs(c).edit().putString(K_FIRED, fired.toString()).commit()
        }
        val late = now - item.atMillis
        if (viaPush) {
            log(c, Event(now, "BACKUP", item.title, "Rang from the backup push at " + time(now) + ": this phone's own alarm for "
                    + time(item.atMillis) + " hadn't gone off. " + reasons(c, item.atMillis)), item.id)
        } else if (late > LATE_MS) {
            log(c, Event(now, "LATE", item.title, "Rang " + minutes(late) + " late (due " + time(item.atMillis) + "). " + reasons(c, item.atMillis)))
        }
        return true
    }

    private fun firedIds(c: Context): Set<String> =
        JSONObject(prefs(c).getString(K_FIRED, "{}") ?: "{}").keys().asSequence().toSet()

    // ---------------------------------------------------------------- audits

    /**
     * Before a sync replaces the stored schedule: every stored alarm whose time has passed (in the last two days)
     * and that never went off here is logged as missed, with the likely reason. Doses answered on another device
     * before their time were already taken out of the schedule, so they don't count.
     */
    fun auditMissed(c: Context, stored: List<AlarmItem>) {
        val now = System.currentTimeMillis()
        val fired = firedIds(c)
        val seen = loggedMissed(c)
        stored.filter { it.atMillis in (now - AUDIT_WINDOW_MS)..(now - LATE_MS) && it.id !in fired && it.id !in seen }
            .forEach { log(c, Event(it.atMillis, "MISSED", it.title, "Didn't ring at " + time(it.atMillis) + ". " + reasons(c, it.atMillis)), it.id) }
    }

    /**
     * Are the future alarms still set with Android? If any were wiped (the app was force-stopped), log it and set
     * them all again. Safe to call often: it only re-arms when something is missing. Not on the main thread.
     */
    fun checkAndRepair(c: Context) {
        if (!Store.isLinked(c)) return
        val now = System.currentTimeMillis()
        val future = Store.schedule(c).filter { it.atMillis > now }
        if (future.isEmpty()) return
        val wiped = future.count { !AlarmScheduler.isScheduled(c, it.id) }
        if (wiped == 0) return
        log(c, Event(now, "WIPED", "$wiped of ${future.size} alarms were removed",
            "Found missing and set again. " + reasons(c, null)))
        AlarmScheduler.rescheduleAllFromStore(c)
    }

    // ---------------------------------------------------------------- reasons

    /**
     * What Android can tell us about a problem at [around] (or now): the phone restarting, the app being
     * force-stopped or killed, and the settings that let the system hold the app back.
     */
    fun reasons(c: Context, around: Long?): String {
        val out = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val bootedAt = now - SystemClock.elapsedRealtime()
        if (around != null && bootedAt > around) out += "The phone restarted after that time (at " + time(bootedAt) + "), so it may have been off."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = c.getSystemService(ActivityManager::class.java)
                val since = (around ?: now) - AUDIT_WINDOW_MS
                am.getHistoricalProcessExitReasons(c.packageName, 0, 20)
                    .filter { it.timestamp in since..(around ?: now) }
                    .filter { it.reason == ApplicationExitInfo.REASON_USER_STOPPED || it.reason == ApplicationExitInfo.REASON_OTHER
                            || it.reason == ApplicationExitInfo.REASON_PERMISSION_CHANGE || it.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED }
                    .maxByOrNull { it.timestamp }
                    ?.let { e ->
                        val what = when (e.reason) {
                            ApplicationExitInfo.REASON_USER_STOPPED -> "MediZon was force-stopped at " + time(e.timestamp) +
                                    " (by a battery saver or cleaner app, or from Settings). Android removes a force-stopped app's alarms until it's opened again."
                            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "MediZon was updated at " + time(e.timestamp) + "."
                            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission of MediZon changed at " + time(e.timestamp) + "."
                            else -> "The phone's system closed MediZon at " + time(e.timestamp) + "."
                        }
                        out += what + (if (!e.description.isNullOrBlank()) " (" + e.description + ")" else "")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "exit reasons unavailable", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = c.getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) out += "\"Alarms & reminders\" is not allowed, so Android can delay alarms."
        }
        val pm = c.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(c.packageName)) out += "Battery isn't set to Unrestricted."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bucket = c.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            if (bucket >= UsageStatsManager.STANDBY_BUCKET_RARE) {
                out += "Android has put MediZon in a " + (if (bucket >= 45) "restricted" else "rarely used") + " group, which holds back its background work."
            }
        }
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) out += "Notifications are off for MediZon."
        if (out.isEmpty()) out += "Android didn't report a reason. On Xiaomi, Oppo, Vivo, Realme, OnePlus and Samsung phones, check that MediZon is allowed to auto-start and isn't in \"sleeping apps\"."
        return out.joinToString(" ")
    }

    // ---------------------------------------------------------------- log

    fun events(c: Context): List<Event> {
        val arr = JSONArray(prefs(c).getString(K_LOG, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Event(it.optLong("at"), it.optString("type"), it.optString("title"), it.optString("detail")) }
    }

    fun clear(c: Context) = prefs(c).edit().remove(K_LOG).apply()

    private fun loggedMissed(c: Context): Set<String> {
        val arr = JSONArray(prefs(c).getString(K_LOG, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }.toSet()
    }

    private fun log(c: Context, e: Event, id: String? = null) {
        Log.w(TAG, "${e.type} ${e.title}: ${e.detail}")
        synchronized(Store.lock) {
            val old = JSONArray(prefs(c).getString(K_LOG, "[]") ?: "[]")
            val next = JSONArray().put(JSONObject().put("at", e.atMillis).put("type", e.type).put("title", e.title)
                .put("detail", e.detail).put("id", id ?: ""))
            for (i in 0 until minOf(old.length(), MAX_LOG - 1)) next.put(old.getJSONObject(i))
            prefs(c).edit().putString(K_LOG, next.toString()).apply()
        }
    }

    private fun minutes(ms: Long): String {
        val m = ms / 60_000L
        return if (m < 1) "under a minute" else if (m == 1L) "1 minute" else "$m minutes"
    }

    fun time(millis: Long): String = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH).format(Date(millis))
}
