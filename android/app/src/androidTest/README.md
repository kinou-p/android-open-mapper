# End-to-end tests

These tests run the **real engine** (`GamepadEngine`, `ButtonProcessor`, camera, movement, combos,
virtual cursor, MOBA aim) against **simulated games** on a physical device or an emulator.

* No Shizuku and no physical gamepad needed. Touches are injected with `UiAutomation`
  (`UiAutomationTouchInjector` replaces the Shizuku injector), and the gamepad is scripted through
  the engine public API (`E2eRig.press`, `leftStick`, `rightStick`...).
* The simulated games are plain full-screen activities that interpret touches the way a real
  game does and expose what they understood (`FpsState`, `MobaState`, `TapState`), so a test
  asserts on game behaviour ("the character walks", "the shot went up"), not on raw touches.

## Run

Connect a device (USB debugging on, screen on and unlocked) or start an emulator, then:

```
cd android
./gradlew connectedDebugAndroidTest
```

Pick a device when several are connected: `ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest`.
One class only: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kinou.gameassist.e2e.scenarios.MobaGameE2eTest`.

On a phone that already has the release build installed, uninstall it first (debug and release
are signed with different keys), and export your profiles beforehand: uninstalling deletes them.
The tests use their own in-memory profiles and never touch yours. Disconnect a real gamepad while
the tests run, so it does not feed the engine.

## Run on an emulator

Any x86_64 emulator image works. The suite is tested on API 34 (`google_apis`, Pixel 6 profile), where it
passes 26/26 several times in a row:

```
sdkmanager "emulator" "system-images;android-34;google_apis;x86_64"
avdmanager create avd -n openmapper_test -k "system-images;android-34;google_apis;x86_64" -d pixel_6
emulator -avd openmapper_test -no-window -no-audio -gpu swiftshader_indirect
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

A headless emulator is slower than a phone, so `waitFor` defaults to 10 s. The tests mark Android's
"viewing full screen" popup as already seen, because on a fresh emulator it swallows the first touch.

## Smoke tests

`e2e/smoke` holds the most basic flows, one assertion each (walk, look, hold a button, tap a button, MOBA aim and
fire, combo, cursor, the app launches). If one fails the app is broken for everyone, so they are the first thing
to run:

```
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.kinou.gameassist.e2e.smoke
```

## App UI tests

`e2e/app` drives the real app screens with Compose test rules: navigation, the Profile Manager (create, import,
duplicate, select, delete), the profile editor tabs and rename, the language picker (including Hebrew right-to-left),
the diagnostic tabs, the bug report and community screens. They use the app's real profile store: every profile they
create is named `E2E ...` and removed afterwards, and the language goes back to the system one. Your own profiles
are not touched, but as always export them before running on a phone that has the debug build installed.

## Simulated games

| Game | Mimics | Controls | Scenarios |
|---|---|---|---|
| `FpsGameActivity` | Call of Duty Mobile | floating move stick, camera drag area, fire, jump | `FpsGameE2eTest`, `FpsFeaturesE2eTest`, `SafetyE2eTest` |
| `MobaGameActivity` | Brawl Stars | floating move stick, attack and super aim joysticks, gadget | `MobaGameE2eTest` |
| `TapGameActivity` | menus, puzzle games | 4x3 grid of tap targets | `ComboE2eTest`, `CursorE2eTest`, `TapFeaturesE2eTest` |

The simulated games live in `src/debug` (not in `androidTest`): an activity of the test APK would run in
the test process, and the engine and the game must share the app process. They are only in debug builds.

## Add a game

1. `src/debug/.../e2e/games/`: extend `GameView` (pointer callbacks and a `snapshotState()` returning an immutable
   data class), add an object with the normalized layout, wrap the view in a
   `SimulatedGameActivity` subclass.
2. Declare the activity in `src/debug/AndroidManifest.xml` (full class name).
3. `scenarios/`: subclass `GameE2eTest<YourActivity, YourView>`, build a profile with `Profiles`
   (use the layout constants for button positions), call `startEngine(profile)` and script the
   gamepad with the returned `E2eRig`. Use `waitFor` / `assertStays` instead of fixed sleeps.

## CI

`.github/workflows/e2e.yml` runs the suite on an emulator on demand (workflow_dispatch).
