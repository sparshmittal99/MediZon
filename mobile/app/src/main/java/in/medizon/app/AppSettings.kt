package `in`.medizon.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate

/** User preferences from the native Settings screen. Read at alarm fire time. */
object AppSettings {
    private const val FILE = "medizon_settings"
    private const val K_RING = "ringPhone"
    private const val K_RINGTONE = "ringtone"
    private const val K_VIBRATE = "vibrate"
    private const val K_RING_SECONDS = "ringSeconds"
    private const val K_SNOOZE_MIN = "snoozeMinutes"

    val RING_CHOICES = intArrayOf(30, 60, 120, 300)
    val SNOOZE_CHOICES = intArrayOf(5, 10, 15)

    private fun p(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ringPhone(c: Context) = p(c).getBoolean(K_RING, true)
    fun setRingPhone(c: Context, v: Boolean) = p(c).edit().putBoolean(K_RING, v).apply()

    /** null = system default ringtone. */
    fun ringtone(c: Context): Uri? = p(c).getString(K_RINGTONE, null)?.let { Uri.parse(it) }
    fun setRingtone(c: Context, uri: Uri?) = p(c).edit().putString(K_RINGTONE, uri?.toString()).apply()

    fun vibrate(c: Context) = p(c).getBoolean(K_VIBRATE, true)
    fun setVibrate(c: Context, v: Boolean) = p(c).edit().putBoolean(K_VIBRATE, v).apply()

    fun ringSeconds(c: Context) = p(c).getInt(K_RING_SECONDS, 60)
    fun setRingSeconds(c: Context, v: Int) = p(c).edit().putInt(K_RING_SECONDS, v).apply()

    fun snoozeMinutes(c: Context) = p(c).getInt(K_SNOOZE_MIN, 10)
    fun setSnoozeMinutes(c: Context, v: Int) = p(c).edit().putInt(K_SNOOZE_MIN, v).apply()

    // ---- appearance: "light" | "dark" | "system" ----
    val THEMES = setOf("light", "dark", "system")

    fun theme(c: Context): String = p(c).getString("theme", "light").takeIf { it in THEMES } ?: "light"

    /** Saves and applies. Returns false for an unknown value. Call on the main thread. */
    fun setTheme(c: Context, mode: String): Boolean {
        val m = mode.trim().lowercase()
        if (m !in THEMES) return false
        p(c).edit().putString("theme", m).apply()
        applyTheme(m)
        return true
    }

    fun applyTheme(mode: String) {
        val night = when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != night) AppCompatDelegate.setDefaultNightMode(night)
    }

    // ---- language / text size / motion (read by the website through the bridge) ----
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "en" to "English",
        "hi" to "हिन्दी · Hindi", "bn" to "বাংলা · Bengali", "mr" to "मराठी · Marathi",
        "te" to "తెలుగు · Telugu", "ta" to "தமிழ் · Tamil", "gu" to "ગુજરાતી · Gujarati",
        "kn" to "ಕನ್ನಡ · Kannada", "ml" to "മലയാളം · Malayalam", "pa" to "ਪੰਜਾਬੀ · Punjabi",
        "or" to "ଓଡ଼ିଆ · Odia", "as" to "অসমীয়া · Assamese", "ur" to "اردو · Urdu",
        "mai" to "मैथिली · Maithili", "bho" to "भोजपुरी · Bhojpuri", "gom" to "कोंकणी · Konkani",
        "ne" to "नेपाली · Nepali", "sa" to "संस्कृतम् · Sanskrit",
        "ar" to "العربية · Arabic", "zh-CN" to "中文 · Chinese", "fr" to "Français · French",
        "de" to "Deutsch · German", "ja" to "日本語 · Japanese", "pt" to "Português · Portuguese",
        "ru" to "Русский · Russian", "es" to "Español · Spanish",
    )

    val TEXT_SIZES: List<Pair<String, String>> = listOf(
        "sm" to "Small", "md" to "Default", "lg" to "Large", "xl" to "Extra large",
    )

    fun language(c: Context): String =
        p(c).getString("language", "en").takeIf { code -> LANGUAGES.any { it.first == code } } ?: "en"
    fun setLanguage(c: Context, code: String) = p(c).edit().putString("language", code).apply()
    fun languageLabel(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: "English"

    fun textSize(c: Context): String =
        p(c).getString("textSize", "md").takeIf { code -> TEXT_SIZES.any { it.first == code } } ?: "md"
    fun setTextSize(c: Context, code: String) = p(c).edit().putString("textSize", code).apply()
    fun textSizeLabel(code: String) = TEXT_SIZES.firstOrNull { it.first == code }?.second ?: "Default"

    fun reduceMotion(c: Context) = p(c).getBoolean("reduceMotion", false)
    fun setReduceMotion(c: Context, v: Boolean) = p(c).edit().putBoolean("reduceMotion", v).apply()

    fun ringLabel(seconds: Int) = if (seconds < 60) "$seconds seconds" else if (seconds == 60) "1 minute" else "${seconds / 60} minutes"
    fun snoozeLabel(c: Context) = "Snooze ${snoozeMinutes(c)} min"
}
