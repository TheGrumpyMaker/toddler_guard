package com.thegrumpymaker.toddlerguard

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/** Setup checklist plus the buttons that engage the lock. */
class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var overlayButton: Button
    private lateinit var biometricStatus: TextView
    private lateinit var biometricButton: Button
    private lateinit var notificationsStatus: TextView
    private lateinit var notificationsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        overlayStatus = requireViewById(R.id.overlayStatus)
        overlayButton = requireViewById(R.id.overlayButton)
        biometricStatus = requireViewById(R.id.biometricStatus)
        biometricButton = requireViewById(R.id.biometricButton)
        notificationsStatus = requireViewById(R.id.notificationsStatus)
        notificationsButton = requireViewById(R.id.notificationsButton)

        overlayButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        biometricButton.setOnClickListener { openFingerprintEnrollment() }
        notificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }

        requireViewById<TextView>(R.id.useHint).text = getString(R.string.use_hint, LOCK_DELAY_SECONDS)
        requireViewById<Button>(R.id.lockDelayedButton).apply {
            text = getString(R.string.action_lock_delayed, LOCK_DELAY_SECONDS)
            setOnClickListener {
                if (ensureReady()) {
                    LockService.lock(this@MainActivity, LOCK_DELAY_SECONDS)
                    // Get out of the way so the parent can open the child's app.
                    moveTaskToBack(true)
                }
            }
        }
        requireViewById<Button>(R.id.lockNowButton).setOnClickListener {
            if (ensureReady()) LockService.lock(this)
        }
        requireViewById<Button>(R.id.addTileButton).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setOnClickListener { requestAddTile() }
            } else {
                visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayOk = LockReadiness.canDrawOverlays(this)
        setStatus(overlayStatus, overlayOk)
        overlayButton.visibility = if (overlayOk) View.GONE else View.VISIBLE

        when (LockReadiness.biometric(this)) {
            LockReadiness.Biometric.READY -> {
                setStatus(biometricStatus, true)
                biometricButton.visibility = View.GONE
            }
            LockReadiness.Biometric.NOT_ENROLLED -> {
                setStatus(biometricStatus, false)
                biometricButton.visibility = View.VISIBLE
            }
            LockReadiness.Biometric.NO_HARDWARE -> {
                setStatus(biometricStatus, false, getString(R.string.status_no_hardware))
                biometricButton.visibility = View.GONE
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationsOk = getSystemService(NotificationManager::class.java)!!.areNotificationsEnabled()
            setStatus(notificationsStatus, notificationsOk)
            notificationsButton.visibility = if (notificationsOk) View.GONE else View.VISIBLE
        } else {
            requireViewById<View>(R.id.notificationsRow).visibility = View.GONE
        }
    }

    private fun setStatus(view: TextView, ok: Boolean, missingText: String = getString(R.string.status_missing)) {
        view.text = if (ok) getString(R.string.status_ok) else missingText
        view.setTextColor(getColor(if (ok) R.color.ok else R.color.missing))
    }

    private fun ensureReady(): Boolean {
        if (LockReadiness.isReady(this)) return true
        Toast.makeText(this, R.string.setup_incomplete, Toast.LENGTH_SHORT).show()
        refreshStatus()
        return false
    }

    private fun openFingerprintEnrollment() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_FINGERPRINT_ENROLL)
        }
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    private fun requestAddTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        getSystemService(StatusBarManager::class.java)!!.requestAddTileService(
            ComponentName(this, LockTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_lock),
            mainExecutor,
        ) { }
    }

    companion object {
        private const val LOCK_DELAY_SECONDS = 10
        private const val REQUEST_NOTIFICATIONS = 1
    }
}
