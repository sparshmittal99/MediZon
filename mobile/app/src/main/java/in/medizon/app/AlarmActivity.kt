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

        // Back never silently dismisses: it snoozes the alarm on screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                current?.let { act(AlarmActions.SNOOZE, it) }
            }
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
        AlarmService.listener = { render() }
        render()
        startAnimations()
    }

    override fun onStop() {
        AlarmService.listener = null
        animators.forEach { it.cancel() }
        animators.clear()
        super.onStop()
    }

    private fun items(): List<AlarmItem> {
        val ringing = AlarmService.activeAlarms()
        return (ringing + silentItems.filter { s -> ringing.none { it.id == s.id } }).sortedBy { it.atMillis }
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
        val m = Regex("""^(.*\S)\s*\(([^()]+)\)\s*$""").find(item.title)
        if (m != null) {
            titleView.text = m.groupValues[1]
            memberView.text = "for ${m.groupValues[2]}"
            memberView.visibility = View.VISIBLE
        } else {
            titleView.text = item.title
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
