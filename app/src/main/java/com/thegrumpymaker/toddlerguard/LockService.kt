package com.thegrumpymaker.toddlerguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Foreground service that owns the lock overlay. While locked it:
 *  - draws a touch-swallowing window above every other app,
 *  - keeps the screen on (window flag + wake lock),
 *  - only goes away once [UnlockActivity] reports a successful fingerprint match.
 */
class LockService : Service() {

    enum class State { IDLE, COUNTDOWN, LOCKED }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var overlay: LockOverlayView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var countdownRemaining = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)!!
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_COUNTDOWN -> {
                if (state != State.LOCKED) stopLock()
                return START_NOT_STICKY
            }
            ACTION_LOCK -> {
                startInForeground(buildNotification())
                if (!LockReadiness.isReady(this)) {
                    stopLock()
                    return START_NOT_STICKY
                }
                if (state == State.IDLE) {
                    val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 0)
                    if (delaySeconds > 0) startCountdown(delaySeconds) else engageLock()
                }
            }
            null -> {
                // The system restarted us after killing the process while locked (START_STICKY):
                // lock again straight away. The overlay goes up first because a visible overlay is
                // what allows a background restart to return to the foreground.
                if (!LockReadiness.isReady(this)) {
                    stopLock()
                    return START_NOT_STICKY
                }
                if (state == State.IDLE) engageLock()
                runCatching { startInForeground(buildNotification()) }
            }
        }
        return START_STICKY
    }

    private fun startCountdown(seconds: Int) {
        state = State.COUNTDOWN
        countdownRemaining = seconds
        refreshTile()
        tickCountdown()
    }

    private val countdownTick = Runnable { tickCountdown() }

    private fun tickCountdown() {
        if (state != State.COUNTDOWN) return
        if (countdownRemaining <= 0) {
            engageLock()
            return
        }
        notify(buildNotification())
        countdownRemaining--
        handler.postDelayed(countdownTick, 1000)
    }

    private fun engageLock() {
        handler.removeCallbacks(countdownTick)
        state = State.LOCKED
        acquireWakeLock()
        showOverlay()
        overlay?.flashHint()
        notify(buildNotification())
        refreshTile()
    }

    private fun showOverlay() {
        if (state != State.LOCKED || overlay != null) return
        val view = LockOverlayView(this, onUnlockRequested = ::launchUnlock)
        windowManager.addView(view, overlayLayoutParams())
        hideSystemBars(view)
        view.requestFocus()
        overlay = view
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun launchUnlock() {
        startActivity(UnlockActivity.intent(this))
    }

    private fun stopLock() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        releaseWakeLock()
        state = State.IDLE
        refreshTile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        releaseWakeLock()
        state = State.IDLE
        if (instance === this) instance = null
        refreshTile()
        super.onDestroy()
    }

    // --- Window -----------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun overlayLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Focusable (no FLAG_NOT_FOCUSABLE) so we also receive and swallow key presses.
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        title = "ToddlerGuardLock"
    }

    /** Best effort: hide status/navigation bars so they are harder to reach by accident. */
    @Suppress("DEPRECATION")
    private fun hideSystemBars(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.windowInsetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            view.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // --- Screen on --------------------------------------------------------------------------

    /**
     * FLAG_KEEP_SCREEN_ON on the overlay already keeps the display awake; the wake lock covers the
     * moments the overlay is lifted for the fingerprint prompt.
     */
    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)!!
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "ToddlerGuard:lock",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // --- Notification -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java)!!.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (state == State.COUNTDOWN) {
            val cancel = PendingIntent.getService(
                this,
                REQUEST_CANCEL,
                Intent(this, LockService::class.java).setAction(ACTION_CANCEL_COUNTDOWN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder
                .setContentTitle(getString(R.string.notif_countdown_title, countdownRemaining))
                .setContentText(getString(R.string.notif_countdown_text))
                .addAction(Notification.Action.Builder(null, getString(R.string.notif_cancel), cancel).build())
        } else {
            // Tapping the notification never unlocks directly: it opens the fingerprint prompt.
            val unlock = PendingIntent.getActivity(
                this,
                REQUEST_UNLOCK,
                UnlockActivity.intent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder
                .setContentTitle(getString(R.string.notif_locked_title))
                .setContentText(getString(R.string.notif_locked_text))
                .setContentIntent(unlock)
        }
        return builder.build()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, notification)
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, LockTileService::class.java))
    }

    companion object {
        private const val ACTION_LOCK = "com.thegrumpymaker.toddlerguard.action.LOCK"
        private const val ACTION_CANCEL_COUNTDOWN = "com.thegrumpymaker.toddlerguard.action.CANCEL_COUNTDOWN"
        private const val EXTRA_DELAY_SECONDS = "delay_seconds"
        private const val CHANNEL_ID = "lock_status"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CANCEL = 1
        private const val REQUEST_UNLOCK = 2

        @Volatile
        var state: State = State.IDLE
            private set

        // Same-process handle used by UnlockActivity; cleared in onDestroy.
        private var instance: LockService? = null

        fun lock(context: Context, delaySeconds: Int = 0) {
            val intent = Intent(context, LockService::class.java)
                .setAction(ACTION_LOCK)
                .putExtra(EXTRA_DELAY_SECONDS, delaySeconds)
            context.startForegroundService(intent)
        }

        fun cancelCountdown(context: Context) {
            context.startService(Intent(context, LockService::class.java).setAction(ACTION_CANCEL_COUNTDOWN))
        }

        /**
         * Lifts the overlay while the fingerprint prompt is showing (UnlockActivity covers the
         * screen and swallows touches meanwhile). Returns false if there is no active lock.
         */
        fun suspendOverlay(): Boolean {
            val service = instance ?: return false
            if (state != State.LOCKED) return false
            service.removeOverlay()
            return true
        }

        /** Puts the overlay back after a cancelled or failed unlock attempt. */
        fun resumeOverlay() {
            val service = instance ?: return
            service.showOverlay()
            service.overlay?.flashHint()
        }

        /** Called only after a successful fingerprint authentication. */
        internal fun unlock() {
            instance?.stopLock()
        }
    }
}
