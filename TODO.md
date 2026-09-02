# 📋 OpenMapper Roadmap & TODO

This document tracks planned features, technical enhancements, and upcoming milestones for **OpenMapper** (`kinou-p/android-open-mapper`).

---

## 🎮 Gameplay & Core Mapping Engine

- [ ] **🖱️ Virtual Mouse / Pointer Mode**
  - **Description**: Hold a customizable shortcut button (e.g. `L3/R3` or `Select + R3`) to temporarily convert the right analog stick into an on-screen mouse pointer.
  - **Benefit**: Enables fast in-game menu navigation, inventory management, looting, and popup closing without touching the screen.
  - **Target Components**: `engine/CameraProcessor.kt`, `engine/GamepadEngine.kt`, `injector/ShizukuTouchInjector.kt`.

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

