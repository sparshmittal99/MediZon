package `in`.medizon.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat

/** Native app settings (hand-built rows, no androidx.preference dependency). */
class SettingsActivity : AppCompatActivity() {

    private class Row(val root: View, val title: TextView, val summary: TextView, val switch: SwitchCompat?)

    private lateinit var list: LinearLayout
    private var dp = 1f

    private lateinit var darkRow: Row
    private lateinit var systemRow: Row
    private lateinit var languageRow: Row
    private lateinit var textSizeRow: Row
    private lateinit var motionRow: Row
    private lateinit var ringRow: Row
    private lateinit var ringtoneRow: Row
    private lateinit var vibrateRow: Row
    private lateinit var ringForRow: Row
    private lateinit var snoozeRow: Row
    private lateinit var notifRow: Row
    private lateinit var exactRow: Row
    private lateinit var fullScreenRow: Row
    private lateinit var batteryRow: Row
    private lateinit var linkedRow: Row
    private lateinit var lastSyncRow: Row
    private lateinit var alarmsRow: Row
    private lateinit var syncRow: Row

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data ?: return@registerForActivityResult
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        if (uri != null) AppSettings.setRingtoneSetting(this, uri.toString())
        refresh()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) openAppNotificationSettings()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        dp = resources.displayMetrics.density
        window.statusBarColor = ContextCompat.getColor(this, R.color.mz_header)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.mz_navbar)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !isNight()
        }
        findViewById<View>(R.id.settingsBack).setOnClickListener { finish() }
        list = findViewById(R.id.settingsList)
        build()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ building

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun build() {
        section("Appearance")
        darkRow = switchRow("Dark mode", isNight()) { on ->
            AppSettings.setTheme(this, if (on) "dark" else "light") // AppCompat recreates this screen
        }
        systemRow = switchRow("Match phone setting", AppSettings.theme(this) == "system") { on ->
            // Turning "match phone" off keeps whatever the screen looks like right now.
            AppSettings.setTheme(this, if (on) "system" else if (isNight()) "dark" else "light")
        }

        languageRow = row("Language") { chooseLanguage() }
        textSizeRow = row("Text size") { chooseTextSize() }
        motionRow = switchRow("Reduce motion", AppSettings.reduceMotion(this)) {
            AppSettings.setReduceMotion(this, it); refresh()
        }

        section("Dose alerts")
        ringRow = switchRow("Ring phone like a call", AppSettings.ringPhone(this)) {
            AppSettings.setRingPhone(this, it); refresh()
        }
        ringtoneRow = row("Ringtone") { pickRingtone() }
        vibrateRow = switchRow("Vibrate", AppSettings.vibrate(this)) { AppSettings.setVibrate(this, it); refresh() }
        ringForRow = row("Ring for") { chooseRingFor() }
        snoozeRow = row("Snooze for") { chooseSnooze() }
        row("Test alert", "Rings (or notifies) in 5 seconds") {
            AlarmActions.scheduleTest(this, 5_000)
            toast("Test alert in 5 seconds. You can lock the phone.")
        }

        section("Permissions")
        notifRow = row("Notifications") { fixNotifications() }
        exactRow = row("Alarms & reminders") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } else toast("Always allowed on this Android version")
        }
        fullScreenRow = row("Full-screen alerts") {
            if (Build.VERSION.SDK_INT >= 34) {
                open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else toast("Always allowed on this Android version")
        }
        batteryRow = row("Battery: Unrestricted") {
            if (!Bridge.status(this).getBoolean("batteryUnrestricted")) {
                open(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                )
            } else open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        section("Sync")
        linkedRow = row("Linked account", clickable = false)
        lastSyncRow = row("Last synced", clickable = false)
        alarmsRow = row("Alarms set", clickable = false)
        syncRow = row("Sync now", "Fetch the latest dose schedule") { syncNow() }

        section("App")
        row("Clear cache", "Frees space. You stay signed in.") {
            WebView(this).apply { clearCache(true); destroy() }
            toast("Cache cleared")
        }
        row("Open website in browser", BuildConfig.BASE_URL.removePrefix("https://")) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.BASE_URL)))
        }
        row("Notification settings", "Sounds and channels for MediZon") { openAppNotificationSettings() }
        row("About", "MediZon for Android · version ${BuildConfig.VERSION_NAME}", clickable = false)
    }

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.mz_accent))
            textSize = 14f
            typeface = ResourcesCompat.getFont(context, R.font.figtree_semibold)
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        })
    }

    private fun row(title: String, summary: String? = null, clickable: Boolean = true, onClick: (() -> Unit)? = null): Row =
        addRow(title, summary, null, clickable, onClick)

    private fun switchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): Row {
        val sw = SwitchCompat(this).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            val accent = ContextCompat.getColor(context, R.color.mz_accent)
            thumbTintList = ColorStateList(states, intArrayOf(accent, ContextCompat.getColor(context, R.color.mz_switch_off)))
            trackTintList = ColorStateList(states, intArrayOf((accent and 0x00FFFFFF) or 0x80000000.toInt(), ContextCompat.getColor(context, R.color.mz_track_off)))
            isClickable = false
            isFocusable = false
        }
        lateinit var r: Row
        r = addRow(title, null, sw, true) {
            sw.isChecked = !sw.isChecked
            onChange(sw.isChecked)
        }
        return r
    }

    private fun addRow(title: String, summary: String?, trailing: View?, clickable: Boolean, onClick: (() -> Unit)?): Row {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (64 * dp).toInt()
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
            val surface = ColorDrawable(ContextCompat.getColor(context, R.color.mz_surface))
            if (clickable && onClick != null) {
                val ripple = ColorStateList.valueOf((ContextCompat.getColor(context, R.color.mz_accent) and 0x00FFFFFF) or 0x22000000)
                background = RippleDrawable(ripple, surface, null)
                setOnClickListener { onClick() }
            } else {
                background = surface
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.mz_text))
            textSize = 16f
        }
        val s = TextView(this).apply {
            setTextColor(ContextCompat.getColor(context, R.color.mz_text2))
            textSize = 14f
            text = summary ?: ""
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        texts.addView(t)
        texts.addView(s)
        root.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { root.addView(it) }
        list.addView(root)
        list.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.mz_divider)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = (20 * dp).toInt()
        })
        return Row(root, t, s, trailing as? SwitchCompat)
    }

    private fun setSummary(r: Row, text: String, color: Int? = null, bold: Boolean = false) {
        r.summary.text = text
        r.summary.visibility = View.VISIBLE
        r.summary.setTextColor(color ?: ContextCompat.getColor(this, R.color.mz_text2))
        r.summary.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun setEnabled(r: Row, enabled: Boolean) {
        r.root.isEnabled = enabled
        r.root.alpha = if (enabled) 1f else 0.45f
    }

    // ------------------------------------------------------------------ state

    private fun refresh() {
        val theme = AppSettings.theme(this)
        darkRow.switch?.isChecked = isNight()
        setSummary(darkRow, if (theme == "system") "Following the phone" else if (isNight()) "On" else "Off")
        setEnabled(darkRow, theme != "system")
        systemRow.switch?.isChecked = theme == "system"
        setSummary(systemRow, if (theme == "system") "Light or dark, same as the phone" else "Off")

        setSummary(languageRow, AppSettings.languageLabel(AppSettings.language(this)) + " · Translates the MediZon pages")
        setSummary(textSizeRow, AppSettings.textSizeLabel(AppSettings.textSize(this)))
        motionRow.switch?.isChecked = AppSettings.reduceMotion(this)
        setSummary(motionRow, "Turns off animations on MediZon pages")

        val ring = AppSettings.ringPhone(this)
        ringRow.switch?.isChecked = ring
        setSummary(ringRow, if (ring) "Rings with a full-screen popup, even when locked" else "Notification only")
        setSummary(ringtoneRow, ringtoneTitle())
        setEnabled(ringtoneRow, ring)
        vibrateRow.switch?.isChecked = AppSettings.vibrate(this)
        setSummary(vibrateRow, if (AppSettings.vibrate(this)) "On" else "Off")
        setSummary(ringForRow, AppSettings.ringLabel(AppSettings.ringSeconds(this)))
        setSummary(snoozeRow, "${AppSettings.snoozeMinutes(this)} minutes")

        val s = Bridge.status(this)
        status(notifRow, s.getBoolean("notifications"))
        status(exactRow, s.getBoolean("exactAlarms"))
        status(fullScreenRow, s.getBoolean("fullScreen"))
        status(batteryRow, s.getBoolean("batteryUnrestricted"))

        val linked = Store.isLinked(this)
        val account = Store.account(this)
        setSummary(linkedRow, if (linked) (if (account.isNotBlank()) "Linked · $account" else "Linked") else "Not linked. Sign in on the site inside the app.")
        val last = Store.lastSync(this)
        setSummary(lastSyncRow, if (last == 0L) "Never" else DateUtils.getRelativeDateTimeString(
            this, last, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0
        ).toString())
        setSummary(alarmsRow, AlarmScheduler.countScheduled(this).toString())
        setEnabled(syncRow, linked)
    }

    private fun status(r: Row, ok: Boolean) = setSummary(
        r, if (ok) "Allowed" else "Not allowed · tap to fix",
        ContextCompat.getColor(this, if (ok) R.color.mz_accent else R.color.mz_amber), bold = true
    )

    private fun ringtoneTitle(): String {
        val uri = AppSettings.ringtoneUri(this) ?: return "MediZon tring (default)"
        return try {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Custom ringtone"
        } catch (e: Exception) {
            "Custom ringtone"
        }
    }

    // ------------------------------------------------------------------ actions

    private fun pickRingtone() {
        if (!AppSettings.ringPhone(this)) return
        val current = if (AppSettings.ringtoneUri(this) == null) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("Ringtone")
            .setSingleChoiceItems(arrayOf("MediZon tring", "Choose a phone ringtone…"), current) { d, which ->
                d.dismiss()
                if (which == 0) {
                    AppSettings.setRingtoneSetting(this, AppSettings.TRING)
                    refresh()
                } else {
                    openRingtonePicker()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE or RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Dose ringtone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_RINGTONE_URI)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AppSettings.ringtoneUri(this@SettingsActivity) ?: Settings.System.DEFAULT_RINGTONE_URI)
        }
        try { ringtonePicker.launch(intent) } catch (e: ActivityNotFoundException) { toast("No ringtone picker on this phone") }
    }

    private fun chooseLanguage() {
        val langs = AppSettings.LANGUAGES
        val labels = langs.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Language")
            .setSingleChoiceItems(labels, langs.indexOfFirst { it.first == AppSettings.language(this) }) { d, which ->
                AppSettings.setLanguage(this, langs[which].first); d.dismiss(); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseTextSize() {
        val sizes = AppSettings.TEXT_SIZES
        AlertDialog.Builder(this)
            .setTitle("Text size")
            .setSingleChoiceItems(sizes.map { it.second }.toTypedArray(), sizes.indexOfFirst { it.first == AppSettings.textSize(this) }) { d, which ->
                AppSettings.setTextSize(this, sizes[which].first); d.dismiss(); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseRingFor() {
        val values = AppSettings.RING_CHOICES
        val labels = values.map { AppSettings.ringLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Ring for")
            .setSingleChoiceItems(labels, values.indexOf(AppSettings.ringSeconds(this))) { d, which ->
                AppSettings.setRingSeconds(this, values[which]); d.dismiss(); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseSnooze() {
        val values = AppSettings.SNOOZE_CHOICES
        val labels = values.map { "$it minutes" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Snooze for")
            .setSingleChoiceItems(labels, values.indexOf(AppSettings.snoozeMinutes(this))) { d, which ->
                AppSettings.setSnoozeMinutes(this, values[which]); d.dismiss(); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else openAppNotificationSettings()
    }

    private fun openAppNotificationSettings() =
        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

    private fun syncNow() {
        if (!Store.isLinked(this)) { toast("Not linked. Sign in on the site inside the app first."); return }
        setSummary(syncRow, "Syncing…")
        val app = applicationContext
        Thread {
            val result = Sync.syncBlocking(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setSummary(syncRow, "Fetch the latest dose schedule")
                refresh()
                toast(when (result) {
                    Sync.Result.OK -> "Synced · ${AlarmScheduler.countScheduled(app)} alarms set"
                    Sync.Result.RETRY -> "Couldn't reach MediZon. Try again in a minute."
                    Sync.Result.UNLINKED -> "Your link expired. Sign in again in the app."
                })
            }
        }.start()
    }

    private fun open(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(fallback ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: ActivityNotFoundException) {
                toast("Couldn't open that screen")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
