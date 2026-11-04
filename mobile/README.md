# MediZon for Android

A small native Android app (Kotlin, no Capacitor/Flutter) for personal use. It:

- shows the live MediZon website (`https://medizon-mze9.onrender.com`) in a WebView, with a branded splash, pull-to-refresh, an offline screen, camera/file uploads, location, downloads and printing/Save as PDF;
- rings **real alarms** for medicine doses and reminders, even when the app is closed and the phone is locked. Alarms come from the server (`GET /api/app/schedule`) and are set with `AlarmManager.setAlarmClock`, so they fire on time in Doze. Each alarm plays your chosen ringtone (default: the phone's ringtone) on the alarm stream, which rings even in silent/vibrate mode. It also vibrates and opens an incoming-call style full-screen page with **Skip / Taken** buttons and a Snooze option.

The website switches on its app-only features (alarm banner, link button, etc.) when the user agent contains `MediZonApp`. The page talks to the app through the `window.MediZonApp` JavaScript bridge.

## Project layout

```
mobile/
  app/src/main/java/in/medizon/app/
    MainActivity.kt      WebView shell, splash overlay, file chooser, geolocation, downloads, print, settings walkthrough
    Bridge.kt            window.MediZonApp (version, linkedAccount, link, unlink, syncNow, alarmStatus, fixAlarmSettings, print, testAlarm)
    Sync.kt              schedule sync + dose posting (WorkManager: periodic 3 h, one-off on demand, dose queue)
    Api.kt               HttpURLConnection client for /api/app/schedule and /api/app/doses
    AlarmScheduler.kt    AlarmManager scheduling / diffing / re-arming
    AlarmService.kt      foreground service that rings (sound, vibration, full-screen notification, 5-min timeout)
    AlarmActivity.kt     the full-screen ringing page (shows over the lock screen)
    AlarmActions.kt      Taken/Skip/Snooze/Dismiss logic + AlarmReceiver, AlarmActionReceiver, BootReceiver
    Notifications.kt     notification channels' content (ringing + "Missed: ..." notifications)
    Store.kt             SharedPreferences storage (token, schedule, snoozes, queued dose answers)
  keystore/medizon-release.jks   release signing key (gitignored)
  keystore.properties            signing passwords (gitignored)
```

## Open in Android Studio

File > Open > select `D:\MediZon\mobile`. Android Studio Iguana (2023.2.1) or newer works. The project uses AGP 8.3.2, Gradle 8.4 (wrapper included), Kotlin 1.9.23 and compile/target SDK 34 (minimum Android 8.0, API 26). `local.properties` points at the SDK. Android Studio rewrites it if needed.

## Build

From `D:\MediZon\mobile`, with `JAVA_HOME` set to Android Studio's JDK (`C:\Program Files\Android\Android Studio\jbr`):

```
gradlew assembleRelease      # signed APK for installing on the phone
gradlew assembleDebug        # debug APK
```

Output:

- Release: `app\build\outputs\apk\release\app-release.apk`
- Debug: `app\build\outputs\apk\debug\app-debug.apk`

### Signing

The release build is signed with `keystore\medizon-release.jks` (alias `medizon`, store and key password `medizon123`). This is a personal-use key and the passwords are in `keystore.properties`. Both files are gitignored. **Keep the .jks file.** Android only installs an update over an existing install if it is signed with the same key. If you lose the key, uninstall the app and install again.

## Install on the phone

Either:

1. Copy `app-release.apk` to the phone (USB, Drive, WhatsApp to yourself...), open it, and allow "Install unknown apps" for the app you opened it from when asked, or
2. With USB debugging on: `adb install -r app\build\outputs\apk\release\app-release.apk`

Debug and release builds have the same package name but different signatures. Uninstall one before installing the other.

## Phone settings checklist (so alarms actually ring)

Sign in on the site inside the app. The page shows a banner if anything below is missing. Tap it, and the app walks you through each setting in turn. To check by hand:

- **Notifications**: allowed. The app asks on first launch.
- **Alarms & reminders** (Android 12+): Settings > Apps > MediZon > Alarms & reminders > Allow.
- **Full-screen notifications** (Android 14+): Settings > Apps > MediZon > "Display full screen notifications" > Allow.
- **Battery**: Settings > Apps > MediZon > Battery > **Unrestricted** (not "Optimised").
- **Xiaomi / Redmi / POCO, Oppo, Vivo, Realme, OnePlus, Samsung** also kill background apps aggressively:
  - turn on **Autostart** for MediZon (Security app / Phone Manager / App info);
  - on Xiaomi also allow "Show on lock screen" and "Display pop-up windows while running in the background";
  - **lock the app in Recents** (open Recents, long-press or swipe down on MediZon, tap the lock);
  - on Samsung, take MediZon out of "Sleeping apps" / "Deep sleeping apps".
  - Model-specific steps: <https://dontkillmyapp.com>
- Keep the phone's **alarm volume** up. Alarms use the alarm stream, not the ringer.

## App settings

Open them from the site's "App settings" entry (`MediZonApp.openSettings()`).

- **Appearance:** Dark mode (off by default), or "Match phone setting". The website reads this via `MediZonApp.theme()` and can change it via `MediZonApp.setTheme()`.
- **Dose alerts**
  - **Ring phone like a call** (on by default): an incoming-call style full-screen popup with your ringtone, looping on the alarm stream.
  - When it's off, you get a normal heads-up notification with Taken / Skip / Snooze instead.
  - You can also set the ringtone, vibration, how long it rings (30 s to 5 min) and the snooze length (5/10/15 min), and send a test alert that rings in 5 s.
- **Permissions:** shows whether each one is allowed. Tap a row to open the matching system screen.
- **Sync:** shows the linked account, when it last synced and how many alarms are set. "Sync now" fetches the schedule straight away.
- **App:**
  - **Clear cache:** cookies are kept, so you stay signed in.
  - **Open website in browser**
  - **Notification settings**
  - **About**

## Test it

1. Open the app, sign in, and use the site's **Test alarm** button. It calls `MediZonApp.testAlarm()`, which rings in 10 seconds.
2. Right after tapping, close the app (swipe it away) and lock the phone.
3. Within about 10 seconds the screen should turn on with the MediZon alarm page, the alarm tone and vibration. Tap Dismiss or Snooze.

If it only shows a notification instead of the full-screen page, check the full-screen notification setting. If it does not ring at all, check battery/autostart (above).

## Change the website URL

Edit `BASE_URL` in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "BASE_URL", "\"https://medizon-mze9.onrender.com\"")
```

Then rebuild. Only pages on that host load inside the app (plus `*.razorpay.com` payment pages). Other links open in the browser or the matching app (phone, mail, UPI, maps, WhatsApp).

## How alarms stay in sync

- The app syncs the next 7 days of alarms from `GET /api/app/schedule` at these times:
  - on app start;
  - on every page load, via `syncNow()`, at most every 30 s;
  - every 3 hours in the background;
  - after reboot, app update, time or time-zone change.
- After a reboot, alarms are re-armed from the stored schedule straight away, even without internet.
- Taken/Skipped answers are sent to `POST /api/app/doses`. If the phone is offline, answers are queued and sent when it reconnects.
- If the server answers 401/403, or you sign out on the site, the app unlinks and cancels all alarms.
- If nobody answers within the "Ring for" time (default 1 minute), ringing stops and a "Missed: ..." notification stays. Tapping it opens the relevant page.
