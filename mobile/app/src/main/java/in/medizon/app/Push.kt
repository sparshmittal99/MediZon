package `in`.medizon.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Firebase Cloud Messaging, configured from firebase.properties. Everything is a no-op when it's blank. */
object Push {
    private const val TAG = "MediZonPush"
    private const val WORK = "medizon-push-token"

    val configured: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    fun init(c: Context) {
        if (!configured) return
        try {
            if (FirebaseApp.getApps(c).isEmpty()) {
                FirebaseApp.initializeApp(
                    c,
                    FirebaseOptions.Builder()
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase init failed; push disabled", e)
        }
    }

    private fun ready(c: Context) = configured && FirebaseApp.getApps(c).isNotEmpty()

    /** Fetch the FCM token and send it if it changed / was never sent ([force] = always send). */
    fun ensureToken(c: Context, force: Boolean = false) {
        if (!ready(c) || !Store.isLinked(c)) return
        val app = c.applicationContext
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank() && (force || token != Store.lastPushToken(app))) send(app, token)
            }.addOnFailureListener { Log.w(TAG, "getToken failed", it) }
        } catch (e: Exception) {
            Log.w(TAG, "getToken failed", e)
        }
    }

    /** Queue POST /api/app/push-token (retried until there is a network). */
    fun send(c: Context, token: String) {
        if (!Store.isLinked(c)) return
        val req = OneTimeWorkRequestBuilder<PushTokenWorker>()
            .setInputData(workDataOf("token" to token))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Called by unlink() before the sync token is cleared: tell the server to forget this device
     * (best effort, own thread), and drop the FCM token.
     */
    fun forget(c: Context, syncToken: String?) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        Store.setLastPushToken(c, null)
        if (syncToken != null) {
            val app = c.applicationContext
            Thread {
                try { Api.postPushToken(app, syncToken, "") } catch (e: Exception) { }
            }.start()
        }
        if (ready(c)) {
            try { FirebaseMessaging.getInstance().deleteToken() } catch (e: Exception) { }
        }
    }
}

class PushTokenWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val token = inputData.getString("token") ?: return@withContext Result.success()
        val sync = Store.token(c) ?: return@withContext Result.success()
        val res = try {
            Api.postPushToken(c, sync, token)
        } catch (e: IOException) {
            return@withContext if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
        when {
            res.code in 200..299 -> { Store.setLastPushToken(c, token); Result.success() }
            res.code == 401 || res.code == 403 -> { Sync.unlink(c); Result.success() }
            res.code in 400..499 -> Result.failure()
            else -> if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }
}

/** Receives data-only FCM messages and FCM token changes. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (Store.isLinked(this)) Push.send(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        if (d.isEmpty()) return
        // A dose was answered on another phone or the website: stop it here too. No notification.
        if (d["type"] == "DOSE_DONE") {
            val planId = d["planId"]?.trim()?.toLongOrNull()
            val at = d["at"]?.trim()
            if (planId != null && !at.isNullOrEmpty()) {
                val app = applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).post { AlarmService.dismissDose(app, planId, at) }
            }
            return
        }
        // A medicine or reminder changed (on any device): fetch the schedule now so the alarms are right
        if (d["type"] == "SYNC") {
            Sync.syncNow(applicationContext)
            return
        }
        // Shown on the phone whether MediZon is open or closed, like the in-app notification.
        val nid = d["nid"]?.takeIf { it.isNotBlank() } ?: (d["type"] + ":" + d["title"] + ":" + d["body"])
        Notifications.push(this, nid, d["title"] ?: "", d["body"] ?: "", d["link"] ?: "")
    }
}
