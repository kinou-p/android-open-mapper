# 📋 OpenMapper Roadmap & TODO

This document tracks planned features, technical enhancements, and upcoming milestones for **OpenMapper** (`kinou-p/android-open-mapper`).

---

## 🎮 Gameplay & Core Mapping Engine

- [x] **🖱️ Virtual Mouse / Pointer Mode** — ✅ **Shipped** (`L3+R3` toggles, either stick moves a quadratic-curve pointer, `A` taps/holds, `B` exits, D-pad sets the speed 0.25x–5x in game, saved as `cursor_speed`).
  - Remaining polish: D-pad auto-repeat, overlay opacity check on Android 12+.
  - Target Components: `ui/overlay/CursorOverlayView.kt`, `engine/GamepadEngine.kt`, `service/OverlayService.kt`.

- [x] **🧩 Two-Button Combos** (`R1+X`) — ✅ **Shipped**, assignable from the HUD editor (hold the modifier, press the trigger, release).
  - Target Components: `engine/ButtonProcessor.kt`, `ui/overlay/HudEditorOverlayView.kt`.

- [x] **🎯 MOBA-Style Controls (Virtual Joystick Aim)** — ✅ **Shipped** (`ButtonRole.AIM_JOYSTICK` allows holding a physical button like `R1` to open a virtual aim joystick at a fixed position, steer with the stick, and release to cast).
  - Target Components: `engine/ButtonProcessor.kt`, `engine/GamepadEngine.kt`, `data/model/ButtonRole.kt`, `data/model/ButtonConfig.kt`.
  - Origin: GitHub issue [#7 — MOBA-Style Controls](https://github.com/kinou-p/android-open-mapper/issues/7) (*enhancement*, by @MrAnarE8).

- [x] **🎮 Gyro Aim (Controller Motion)** — ✅ **Shipped** (uses the controller's own gyroscope on Android 12+, with LT-hold or always-on activation, 0.2x–5x sensitivity and smoothing; swipe camera only).
  - Target Components: `engine/GyroSource.kt`, `engine/GamepadEngine.kt`, `engine/CameraProcessor.kt`, `data/model/CameraConfig.kt`.
  - Origin: GitHub issue [#22 — Gyroscope mode](https://github.com/kinou-p/android-open-mapper/issues/22).

- [x] **🕹️ Twin-Stick Right Joystick Mode** — ✅ **Shipped** (per-profile `CameraMode.JOYSTICK`: the right stick holds a second on-screen joystick at direction × deflection × radius instead of swiping the camera).
  - Target Components: `engine/CameraProcessor.kt`, `data/model/CameraConfig.kt`, `ui/screens/ProfileEditScreen.kt`.
  - Origin: GitHub issue [#29 — Unable to use gamepad](https://github.com/kinou-p/android-open-mapper/issues/29) (right-stick part).

- [x] **🙈 Hide Controller from the Game (Exclusive Capture)** — ✅ **Shipped** (per-profile option; a dedicated `app_process` reader grabs the evdev node with `EVIOCGRAB` through Shizuku, and releases it while the HUD editor is open).
  - Target Components: `engine/ExclusiveInputReader.kt`, `engine/LinuxInputReader.kt`, `engine/GamepadEngine.kt`, `data/model/GameProfile.kt`.
  - Origin: GitHub issue [#28 — Right stick is inverted while the option to invert it is OFF](https://github.com/kinou-p/android-open-mapper/issues/28).

- [x] **💬 Floating Quick-Menu Bubble** — ✅ **Shipped** (optional draggable bubble that opens the quick menu when the edge handle is hard to reach with gesture navigation).
  - Target Components: `ui/overlay/EdgeHandleOverlayView.kt`, `service/OverlayService.kt`, `ui/screens/HomeScreen.kt`.
  - Origin: GitHub issue [#30 — Quick settings as a bubble](https://github.com/kinou-p/android-open-mapper/issues/30).

- [ ] **⚡ e-Sport Macros & Combos**
  - **Description**: Record or configure multi-action button sequences with customizable millisecond delays and execution order.
  - **Use Cases**:
    - *Drop-Shot*: Crouch + Shoot simultaneously with a single trigger press.
    - *Fast-Slide*: Sprint + Crouch + Jump sequence.
  - **Target Components**: `engine/ButtonProcessor.kt`, `data/model/ButtonConfig.kt`, `ui/screens/ProfileEditorScreen.kt`.

- [ ] **🕹️ Gesture Support & Radial Menus (Radial Wheel)**
  - **Description**: Support touch swipe gestures (e.g. swipe up/down for jump/slide) and hold-to-open radial action wheels.
  - **Benefit**: Quick selection of grenades, healing items, weapon slots, or emotes with analog stick direction snapping.
  - **Target Components**: `engine/MovementProcessor.kt`, `engine/ButtonProcessor.kt`, `ui/overlay/HudEditorOverlayView.kt`.

---

## 🪟 In-Game Experience & Overlay

- [ ] **🎮 Per-App Auto-Switch (Game Auto-Detection)**
  - **Description**: Automatically detect the foreground game package (via Shizuku elevated commands or Android `UsageStatsManager`) to load and activate the corresponding profile without manual user intervention.
  - **Benefit**: Seamless launch experience when switching between different games (e.g. *CoD Mobile*, *Warzone*, *PUBG*, *Genshin*).
  - **Target Components**: `service/OverlayService.kt`, `injector/ShizukuManager.kt`, `data/repository/ProfileRepository.kt`.

- [ ] **🪟 Quick Floating Bar (In-Game Fast Action Bar)**
  - **Description**: Retractable compact floating toolbar accessible during gameplay to quickly toggle core mechanics without opening the full-screen HUD editor.
  - **Quick Toggles**:
    - Auto Jiggle Strafe on/off
    - Anti-Recoil on/off
    - Gyroscope Aiming on/off
    - Instant Profile switcher
  - **Target Components**: `ui/overlay/EdgeHandleOverlayView.kt`, `service/OverlayService.kt`.

---

## 🌐 Community Hub & Profile Discovery

- [ ] **🏷️ Advanced Filters & Community Tags**
  - **Description**: Filter community profiles by layout style (*4-Finger Claw*, *2-Finger*, *Pro Controller*), controller hardware (*Xbox Series*, *DualSense PS5*, *Razer Kishi*, *GameSir*), and screen resolution / aspect ratio.
  - **Target Components**: `data/community/CommunityApiClient.kt`, `ui/screens/CommunityScreen.kt`, `backend/src/index.ts`, `backend/schema.sql`.

- [ ] **🖼️ Profile Visual Preview (HUD Layout Inspector)**
  - **Description**: Render an interactive graphical preview of button positions, joystick zones, and camera boundaries directly within the Community Hub before downloading or applying a profile.
  - **Target Components**: `ui/screens/CommunityScreen.kt`, `ui/overlay/HudEditorOverlayView.kt`.

---

## ⚡ Performance & Battery Optimization

- [ ] **💤 Adaptive Polling & Smart Standby Mode**
  - **Description**: Dynamically reduce input loop polling rate (e.g. from 240 Hz / 120 Hz down to 60 Hz / 30 Hz) when no physical gamepad input is detected for more than 2 seconds.
  - **Rationale**: Significantly reduces CPU wakeups and thermal throttling during long idle periods or cutscenes while restoring instantaneous 240 Hz polling on the very first input event.
  - **Target Components**: `engine/GamepadEngine.kt`, `engine/LinuxInputReader.kt`.

---

## 📳 Haptics & Force Feedback (Rumble)

- [ ] **🔌 Direct USB Host Rumble Driver (Bypass Kernel Limits & Impulse Triggers)**
  - **Description**: Implement direct low-level USB communication (`android.hardware.usb.UsbManager` / `UsbDeviceConnection`) for gamepads connected via USB-C OTG cable (Xbox One, Xbox Series, Xbox Elite Series 1 & 2, DualSense).
  - **Rationale**: Android kernel drivers on certain OEM ROMs (e.g. Xiaomi HyperOS) compile `xpad` without Force Feedback (`EV_FF`) support over standard USB. Direct USB Host communication bypasses this limitation by sending raw GIP/HID force-feedback packets (`0x09` report) directly to the controller endpoints.
  - **Advanced Capabilities**:
    - Unlocks individual **Impulse Trigger Motors** (independent LT / RT trigger vibration) on Xbox Elite & Series controllers during aiming/shooting.
    - Automatic hybrid fallback: Bluetooth native framework vibrator when connected wirelessly, direct USB Host driver when plugged via USB-C OTG cable.
  - **Target Components**: `engine/HapticManager.kt`, `engine/UsbHapticDriver.kt`, `data/model/GamepadDetector.kt`, `AndroidManifest.xml`.

---

## 🔄 App Distribution & Release Channels

- [ ] **🧪 Beta / Prerelease Channel Opt-In**
  - **Description**: Add an opt-in toggle in the application settings / about section allowing users to choose between the Stable update channel and the Beta/Prerelease channel. Update `AppUpdateManager` to query the GitHub releases API endpoint (including releases tagged as `prerelease: true`) when the beta channel is enabled.
  - **Benefit**: Allows power users and community testers to receive and test experimental features, bugfixes, and cutting-edge engine adjustments early, creating a fast feedback loop without impacting general users.
  - **Target Components**: `data/updater/AppUpdateManager.kt`, `ui/screens/HomeScreen.kt`, `data/repository/SettingsRepository.kt`.

---

## 🏗️ Architecture & Code Health

- [ ] **🧩 Deconstruct Monolithic "God Components" & Massive Files**
  - **Description**: Break down oversized, monolithic UI screens, overlay views, and engine classes into smaller, decoupled single-responsibility modules, sub-composables, and dedicated state holders.
  - **Key Target Areas**:
    - `ui/screens/GamepadTestScreen.kt` (~1,840 lines) & `ui/overlay/HudEditorOverlayView.kt` (~1,780 lines): extract canvas rendering, control palettes, dialogs, and gesture listeners into modular sub-components.
    - `ui/screens/ProfileEditScreen.kt` (~1,180 lines) & `ProfileEditorScreen.kt` (~1,090 lines): separate button configuration sheets, stick calibration panels, and macro builders.
    - `ui/screens/HomeScreen.kt` (~860 lines) & `engine/LinuxInputReader.kt` (~865 lines): isolate device discovery cards, update dialogs, and evdev byte packet parsers.
  - **Benefit**: Greatly improves code readability, maintainability, compilation speed, and testability while drastically reducing the risk of regressions during feature development.
  - **Target Components**: `ui/screens/GamepadTestScreen.kt`, `ui/overlay/HudEditorOverlayView.kt`, `ui/screens/ProfileEditScreen.kt`, `ui/screens/ProfileEditorScreen.kt`, `ui/screens/HomeScreen.kt`, `engine/LinuxInputReader.kt`.

