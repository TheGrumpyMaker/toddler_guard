package com.thegrumpymaker.toddlerguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Invisible activity that shows the system fingerprint prompt above the locked app.
 *
 * The overlay is lifted while the prompt is up so nothing can sit between the parent's finger and
 * the sensor (including in-display sensors). This activity's own full-screen window keeps
 * swallowing touches in the meantime. Any outcome other than a successful match puts the overlay
 * straight back.
 */
class UnlockActivity : FragmentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var prompt: BiometricPrompt? = null
    private var overlaySuspended = false
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Back press cancels the prompt (handled by the prompt itself); never leak to the app below.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        if (LockService.state != LockService.State.LOCKED) {
            finish()
            return
        }
        overlaySuspended = LockService.suspendOverlay()

        val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        prompt = biometricPrompt
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.prompt_title))
                .setSubtitle(getString(R.string.prompt_subtitle))
                .setNegativeButtonText(getString(R.string.prompt_cancel))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build(),
        )
        // Don't leave the overlay lifted forever if the prompt is left sitting there.
        handler.postDelayed({ prompt?.cancelAuthentication() }, PROMPT_TIMEOUT_MS)
    }

    private val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            unlocked = true
            LockService.unlock()
            finish()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // Cancelled, timed out, too many attempts, etc. Stay locked.
            finish()
        }

        // onAuthenticationFailed (wrong finger): the system prompt shows the error and lets the
        // parent retry, so nothing to do here.
    }

    override fun onStop() {
        super.onStop()
        // Home / recents pressed while the prompt was up: give up and re-lock.
        if (!unlocked && !isFinishing) {
            prompt?.cancelAuthentication()
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!unlocked && overlaySuspended) LockService.resumeOverlay()
        super.onDestroy()
    }

    companion object {
        private const val PROMPT_TIMEOUT_MS = 30_000L

        fun intent(context: Context): Intent =
            Intent(context, UnlockActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
    }
}
