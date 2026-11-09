package `in`.medizon.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Sync {
    private const val TAG = "MediZonSync"
    private const val WORK_NOW = "medizon-sync-now"
    private const val WORK_PERIODIC = "medizon-sync-periodic"
    private const val WORK_DOSES = "medizon-doses"

    @Volatile private var lastSyncRequest = 0L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Enqueue a sync right away (runs as soon as there is a network). A request while a sync is already running
     * queues one more after it, so a medicine saved mid-sync still reaches the alarms.
     */
    fun syncNow(c: Context, replace: Boolean = false) {
        if (!Store.isLinked(c)) return
        lastSyncRequest = SystemClock.elapsedRealtime()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(
            WORK_NOW, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, req
        )
    }

    /** Same as [syncNow] but ignores calls within 5 s of the previous one (pages call it on every load). */
    fun syncNowThrottled(c: Context) {
        if (SystemClock.elapsedRealtime() - lastSyncRequest < 5_000 && lastSyncRequest != 0L) return
        syncNow(c)
    }

    fun ensurePeriodic(c: Context) {
        if (!Store.isLinked(c)) return
        val req = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun flushDosesSoon(c: Context) {
        val req = OneTimeWorkRequestBuilder<DoseWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK_DOSES, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** What `unlink()` does; also used when the server answers 401/403. */
    fun unlink(c: Context) {
        Push.forget(c, Store.token(c)) // before the sync token is cleared
        AlarmScheduler.cancelEverything(c)
        Store.clearLink(c)
        val wm = WorkManager.getInstance(c)
        wm.cancelUniqueWork(WORK_PERIODIC)
        wm.cancelUniqueWork(WORK_NOW)
        wm.cancelUniqueWork(WORK_DOSES)
        lastSyncRequest = 0L
    }

    enum class Result { OK, RETRY, UNLINKED }

    /** Sends queued dose answers. Blocking. */
    fun flushDoses(c: Context): Result {
        val token = Store.token(c) ?: return Result.OK
        for (dose in Store.doseQueue(c)) {
            val res = try {
                Api.postDose(c, token, dose)
            } catch (e: IOException) {
                Log.w(TAG, "dose post failed, will retry", e)
                return Result.RETRY
            }
            when {
                res.code in 200..299 -> Store.removeDose(c, dose)
                res.code == 401 || res.code == 403 -> { unlink(c); return Result.UNLINKED }
                res.code in 400..499 -> Store.removeDose(c, dose) // e.g. plan deleted: drop it
                else -> return Result.RETRY
            }
        }
        return Result.OK
    }

    /** Full sync: send queued doses, fetch schedule, diff alarms. Blocking. */
    fun syncBlocking(c: Context): Result {
        val doses = flushDoses(c)
        if (doses == Result.UNLINKED) return doses
        val token = Store.token(c) ?: return Result.OK
        val res = try {
            Api.getSchedule(c, token)
        } catch (e: IOException) {
            Log.w(TAG, "schedule fetch failed", e)
            return Result.RETRY
        }
        if (res.code == 401 || res.code == 403) { unlink(c); return Result.UNLINKED }
        if (res.code !in 200..299) return Result.RETRY
        var notices = emptyList<Notifications.Notice>()
        var checks = emptyList<AlarmItem>()
        val items = try {
            val json = JSONObject(res.body)
            json.optJSONArray("notices")?.let { n ->
                notices = (0 until n.length()).mapNotNull { i ->
                    val o = n.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id", "")
                    if (id.isBlank()) null
                    else Notifications.Notice(id, o.optString("title", "MediZon"), o.optString("body", ""), o.optString("path", "/"))
                }
            }
            // Other members' doses: they don't ring here, only "isn't marked yet" 30 min later if nobody marks them
            json.optJSONArray("checks")?.let { a ->
                checks = (0 until a.length()).mapNotNull { AlarmItem.fromServer(a.getJSONObject(it)) }.filter { it.isDose }
            }
            val arr = json.optJSONArray("alarms")
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { AlarmItem.fromServer(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "bad schedule json", e)
            return Result.RETRY
        }
        // Token may have been unlinked while we were waiting on the network.
        if (Store.token(c) != token) return Result.OK
        AlarmScheduler.applySchedule(c, items.distinctBy { it.id })
        AlarmScheduler.applyChecks(c, checks.distinctBy { it.id })
        Store.setLastSync(c, System.currentTimeMillis())
        Notifications.showNotices(c, notices)
        return if (doses == Result.RETRY) Result.RETRY else Result.OK
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (Sync.syncBlocking(applicationContext)) {
            Sync.Result.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}

class DoseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        when (Sync.flushDoses(applicationContext)) {
            Sync.Result.RETRY -> Result.retry()
            else -> Result.success()
        }
    }
}
