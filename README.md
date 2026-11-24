# Jarvis voice assistant (Android)

Say "Jarvis" followed by a command. Works without root for most things; turn on
"Use root" in the app for direct system toggles.

## Build the APK

**Option A: Android Studio**
1. File > Open > select this folder, wait for Gradle sync.
2. Build > Build APK(s). Output: `app/build/outputs/apk/debug/app-debug.apk`

**Option B: GitHub (no install needed)**
1. Create a new GitHub repo and upload this folder's contents.
2. Open the Actions tab > "Build APK" > Run workflow.
3. Download `jarvis-debug-apk` from the finished run.

Install the APK on your phone (allow "install unknown apps").

## First run
1. Allow microphone and notifications.
2. Tap the permission buttons: display over other apps, modify system settings,
   Do Not Disturb access, battery unrestricted.
3. Optional: switch on "Use root" and approve the Magisk/KernelSU prompt.
4. Tap Start Jarvis.

## Commands
- open <app name>
- play <song>, play <song> on spotify, pause, resume, next song, previous
- volume up / down / mute / volume 60
- brightness up / down / brightness 30
- turn on|off wifi, bluetooth, flashlight, airplane mode, mobile data, location,
  do not disturb, auto rotate, nfc
- lock screen, screenshot, go home, go back, recent apps (root)
- reboot, power off (root, asks you to confirm)
- what time is it, battery

## Root vs no root
| Command | No root | Root |
|---|---|---|
| Flashlight, volume, media keys, open app, play music | works | works |
| Brightness, rotation, Do Not Disturb | works after granting permission | works |
| Wi-Fi, Bluetooth, mobile data, airplane, location, NFC | opens the settings screen | direct on/off |
| Lock, screenshot, back, recents, reboot | not possible | works |

## Notes
- Wake word uses Android's built-in speech recognizer in a loop, so it needs
  internet unless an offline English pack is installed, and some phones beep
  on each restart.
- Add commands in `CommandProcessor.java`.
