package `in`.medizon.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.Date

/**
 * Incoming-call style popup, shown over the lock screen. Shows one due alarm at a time ("1 of 3")
 * and closes after the last one is answered. Items come from AlarmService (ringing mode) and/or
 * from tapped notification-only reminders (silent mode, item passed in the intent).
 */
class AlarmActivity : AppCompatActivity() {

    private val silentItems = mutableListOf<AlarmItem>()
    private val animators = mutableListOf<android.animation.Animator>()
    private var current: AlarmItem? = null

    private lateinit var timeView: TextView
    private lateinit var pagerView: TextView
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var memberView: TextView
    private lateinit var snoozeButton: Button
    private lateinit var declineButton: ImageButton
    private lateinit var acceptButton: ImageButton
    private lateinit var declineLabel: TextView
    private lateinit var acceptLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)
        timeView = findViewById(R.id.alarmTime)
        pagerView = findViewById(R.id.alarmPager)
        titleView = findViewById(R.id.alarmTitle)
        bodyView = findViewById(R.id.alarmBody)
        memberView = findViewById(R.id.alarmMember)
        snoozeButton = findViewById(R.id.btnSnooze)
        declineButton = findViewById(R.id.btnDecline)
        acceptButton = findViewById(R.id.btnAccept)
        declineLabel = findViewById(R.id.labelDecline)
        acceptLabel = findViewById(R.id.labelAccept)

        addSilentItem(intent)

        // Kept while stopped too: the power button stops this activity before the service hears screen-off.
        val close: () -> Unit = { if (!isFinishing) finish() }
        AlarmService.closePopup = close
        closeHook = close

        // Back: stop ringing and close; the alarm stays in a silent notification with its actions.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        addSilentItem(intent)
        render()
    }

    private fun addSilentItem(intent: Intent?) {
        val item = AlarmItem.fromIntent(intent) ?: return
        if (silentItems.none { it.id == item.id }) silentItems += item
    }

    override fun onStart() {
        super.onStart()
        started = true
        handler.removeCallbacks(leaveIfStillAwake)
        AlarmService.listener = { render() }
        render()
        startAnimations()
    }

    /** Volume keys while ringing: stop the ring, keep the alarm in a notification for 2 min (AlarmService). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (AlarmService.isRinging() && keyCode in SILENCE_KEYS) {
            AlarmService.silenceByButton(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Home / Recents. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        leave()
    }

    private fun leave() {
        if (isFinishing) return
        AlarmService.silence(this)
        finish()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var started = false

    /**
     * Left the foreground some other way (another app came up). Ignore stops caused by the screen turning
     * off: the power button is handled by AlarmService (ring stops, notification kept for 2 minutes), and a
     * just-fired alarm must never be silenced by the display going to sleep. Many phones still report the
     * screen as on when onStop runs for a power press, so wait a moment before deciding.
     */
    private val leaveIfStillAwake = Runnable {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!started && !isFinishing && !isDestroyed && pm.isInteractive) leave()
    }

    override fun onStop() {
        started = false
        if (!isFinishing && !isChangingConfigurations) handler.postDelayed(leaveIfStillAwake, 800)
        AlarmService.listener = null
        animators.forEach { it.cancel() }
        animators.clear()
        super.onStop()
    }

    private var closeHook: (() -> Unit)? = null

    override fun onDestroy() {
        handler.removeCallbacks(leaveIfStillAwake)
        if (AlarmService.closePopup === closeHook) AlarmService.closePopup = null
        super.onDestroy()
    }

    private fun items(): List<AlarmItem> {
        val ringing = AlarmService.activeAlarms(this)
        silentItems.removeAll { AlarmService.isDismissed(it) } // answered elsewhere
        return (ringing + silentItems.filter { s -> ringing.none { it.id == s.id } })
            .sortedByDescending { it.sortKey } // newest scheduled dose first: "1 of N" is the latest
    }

    private fun render() {
        val all = items()
        val item = all.firstOrNull()
        if (item == null) {
            finishAndRemoveTask()
            return
        }
        current = item
        timeView.text = DateFormat.getTimeFormat(this).format(Date(item.atMillis))
        pagerView.visibility = if (all.size > 1) View.VISIBLE else View.GONE
        pagerView.text = "1 of ${all.size}"

        // "Metformin (Asha)" -> title "Metformin", member line "for Asha"
        titleView.text = item.medicineName
        val member = item.memberName
        if (member != null) {
            memberView.text = "for $member"
            memberView.visibility = View.VISIBLE
        } else {
            memberView.visibility = View.GONE
        }
        bodyView.text = item.body.replace(" · ", "\n")
        bodyView.visibility = if (item.body.isBlank()) View.GONE else View.VISIBLE

        snoozeButton.text = AppSettings.snoozeLabel(this)
        snoozeButton.setOnClickListener { act(AlarmActions.SNOOZE, item) }
        if (item.isDose) {
            declineLabel.setText(R.string.skip)
            acceptLabel.setText(R.string.taken)
            declineButton.contentDescription = getString(R.string.skip)
            acceptButton.contentDescription = getString(R.string.taken)
            declineButton.setOnClickListener { act(AlarmActions.SKIP, item) }
            acceptButton.setOnClickListener { act(AlarmActions.TAKEN, item) }
        } else {
            declineLabel.setText(R.string.dismiss)
            acceptLabel.setText(R.string.ok)
            declineButton.contentDescription = getString(R.string.dismiss)
            acceptButton.contentDescription = getString(R.string.ok)
            declineButton.setOnClickListener { act(AlarmActions.DISMISS, item) }
            acceptButton.setOnClickListener { act(AlarmActions.DISMISS, item) }
        }
    }

    private fun act(action: String, item: AlarmItem) {
        silentItems.removeAll { it.id == item.id }
        val msg = AlarmActions.perform(this, action, item)
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        render() // next alarm, or finish after the last
    }

    private companion object {
        val SILENCE_KEYS = setOf(
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
        )
    }

    private fun startAnimations() {
        if (animators.isNotEmpty()) return
        val rings = listOf(findViewById<View>(R.id.ring1), findViewById<View>(R.id.ring2))
        rings.forEachIndexed { i, ring ->
            val sx = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.7f)
            val sy = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.7f)
            val a = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.9f, 0f)
            listOf(sx, sy, a).forEach {
                it.repeatCount = ValueAnimator.INFINITE
                it.duration = 1800
                it.startDelay = i * 900L
                it.interpolator = DecelerateInterpolator()
            }
            AnimatorSet().apply { playTogether(sx, sy, a); start() }.also { animators += it }
        }
        // Gentle bounce on the accept button.
        val bx = ObjectAnimator.ofFloat(acceptButton, View.SCALE_X, 1f, 1.1f)
        val by = ObjectAnimator.ofFloat(acceptButton, View.SCALE_Y, 1f, 1.1f)
        val ty = ObjectAnimator.ofFloat(acceptButton, View.TRANSLATION_Y, 0f, -6f * resources.displayMetrics.density)
        listOf(bx, by, ty).forEach {
            it.repeatCount = ValueAnimator.INFINITE
            it.repeatMode = ValueAnimator.REVERSE
            it.duration = 650
            it.interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply { playTogether(bx, by, ty); start() }.also { animators += it }
    }
}
