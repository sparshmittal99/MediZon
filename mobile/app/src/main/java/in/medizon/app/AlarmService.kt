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
 * Foreground service that rings: looping alarm-stream audio, repeating vibration, a full-screen
 * notification. Holds every alarm that is currently ringing (several can fire at the same minute).
 * All state is touched on the main thread only.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "MediZonAlarmService"
        private const val ACTION_FIRE = "in.medizon.app.action.SERVICE_FIRE"

        @Volatile private var instance: AlarmService? = null
        private val active = mutableListOf<AlarmItem>()

        /** Called by AlarmActivity to re-render when the list changes. Main thread only. */
        var listener: (() -> Unit)? = null

        fun activeAlarms(): List<AlarmItem> = active.toList()

        fun fire(c: Context, item: AlarmItem) {
            val i = Intent(c, AlarmService::class.java).setAction(ACTION_FIRE)
            item.putInto(i)
            ContextCompat.startForegroundService(c, i)
        }

        /** The user answered [id]: drop it; stop ringing when nothing is left. */
        fun resolve(c: Context, id: String) {
            val svc = instance
            if (svc != null) svc.remove(id) else {
                active.removeAll { it.id == id }
                NotificationManagerCompat.from(c).cancel(Notifications.RINGING_ID)
                listener?.invoke()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val timeout = Runnable { onTimeout() }

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
        val item = AlarmItem.fromIntent(intent)
        if (item != null) {
            active.removeAll { it.id == item.id }
            active.add(item)
            active.sortBy { it.atMillis }
        }
        if (active.isEmpty()) {
            // Must still satisfy the startForegroundService contract before stopping.
            goForeground(
                NotificationCompat.Builder(this, Notifications.CHANNEL_CALLS)
                    .setSmallIcon(R.drawable.ic_stat_capsule)
                    .setContentTitle(getString(R.string.app_name))
                    .setSilent(true)
                    .build()
            )
            stopAll()
            return START_NOT_STICKY
        }
        goForeground(Notifications.ringing(this, active))
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

    private fun remove(id: String) {
        active.removeAll { it.id == id }
        if (active.isEmpty()) {
            stopAll()
        } else {
            try {
                NotificationManagerCompat.from(this).notify(Notifications.RINGING_ID, Notifications.ringing(this, active))
            } catch (e: SecurityException) { /* notifications off */ }
        }
        listener?.invoke()
    }

    private fun onTimeout() {
        active.forEach { Notifications.missed(this, it) }
        active.clear()
        stopAll()
        listener?.invoke()
    }

    private fun startRinging() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediZon:alarm").apply {
                setReferenceCounted(false)
                acquire(AppSettings.ringSeconds(this@AlarmService) * 1000L + 60_000)
            }
        }
        if (player == null) {
            requestFocus()
            // Chosen ringtone, else the phone's ringtone, else the alarm / notification sound.
            val candidates = listOfNotNull(
                AppSettings.ringtone(this),
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE),
                Settings.System.DEFAULT_RINGTONE_URI,
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
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
        handler.removeCallbacks(timeout)
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopRinging()
        instance = null
        super.onDestroy()
    }
}
