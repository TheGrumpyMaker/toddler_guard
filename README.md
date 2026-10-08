# Toddler Guard

An Android app that locks the screen **on top of whatever app is open** (a video, a game, a video
call) so toddlers can watch but can't press anything. The screen stays on while locked, and only
a parent's fingerprint unlocks it.

## How it works

| Requirement | How it's done |
| --- | --- |
| Launches over other apps | A transparent full-screen `TYPE_APPLICATION_OVERLAY` window, owned by a foreground service (`LockService`) so it survives app switches. |
| Children can't press anything | The overlay swallows every touch and every key it receives (back, volume, media keys). System bars are hidden in sticky-immersive mode where the device allows it. |
| Screen never sleeps while locked | `FLAG_KEEP_SCREEN_ON` on the overlay, plus a screen wake lock held for the whole lock session. |
| Unlock only with a parent fingerprint | Press and hold the 🔒 badge in the corner, then touch the sensor. The system `BiometricPrompt` (Class 3 / strong biometrics only, with no PIN fallback) must succeed before the overlay is removed. A cancelled, failed or timed-out prompt puts the lock straight back. |

There are three ways to lock:

1. **Quick Settings tile**: open your child's app, swipe down, and tap **Toddler Lock**. You can
   add the tile from the app on Android 13+, or by editing your Quick Settings panel.
2. **Lock in 10 seconds** in the app, then switch to your child's app.
3. **Lock now** in the app.

The tile and the ongoing notification can't unlock anything on their own. They only open the
fingerprint prompt.

## First-time setup (in the app)

1. Grant **Display over other apps**.
2. Make sure a fingerprint is enrolled. Android can't tell *whose* finger it is, so only enroll
   the parents' fingers on this device.
3. Optionally allow notifications (Android 13+) so the lock status shows in the shade.

The app won't lock unless both 1 and 2 are done, so you can't lock yourself out.

## Limitations (Android platform rules)

- **Home, Recents and Power can't be blocked** by any normal app. Pressing them doesn't remove
  the lock, but Home/Recents can send the app underneath to the background. To keep a single app
  pinned as well, combine this with Android's built-in *App pinning*.
- The notification shade can still be pulled down on most devices. Anything in it that touches
  this app still needs the fingerprint.
- `BIOMETRIC_STRONG` also accepts Class 3 face unlock on the few devices that have it.
- **Emergency escape**: the lock doesn't survive a reboot. If the sensor stops working, restart
  the phone.

## Building

Requirements: Android Studio (or the Android SDK with platform 35) and JDK 17+.

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

- `minSdk` 29 (Android 10). From Android 10, the fingerprint prompt is always drawn by the
  system UI, above app overlays.
- `targetSdk` / `compileSdk` 35.

## Project layout

```
app/src/main/java/com/thegrumpymaker/toddlerguard/
  MainActivity.kt       setup checklist + lock buttons
  LockService.kt        foreground service: overlay, wake lock, notification, countdown
  LockOverlayView.kt    the touch/key-swallowing layer and the hold-to-unlock badge
  UnlockActivity.kt     invisible activity that shows BiometricPrompt
  StartLockActivity.kt  invisible trampoline used by the Quick Settings tile
  LockTileService.kt    Quick Settings tile
  LockReadiness.kt      overlay permission + fingerprint enrollment checks
```
