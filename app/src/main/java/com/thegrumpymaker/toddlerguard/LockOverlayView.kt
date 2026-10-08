package com.thegrumpymaker.toddlerguard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Full-screen, transparent layer that swallows every touch and key event so the app underneath
 * keeps playing but cannot be interacted with. The only interactive element is a small lock badge:
 * holding it for [HOLD_TO_UNLOCK_MS] asks for the parent's fingerprint via [onUnlockRequested].
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class LockOverlayView(
    context: Context,
    private val onUnlockRequested: () -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val badge: ImageView
    private val hint: TextView

    private val unlockRunnable = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        resetBadge()
        onUnlockRequested()
    }
    private val hideHintRunnable = Runnable {
        hint.animate().alpha(0f).setDuration(600).start()
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = true
        isFocusableInTouchMode = true

        badge = ImageView(context).apply {
            setImageResource(R.drawable.ic_lock)
            setBackgroundResource(R.drawable.badge_background)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            alpha = 0.6f
            contentDescription = context.getString(R.string.overlay_badge_desc)
            setOnTouchListener { _, event -> onBadgeTouch(event) }
        }
        addView(
            badge,
            LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(40)
                marginEnd = dp(16)
            },
        )

        hint = TextView(context).apply {
            text = context.getString(R.string.overlay_hint)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.hint_background)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        addView(
            hint,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    /** Briefly shows the "screen locked" hint, e.g. right after locking or after a failed unlock. */
    fun flashHint() {
        handler.removeCallbacks(hideHintRunnable)
        hint.animate().cancel()
        hint.alpha = 1f
        handler.postDelayed(hideHintRunnable, HINT_VISIBLE_MS)
    }

    private fun onBadgeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                badge.animate().alpha(1f).scaleX(1.25f).scaleY(1.25f).setDuration(HOLD_TO_UNLOCK_MS).start()
                handler.postDelayed(unlockRunnable, HOLD_TO_UNLOCK_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(unlockRunnable)
                resetBadge()
            }
        }
        return true
    }

    private fun resetBadge() {
        badge.animate().cancel()
        badge.alpha = 0.6f
        badge.scaleX = 1f
        badge.scaleY = 1f
    }

    // Swallow every touch that is not on the badge.
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    // Swallow back, volume, media and any other keys delivered to the lock window.
    // (Home, recents and power are handled by the system and can't be intercepted by apps.)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = true

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        resetBadge()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val HOLD_TO_UNLOCK_MS = 1200L
        const val HINT_VISIBLE_MS = 3000L
    }
}
