package `in`.medizon.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that rings: looping alarm-stream audio, call-like vibration, an incoming-call
 * notification. The list of due (unanswered) alarms is persisted, so after the ring stops (timeout,
 * Back/Home on the popup) a silent notification keeps them and the popup can be reopened.
 * All state is touched on the main thread only.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "MediZonAlarmService"
        private const val ACTION_FIRE = "in.medizon.app.action.SERVICE_FIRE"
        private const val DUE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        @Volatile private var instance: AlarmService? = null
        private var dueCache: MutableList<AlarmItem>? = null

        /** Called by AlarmActivity to re-render when the list changes. Main thread only. */
        var listener: (() -> Unit)? = null

        private fun due(c: Context): MutableList<AlarmItem> {
            dueCache?.let { return it }
            val cutoff = System.currentTimeMillis() - DUE_MAX_AGE_MS
            return Store.dueAlarms(c).filter { it.atMillis > cutoff }.sortedByDescending { it.sortKey }
                .toMutableList().also { dueCache = it }
        }

        private fun saveDue(c: Context) = Store.saveDueAlarms(c, due(c))

        /** Doses answered (here or on another device) in this process; AlarmActivity hides them. */
        private val dismissedDoses = mutableSetOf<String>()

        fun isDismissed(item: AlarmItem): Boolean {
            val planId = item.planId ?: return false
            val t = item.doseInstant ?: return false
            return "$planId@$t" in dismissedDoses
        }

        /**
         * A dose was answered (Taken/Skipped) on this phone, another phone or the website: remove every
         * copy of it everywhere: due/ringing list, snoozes, future alarm, notifications, popup.
         * Reads everything from the stores, so it works in a freshly started process. Main thread.
         */
        fun dismissDose(c: Context, planId: Long, at: String) {
            val t = try { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() } catch (e: Exception) { return }
            dismissedDoses += "$planId@$t"
            val removed = mutableListOf<AlarmItem>()

            val list = due(c)
            removed += list.filter { it.isSameDose(planId, t) }
            if (list.removeAll { it.isSameDose(planId, t) }) saveDue(c)

            Store.oneOffs(c).filter { it.isSameDose(planId, t) }.forEach {
                AlarmScheduler.cancel(c, it.id)
                Store.removeOneOff(c, it.id)
                removed += it
            }

            synchronized(Store.lock) {
                val schedule = Store.schedule(c)
                val hit = schedule.filter { it.isSameDose(planId, t) }
                if (hit.isNotEmpty()) {
                    hit.forEach { AlarmScheduler.cancel(c, it.id) }
                    Store.saveSchedule(c, schedule.filter { !it.isSameDose(planId, t) })
                    removed += hit
                }
            }

            removed.forEach { Notifications.cancelReminder(c, it) }
            Notifications.cancelDoseNotifications(c, planId, t)

            val svc = instance
            if (svc != null) {
                svc.afterRemove() // stops ringing when nothing is left, else refreshes the notification
            } else {
                showPendingOrCancel(c)
                listener?.invoke()
            }
        }

        /** Alarms that went off and are not answered yet (ringing or silenced). */
        fun activeAlarms(c: Context): List<AlarmItem> = due(c).toList()

        fun isRinging(): Boolean = instance?.player != null || instance?.vibrator != null

        fun fire(c: Context, item: AlarmItem) {
            val i = Intent(c, AlarmService::class.java).setAction(ACTION_FIRE)
            item.putInto(i)
            ContextCompat.startForegroundService(c, i)
        }

        /** The user answered [id]: drop it; stop ringing when nothing is left. */
        fun resolve(c: Context, id: String) {
            val list = due(c)
            list.removeAll { it.id == id }
            saveDue(c)
            val svc = instance
            if (svc != null) {
                svc.afterRemove()
            } else {
                showPendingOrCancel(c)
                listener?.invoke()
            }
        }

        /** Stop sound + vibration now, keep the alarms in a silent notification. */
        fun silence(c: Context) {
            instance?.silenceNow() ?: showPendingOrCancel(c)
        }

        /** The silent notification was swiped away: forget the unanswered alarms. */
        fun clearDue(c: Context) {
            due(c).clear()
            saveDue(c)
            instance?.stopAll()
            NotificationManagerCompat.from(c).cancel(Notifications.RINGING_ID)
            listener?.invoke()
        }

        private fun showPendingOrCancel(c: Context) {
            val list = due(c)
            val nm = NotificationManagerCompat.from(c)
            if (list.isEmpty()) {
                nm.cancel(Notifications.RINGING_ID)
            } else {
                try {
                    nm.notify(Notifications.RINGING_ID, Notifications.pending(c, list))
                } catch (e: SecurityException) { /* notifications off */ }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val timeout = Runnable { silenceNow() }

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val item = AlarmItem.fromIntent(intent)
        val list = due(this)
        if (item != null) {
            list.removeAll { it.id == item.id }
            list.add(item)
            list.sortByDescending { it.sortKey } // newest scheduled dose first
            saveDue(this)
        }
        if (item == null || list.isEmpty()) {
            // Must still satisfy the startForegroundService contract before stopping.
            goForeground(
                NotificationCompat.Builder(this, Notifications.CHANNEL_CALLS)
                    .setSmallIcon(R.drawable.ic_stat_capsule)
                    .setContentTitle(getString(R.string.app_name))
                    .setSilent(true)
                    .build()
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            showPendingOrCancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(Notifications.ringing(this, list))
        startRinging()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, AppSettings.ringSeconds(this) * 1000L)
        listener?.invoke()
        return START_NOT_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, Notifications.RINGING_ID, n, type)
    }

    private fun afterRemove() {
        val list = due(this)
        if (list.isEmpty()) {
            stopAll()
        } else {
            try {
                NotificationManagerCompat.from(this).notify(Notifications.RINGING_ID, Notifications.ringing(this, list))
            } catch (e: SecurityException) { /* notifications off */ }
        }
        listener?.invoke()
    }

    /** Ring time over, or the user left the popup: quiet now, keep a silent notification. */
    private fun silenceNow() {
        if (instance === this) instance = null
        handler.removeCallbacks(timeout)
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        showPendingOrCancel(this)
        stopSelf()
        listener?.invoke()
    }

    private fun startRinging() {
        val ringMs = AppSettings.ringSeconds(this) * 1000L
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediZon:alarm").apply {
                setReferenceCounted(false)
                acquire(ringMs + 60_000)
            }
        }
        if (player == null) {
            requestFocus()
            // Chosen phone ringtone (if any) -> bundled "MediZon tring" -> phone ringtone / alarm sound.
            val candidates = listOfNotNull(
                AppSettings.chosenSoundUri(this),
                AppSettings.celestaUri(this),
                AppSettings.tringUri(this),
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE),
                Settings.System.DEFAULT_RINGTONE_URI,
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
                Settings.System.DEFAULT_ALARM_ALERT_URI,
            ).distinct()
            for (uri in candidates) {
                player = tryPlay(uri)
                if (player != null) break
            }
            if (player == null) Log.w(TAG, "no playable alarm sound found")
        }
        if (vibrator == null && AppSettings.vibrate(this)) {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (v.hasVibrator()) {
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 400, 1000, 1600), 0), alarmAttrs)
                vibrator = v
            }
        }
    }

    private fun tryPlay(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(alarmAttrs)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            Log.w(TAG, "cannot play $uri", e)
            mp.release()
            null
        }
    }

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttrs)
            .setOnAudioFocusChangeListener { }
            .build()
        try { am.requestAudioFocus(req) } catch (e: Exception) { }
        focusRequest = req
    }

    private fun stopRinging() {
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        focusRequest?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun stopAll() {
        if (instance === this) instance = null
        handler.removeCallbacks(timeout)
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopRinging()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
