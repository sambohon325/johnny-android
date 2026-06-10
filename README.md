# Johnny Android
### Dual-mode tablet app for the Johnny companion care system

---

## What This Is

The Johnny Android app runs on every Johnny tablet. It has two modes:

**Mode 1: Personal Kiosk**
A locked-down tablet experience tailored to each user. Riot gets YouTube categories. Winter gets educational and creative tools. The grandparents get a simplified smart home interface.

**Mode 2: Robot Face**
When mounted on the companion robot, the app becomes the robot's face — an animated display that reacts to mood, speaks, and interfaces with the Raspberry Pi hardware underneath.

---

## Repository Structure

```
johnny-android/
  core/           ← Shared code used by all profiles
                    - Hive connection (WebSocket + HTTPS)
                    - Authentication
                    - Location service (manual trigger)
                    - Camera service (manual trigger, front + back)
                    - Push notification handler
  kiosk/          ← Personal kiosk mode code
  robot-face/     ← Robot face mode code
  johnny6/        ← Riot's profile and config
  johnny7/        ← Winter's profile and config
  johnny8/        ← Grandparents' profile and config
  docs/           ← Android-specific documentation
```

---

## Current Status

| Johnny | Status | Notes |
|---|---|---|
| Johnny 6 (Riot) | ✅ Phase 1 complete | Kiosk app running on Lenovo tablet |
| Johnny 7 (Winter) | ⬜ Not started | |
| Johnny 8 (Grandparents) | ⬜ Not started | |

---

## Building

Requirements:
- Android Studio (latest)
- Java 8+
- Android SDK 21+ (minSdk), 34 (targetSdk)

```
1. Open Android Studio
2. File → Open → select johnny-android folder
3. Let Gradle sync
4. Build → Build Bundle(s)/APK(s) → Build APK(s)
```

---

## Installing on Tablet (No USB Debugging)

Due to firmware lock on the Lenovo tablet, USB debugging is unavailable.
Install via file transfer:

```
1. Build APK in Android Studio
2. Connect tablet via USB — select "File Transfer" mode
3. Copy app-debug.apk to tablet Downloads folder
4. On tablet: Settings → Security → Install unknown apps → enable for file manager
5. Open file manager on tablet, tap APK, install
```

---

## Key Technical Details

- Package: `com.johnny6.riotdash` (Johnny 6), `com.johnny7` (future), `com.johnny8` (future)
- Dashboard URL: `https://johnny6.sambohon.digital`
- URL routing: `shouldOverrideUrlLoading` intercepts YouTube URLs → routes to YouTube WebView
- Back button: trapped — users cannot exit
- Screen: always on, immersive fullscreen

---

## Adding Areana as Collaborator

Go to github.com/sambohon325/johnny-android → Settings → Collaborators → Add Areana

---

*Part of the Johnny system — github.com/sambohon325*
