# PaintGo — Claude notes

Read [SPEC.md](SPEC.md) and [TODO.md](TODO.md) first for project intent and current step.

## Build / compile

Bash has no Java in PATH. Use PowerShell with the Android Studio bundled JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat compileDebugKotlin
```

Incremental compile is ~3 seconds. Run it after every Kotlin edit before reporting done — don't make the user be the compiler.

Full `assembleDebug` (APK) is heavier; only run if explicitly asked. User installs + runs on the emulator themselves.

## Runtime logs (when app crashes / ANRs)

ADB is at `C:\Users\anita\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Useful one-shots:

```powershell
$adb = "C:\Users\anita\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Devices currently connected
& $adb devices

# Recent errors only (last 200 lines, severity Error+)
& $adb logcat -d -t 200 *:E

# Just our app, by package PID
$pid = & $adb shell pidof com.anita.paintgo
& $adb logcat -d --pid=$pid

# Crash stack traces
& $adb logcat -d AndroidRuntime:E *:S
```

When the user reports a crash/ANR, dump logcat yourself instead of asking for screenshots.

## Gotchas

- Write tool currently injects `</content></invoke>` at the bottom of newly-created files. Always Read the tail after a Write to confirm and Edit if needed.
- `android.disallowKotlinSourceSets=false` is set in `gradle.properties` to work around a KSP / built-in-Kotlin conflict. Experimental flag — fine for now, revisit if KSP/AGP versions change.
- Min SDK is 29 (per user's older test phone). Flag any API > 29 features before recommending.

## File layout

- `app/src/main/java/com/anita/paintgo/MainActivity.kt` — Compose entry point
- `app/src/main/java/com/anita/paintgo/MapScreen.kt` — MapLibre map composable
- `app/src/main/java/com/anita/paintgo/LocationService.kt` — foreground service scaffold (empty so far)
