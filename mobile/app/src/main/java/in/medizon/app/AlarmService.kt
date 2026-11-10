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

        /** Set by AlarmActivity: a phone button stopped the ring, so the popup closes. Main thread only. */
        var closePopup: (() -> Unit)? = null

        /**
         * How long an unanswered alarm stays in the notification after the ring stops (Home, Back, a phone
         * button or the ring time running out). It can't be cleared before then; afterwards it goes away and
         * the 30-minute missed-dose reminder takes over.
         */
        private const val KEEP_MS = 2 * 60 * 1000L
        /** A dismissal this close to the end counts as the 2 minutes being over. */
        private const val KEEP_SLACK_MS = 5_000L
        /** How often the 2-minute notification is checked for, and put back if it was swiped or cleared. */
        private const val KEEP_CHECK_MS = 1_000L

        /**
         * The service holding the 2-minute notification. It stays in the foreground until the 2 minutes are up,
         * so the notification can't be cleared for good: phones like Xiaomi (MIUI/HyperOS) let a swipe or "Clear
         * all" remove it and then don't wake a closed app to put it back, so the app has to still be running.
         */
        @Volatile private var keeper: AlarmService? = null

        /** Screen-off right after the ring starts can be the device settling, not the power button. */
        private const val SCREEN_OFF_GRACE_MS = 1_500L

        /**
         * Volume / power pressed while ringing: stop the ring and close the popup, keep the alarms in a
         * silent notification for 2 minutes (tap = popup again, no ringing), then forget them.
         */
        fun silenceByButton(c: Context) {
            instance?.buttonSilence()
        }

        /** The 2 minutes are over: drop the alarms that were in the notification. */
        fun expire(c: Context, ids: Set<String>) {
            val list = due(c)
            if (!list.removeAll { it.id in ids }) return
            saveDue(c)
            val svc = instance
            if (svc != null) {
                svc.afterRemove()
            } else {
                showPendingOrCancel(c)
                listener?.invoke()
            }
        }

        private fun scheduleExpiry(c: Context, ids: List<String>, at: Long) {
            val op = android.app.PendingIntent.getBroadcast(
                c, 7004,
                Intent(c, AlarmActionReceiver::class.java)
                    .setAction(AlarmActions.EXPIRE_DUE)
                    .putExtra(AlarmActions.EXTRA_IDS, ids.toTypedArray()),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, op)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, op)
            }
        }

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

            AlarmScheduler.cancelFollowUps(c, planId, t)
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

        /**
         * The unanswered-alarm notification was dismissed (swipe on Android 14+, which no longer lets apps
         * block it, or its 2-minute timeout). Within the 2 minutes it is put straight back; after that the
         * alarms are forgotten.
         */
        fun clearDue(c: Context) {
            if (due(c).isNotEmpty() && Store.pendingUntil(c) - System.currentTimeMillis() > KEEP_SLACK_MS) {
                showPendingOrCancel(c)
                return
            }
            due(c).clear()
            saveDue(c)
            keeper?.endKeep()
            instance?.stopAll()
            NotificationManagerCompat.from(c).cancel(Notifications.RINGING_ID)
            listener?.invoke()
        }

        private fun showPendingOrCancel(c: Context) {
            val list = due(c)
            val nm = NotificationManagerCompat.from(c)
            if (list.isEmpty()) {
                keeper?.endKeep()   // answered or expired: the held notification goes with it
                nm.cancel(Notifications.RINGING_ID)
            } else {
                val left = Store.pendingUntil(c) - System.currentTimeMillis()
                try {
                    nm.notify(Notifications.RINGING_ID, Notifications.pending(c, list, left.takeIf { it > 0 }))
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
        if (item != null && keeper === this) {
            keeper = null              // a new ring replaces the 2-minute notification
            handler.removeCallbacks(keepCheck)
        }
        val list = due(this)
        if (item != null) {
            Store.setPendingUntil(this, 0L) // a new ring: its notification has no 2-minute limit
            list.removeAll { it.id == item.id }
            list.add(item)
            list.sortByDescending { it.sortKey } // newest scheduled dose first
            saveDue(this)
        }
        if (item == null && keeper === this && list.isNotEmpty()) {
            // Still holding the 2-minute notification: stay as we are
            instance = null
            val left = Store.pendingUntil(this) - System.currentTimeMillis()
            goForeground(Notifications.pending(this, list, left.coerceAtLeast(1L)))
            return START_NOT_STICKY
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

    private fun buttonSilence() {
        if (instance !== this) return
        silenceNow() // posts the notification with the 2-minute limit
        closePopup?.invoke()
    }

    private var buttonReceiver: android.content.BroadcastReceiver? = null
    private var ringStartedAt = 0L

    /** Power (screen off) and volume presses while ringing, also when the popup isn't on screen. */
    private fun watchButtons() {
        if (buttonReceiver != null) return
        ringStartedAt = android.os.SystemClock.elapsedRealtime()
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF &&
                    android.os.SystemClock.elapsedRealtime() - ringStartedAt < SCREEN_OFF_GRACE_MS
                ) return
                buttonSilence()
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        try {
            ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_EXPORTED)
            buttonReceiver = r
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch phone buttons", e)
        }
    }

    private fun unwatchButtons() {
        buttonReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        buttonReceiver = null
    }

    /** Ring time over, or the user left the popup: quiet now, keep a silent notification. */
    private fun silenceNow() {
        val wasRinging = instance === this
        if (wasRinging) instance = null
        handler.removeCallbacks(timeout)
        stopRinging()
        val ids = due(this).map { it.id }
        if (wasRinging && ids.isNotEmpty()) {
            // However the ring stopped: the alarm stays in the notification for 2 minutes, then goes.
            val until = System.currentTimeMillis() + KEEP_MS
            Store.setPendingUntil(this, until)
            scheduleExpiry(this, ids, until)
            startKeep(KEEP_MS)
            listener?.invoke()
            return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        showPendingOrCancel(this)
        stopSelf()
        listener?.invoke()
    }

    private fun startRinging() {
        watchButtons()
        val ringMs = AppSettings.ringSeconds(this) * 1000L
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediZon:alarm").apply {
                setReferenceCounted(false)
                acquire(ringMs + 60_000)
            }
        }
        // "Ring phone" off: the same popup and notification, without the ringtone (vibration follows its own setting)
        if (player == null && AppSettings.ringPhone(this)) {
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
        unwatchButtons()
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        focusRequest?.let { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Holds the silent 2-minute notification as this service's foreground notification: the app keeps running,
     * and every second the notification is put back if it was swiped away or cleared.
     */
    private fun startKeep(leftMs: Long) {
        keeper = this
        goForeground(Notifications.pending(this, due(this), leftMs))
        handler.removeCallbacks(keepCheck)
        handler.postDelayed(keepCheck, KEEP_CHECK_MS)
    }

    private val keepCheck = object : Runnable {
        override fun run() {
            val svc = this@AlarmService
            if (keeper !== svc) return
            val list = due(svc)
            val left = Store.pendingUntil(svc) - System.currentTimeMillis()
            if (list.isEmpty() || left <= 0) {
                endKeep()          // the 2 minutes are up (the expiry alarm forgets the alarms)
                return
            }
            if (left > KEEP_SLACK_MS) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val showing = try {
                    nm.activeNotifications.any { it.id == Notifications.RINGING_ID }
                } catch (e: Exception) { true }
                if (!showing) {
                    try {
                        goForeground(Notifications.pending(svc, list, left))
                    } catch (e: Exception) {
                        try { nm.notify(Notifications.RINGING_ID, Notifications.pending(svc, list, left)) } catch (e2: Exception) { }
                    }
                }
            }
            handler.postDelayed(this, KEEP_CHECK_MS)
        }
    }

    /** The 2 minutes are over, or the alarms were answered: let the notification and the service go. */
    private fun endKeep() {
        if (keeper !== this) return
        keeper = null
        handler.removeCallbacks(keepCheck)
        if (instance == null) {    // not ringing again in the meantime
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopAll() {
        if (instance === this) instance = null
        if (keeper === this) keeper = null
        handler.removeCallbacks(keepCheck)
        handler.removeCallbacks(timeout)
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(keepCheck)
        if (keeper === this) keeper = null
        stopRinging()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
