package `in`.medizon.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * window.MediZonApp — the contract the server-side JS relies on. Methods run on a WebView binder
 * thread; anything touching UI is posted to the main thread. Every call is ignored unless the page
 * currently shown is on the BASE_URL host.
 */
class Bridge(private val activity: MainActivity) {
    private val app: Context = activity.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private fun trusted(): Boolean {
        val url = activity.currentUrl ?: return false
        return try { Uri.parse(url).host.equals(MainActivity.baseHost, ignoreCase = true) } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun version(): String = if (trusted()) "1.0" else ""

    @JavascriptInterface
    fun linkedAccount(): String = if (trusted()) Store.account(app) else ""

    @JavascriptInterface
    fun link(token: String?, account: String?) {
        if (!trusted() || token.isNullOrBlank()) return
        Store.saveLink(app, token, account ?: "")
        Sync.syncNow(app, replace = true)
        Sync.ensurePeriodic(app)
        Push.ensureToken(app, force = true)
    }

    @JavascriptInterface
    fun unlink() {
        if (!trusted()) return
        Sync.unlink(app)
    }

    @JavascriptInterface
    fun syncNow() {
        if (!trusted()) return
        Sync.syncNowThrottled(app)
    }

    @JavascriptInterface
    fun alarmStatus(): String {
        if (!trusted()) return "{}"
        return status(app).toString()
    }

    @JavascriptInterface
    fun fixAlarmSettings() {
        if (!trusted()) return
        main.post { activity.startFixAlarmSettings() }
    }

    @JavascriptInterface
    fun print(title: String?) {
        if (!trusted()) return
        main.post { activity.printPage(title?.takeIf { it.isNotBlank() } ?: "MediZon") }
    }

    @JavascriptInterface
    fun testAlarm() {
        if (!trusted()) return
        AlarmActions.scheduleTest(app, 10_000)
        main.post { activity.toast("Test alarm in 10 seconds. You can close the app and lock the phone.") }
    }

    @JavascriptInterface
    fun theme(): String {
        if (!trusted()) return "light"
        return when (AppSettings.theme(app)) {
            "dark" -> "dark"
            "light" -> "light"
            // "system": resolve from the activity's night mode (AppCompat has already applied it),
            // so the page never depends on prefers-color-scheme inside the WebView.
            else -> {
                val night = activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
            }
        }
    }

    /** Raw stored value: "light" | "dark" | "system". */
    @JavascriptInterface
    fun themeSetting(): String = if (trusted()) AppSettings.theme(app) else "light"

    @JavascriptInterface
    fun setTheme(mode: String?) {
        if (!trusted() || mode == null) return
        main.post {
            if (AppSettings.setTheme(app, mode)) activity.onThemeChanged()
        }
    }

    @JavascriptInterface
    fun language(): String = if (trusted()) AppSettings.language(app) else "en"

    @JavascriptInterface
    fun textSize(): String = if (trusted()) AppSettings.textSize(app) else "md"

    @JavascriptInterface
    fun reduceMotion(): Boolean = trusted() && AppSettings.reduceMotion(app)

    @JavascriptInterface
    fun openSettings() {
        if (!trusted()) return
        main.post { activity.startActivity(android.content.Intent(activity, SettingsActivity::class.java)) }
    }

    companion object {
        fun status(c: Context): JSONObject {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
            return JSONObject()
                .put("notifications", NotificationManagerCompat.from(c).areNotificationsEnabled())
                .put("exactAlarms", Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())
                .put("fullScreen", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
                .put("batteryUnrestricted", pm.isIgnoringBatteryOptimizations(c.packageName))
                .put("linked", Store.isLinked(c))
                .put("alarms", AlarmScheduler.countScheduled(c))
                .put("ringPhone", AppSettings.ringPhone(c))
        }
    }
}
