package com.thegrumpymaker.toddlerguard

import android.content.Context
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG

/**
 * Checks that the lock can be both engaged and released. Locking without an enrolled
 * fingerprint would leave the parent with no way back in, so we refuse to lock in that case.
 */
object LockReadiness {

    enum class Biometric { READY, NOT_ENROLLED, NO_HARDWARE }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun biometric(context: Context): Biometric =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Biometric.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Biometric.NOT_ENROLLED
            else -> Biometric.NO_HARDWARE
        }

    fun isReady(context: Context): Boolean =
        canDrawOverlays(context) && biometric(context) == Biometric.READY
}
