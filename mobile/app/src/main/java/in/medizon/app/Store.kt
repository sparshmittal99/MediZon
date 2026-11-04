package `in`.medizon.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app persists. Plain private SharedPreferences (EncryptedSharedPreferences would need
 * androidx.security; this is a personal, sideloaded app). All read-modify-write goes through [lock].
 */
object Store {
    private const val FILE = "medizon"
    private const val K_TOKEN = "token"
    private const val K_ACCOUNT = "account"
    private const val K_SCHEDULE = "schedule"          // JSON array of stored AlarmItems (from server)
    private const val K_ONE_OFFS = "oneOffs"           // JSON array of snooze / test alarms
    private const val K_DOSE_QUEUE = "doseQueue"       // JSON array of pending POST /api/app/doses bodies
    private const val K_ASKED_NOTIF = "askedNotif"
    private const val K_UA = "userAgent"

    val lock = Any()

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- link ----
    fun token(c: Context): String? = prefs(c).getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }
    fun account(c: Context): String = prefs(c).getString(K_ACCOUNT, "") ?: ""
    fun isLinked(c: Context) = token(c) != null

    fun saveLink(c: Context, token: String, account: String) {
        prefs(c).edit().putString(K_TOKEN, token).putString(K_ACCOUNT, account).apply()
    }

    fun clearLink(c: Context) = synchronized(lock) {
        prefs(c).edit()
            .remove(K_TOKEN).remove(K_ACCOUNT)
            .remove(K_SCHEDULE).remove(K_DOSE_QUEUE)
            .apply()
    }

    // ---- schedule ----
    fun schedule(c: Context): List<AlarmItem> = readItems(c, K_SCHEDULE)
    fun saveSchedule(c: Context, items: List<AlarmItem>) = writeItems(c, K_SCHEDULE, items)

    fun oneOffs(c: Context): List<AlarmItem> = readItems(c, K_ONE_OFFS)

    fun addOneOff(c: Context, item: AlarmItem) = synchronized(lock) {
        writeItems(c, K_ONE_OFFS, readItems(c, K_ONE_OFFS).filter { it.id != item.id } + item)
    }

    fun removeOneOff(c: Context, id: String) = synchronized(lock) {
        val list = readItems(c, K_ONE_OFFS)
        if (list.any { it.id == id }) writeItems(c, K_ONE_OFFS, list.filter { it.id != id })
    }

    fun clearOneOffs(c: Context) = synchronized(lock) { prefs(c).edit().remove(K_ONE_OFFS).apply() }

    private fun readItems(c: Context, key: String): List<AlarmItem> {
        val raw = prefs(c).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { AlarmItem.fromStored(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeItems(c: Context, key: String, items: List<AlarmItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(key, arr.toString()).commit()
    }

    // ---- dose queue ----
    fun queueDose(c: Context, planId: Long, at: String, status: String) = synchronized(lock) {
        val arr = doseQueueRaw(c)
        // One answer per dose: replace any earlier queued answer for the same plan + time.
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!(o.optLong("planId") == planId && o.optString("at") == at)) out.put(o)
        }
        out.put(JSONObject().put("planId", planId).put("at", at).put("status", status))
        prefs(c).edit().putString(K_DOSE_QUEUE, out.toString()).commit()
    }

    fun doseQueue(c: Context): List<JSONObject> = synchronized(lock) {
        val arr = doseQueueRaw(c)
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun removeDose(c: Context, dose: JSONObject) = synchronized(lock) {
        val arr = doseQueueRaw(c)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val same = o.optLong("planId") == dose.optLong("planId") &&
                o.optString("at") == dose.optString("at") &&
                o.optString("status") == dose.optString("status")
            if (!same) out.put(o)
        }
        prefs(c).edit().putString(K_DOSE_QUEUE, out.toString()).commit()
    }

    private fun doseQueueRaw(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString(K_DOSE_QUEUE, "[]")) } catch (e: Exception) { JSONArray() }

    // ---- misc ----
    fun askedNotifications(c: Context) = prefs(c).getBoolean(K_ASKED_NOTIF, false)
    fun setAskedNotifications(c: Context) = prefs(c).edit().putBoolean(K_ASKED_NOTIF, true).apply()

    // ---- alarms that went off and are not answered yet ----
    fun dueAlarms(c: Context): List<AlarmItem> = readItems(c, "dueAlarms")
    fun saveDueAlarms(c: Context, items: List<AlarmItem>) = writeItems(c, "dueAlarms", items)

    // ---- FCM token last sent to the server ----
    fun lastPushToken(c: Context): String? = prefs(c).getString("lastPushToken", null)
    fun setLastPushToken(c: Context, token: String?) = prefs(c).edit().putString("lastPushToken", token).apply()

    // ---- announcement notices already shown ----
    fun shownNotices(c: Context): Set<String> = prefs(c).getStringSet("shownNotices", emptySet())?.toSet() ?: emptySet()
    fun setShownNotices(c: Context, ids: Set<String>) = prefs(c).edit().putStringSet("shownNotices", HashSet(ids)).apply()

    fun lastSync(c: Context): Long = prefs(c).getLong("lastSync", 0L)
    fun setLastSync(c: Context, millis: Long) = prefs(c).edit().putLong("lastSync", millis).apply()

    fun userAgent(c: Context): String? = prefs(c).getString(K_UA, null)
    fun saveUserAgent(c: Context, ua: String) = prefs(c).edit().putString(K_UA, ua).apply()
}
