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

## Simulated games

| Game | Mimics | Controls | Scenarios |
|---|---|---|---|
| `FpsGameActivity` | Call of Duty Mobile | floating move stick, camera drag area, fire, jump | `FpsGameE2eTest` |
| `MobaGameActivity` | Brawl Stars | floating move stick, attack and super aim joysticks, gadget | `MobaGameE2eTest` |
| `TapGameActivity` | menus, puzzle games | 4x3 grid of tap targets | `ComboE2eTest`, `CursorE2eTest` |

## Add a game

1. `games/`: extend `GameView` (pointer callbacks and a `snapshotState()` returning an immutable
   data class), add an object with the normalized layout, wrap the view in a
   `SimulatedGameActivity` subclass.
2. Declare the activity in `src/androidTest/AndroidManifest.xml` (full class name).
3. `scenarios/`: subclass `GameE2eTest<YourActivity, YourView>`, build a profile with `Profiles`
   (use the layout constants for button positions), call `startEngine(profile)` and script the
   gamepad with the returned `E2eRig`. Use `waitFor` / `assertStays` instead of fixed sleeps.

## CI

`.github/workflows/e2e.yml` runs the suite on an emulator on demand (workflow_dispatch).
