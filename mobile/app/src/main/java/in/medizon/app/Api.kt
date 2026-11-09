package `in`.medizon.app

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HttpURLConnection client for the two app endpoints. Blocking: call off the main thread. */
object Api {
    class Response(val code: Int, val body: String)

    const val UA_TOKEN = "MediZonApp/1.0"

    /**
     * Added to the user agent: the app token and this phone's name, so the account's device list says
     * "Sparsh's Galaxy S23" instead of "MediZon app on Android" (WebViews no longer send the model).
     */
    fun uaSuffix(c: Context): String =
        "$UA_TOKEN MediZonDevice/" + java.net.URLEncoder.encode(deviceName(c), "UTF-8").replace("+", "%20")

    /** The name the owner gave the phone (Settings > About phone), else maker and model: "Samsung SM-S911B". */
    fun deviceName(c: Context): String {
        val given = try {
            android.provider.Settings.Global.getString(c.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        } catch (e: Exception) { null }
        if (!given.isNullOrBlank()) return given.trim().take(40)
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return name.trim().ifBlank { "Android phone" }.take(40)
    }

    fun userAgent(c: Context): String {
        Store.userAgent(c)?.let { return it }
        val base = try {
            WebSettings.getDefaultUserAgent(c)
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
        }
        return "$base ${uaSuffix(c)}"
    }

    @Throws(IOException::class)
    fun getSchedule(c: Context, token: String): Response = request(c, "GET", "/api/app/schedule", token, null)

    @Throws(IOException::class)
    fun postDose(c: Context, token: String, dose: JSONObject): Response =
        request(c, "POST", "/api/app/doses", token, dose.toString())

    @Throws(IOException::class)
    fun postPushToken(c: Context, token: String, pushToken: String): Response =
        request(c, "POST", "/api/app/push-token", token, JSONObject().put("token", pushToken).toString())

    private fun request(c: Context, method: String, path: String, token: String, json: String?): Response {
        val conn = URL(BuildConfig.BASE_URL + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", userAgent(c))
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
