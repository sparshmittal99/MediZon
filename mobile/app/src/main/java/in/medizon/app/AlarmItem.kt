package `in`.medizon.app

import android.content.Intent
import org.json.JSONObject
import java.time.OffsetDateTime

/** One alarm from GET /api/app/schedule (or a snooze / test alarm created on the phone). */
data class AlarmItem(
    val id: String,
    val kind: String,          // DOSE | REMINDER | TEST
    val planId: Long?,
    val reminderId: Long?,
    val at: String,            // ISO offset date-time as sent by the server
    val atMillis: Long,
    val title: String,
    val body: String,
    val path: String,
) {
    val isDose: Boolean get() = kind == "DOSE" && planId != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        if (planId != null) put("planId", planId)
        if (reminderId != null) put("reminderId", reminderId)
        put("at", at)
        put("atMillis", atMillis)
        put("title", title)
        put("body", body)
        put("path", path)
    }

    fun putInto(intent: Intent): Intent = intent.putExtra(EXTRA_JSON, toJson().toString())

    /** Same payload, ringing again at [whenMillis]; used for snooze. `at` stays the original dose time. */
    fun snoozed(whenMillis: Long): AlarmItem =
        copy(id = if (id.endsWith("-snooze")) id else "$id-snooze", atMillis = whenMillis)

    companion object {
        const val EXTRA_JSON = "in.medizon.app.ALARM_JSON"

        /** Parses an item from the server schedule. Returns null for anything malformed. */
        fun fromServer(o: JSONObject): AlarmItem? = try {
            val at = o.getString("at")
            AlarmItem(
                id = o.getString("id"),
                kind = o.optString("kind", "REMINDER").uppercase(),
                planId = if (o.has("planId") && !o.isNull("planId")) o.getLong("planId") else null,
                reminderId = if (o.has("reminderId") && !o.isNull("reminderId")) o.getLong("reminderId") else null,
                at = at,
                atMillis = OffsetDateTime.parse(at).toInstant().toEpochMilli(),
                title = o.optString("title", "MediZon"),
                body = o.optString("body", ""),
                path = o.optString("path", "/"),
            )
        } catch (e: Exception) {
            null
        }

        /** Parses an item we stored ourselves (has atMillis). */
        fun fromStored(o: JSONObject): AlarmItem? = try {
            AlarmItem(
                id = o.getString("id"),
                kind = o.optString("kind", "REMINDER"),
                planId = if (o.has("planId") && !o.isNull("planId")) o.getLong("planId") else null,
                reminderId = if (o.has("reminderId") && !o.isNull("reminderId")) o.getLong("reminderId") else null,
                at = o.optString("at", ""),
                atMillis = o.getLong("atMillis"),
                title = o.optString("title", "MediZon"),
                body = o.optString("body", ""),
                path = o.optString("path", "/"),
            )
        } catch (e: Exception) {
            null
        }

        fun fromIntent(intent: Intent?): AlarmItem? {
            val json = intent?.getStringExtra(EXTRA_JSON) ?: return null
            return try { fromStored(JSONObject(json)) } catch (e: Exception) { null }
        }
    }
}
