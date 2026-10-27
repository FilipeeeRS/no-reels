# No Reels

Personal Android app that removes Reels, Explore and ads from Instagram using an accessibility service. No root required.

## How to use

Requires Android 14+ with USB debugging enabled and JDK 17 to build.

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then enable it under **Settings > Accessibility > No Reels**.

## If it stops working

An Instagram update may change the IDs the app relies on. Generate a diagnostic under **Advanced** and adjust the IDs right there.
