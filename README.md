<h1 align="center">🎮 OpenMapper</h1>

<p align="center">
  <b>High-performance, standalone Android gamepad keymapper powered by Shizuku.</b><br>
  <i>The 100% Free, Modern & Open-Source Alternative to Mantis Gamepad Pro, Panda Gamepad & Octopus.</i><br>
  Play touch-only mobile games with any physical gamepad without root and with ultra-low latency (&lt; 0.5 ms).
</p>

<p align="center">
  <a href="https://github.com/kinou-p/android-open-mapper/releases/latest"><img src="https://img.shields.io/github/v/release/kinou-p/android-open-mapper?color=00F0FF&label=Latest%20Release" alt="Latest Release"></a>
  <img src="https://img.shields.io/badge/Status-Early%20Development%20%2F%20Beta-yellow.svg" alt="Early Development">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-PolyForm%20Noncommercial-orange.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Root-Not%20Required-blue.svg" alt="No Root Required">
  <img src="https://img.shields.io/badge/Ads%20%2F%20Paywall-None-success.svg" alt="No Ads / No Paywall">
</p>

---

> [!WARNING]
> **⚠️ Early Development & Alpha/Beta Notice**
> OpenMapper is actively under **early development**. While the core mapping engine, zero-allocation input streaming, and in-game HUD editor are fully operational, you may occasionally run into unexpected bugs or device-specific quirks across different Android ROMs (MIUI/HyperOS, OneUI, ColorOS, OxygenOS, etc.).
> 
> If you encounter any bugs, controller recognition issues, or have ideas for improvements, please feel free to [**Open an Issue on GitHub**](https://github.com/kinou-p/android-open-mapper/issues) with your device model, controller model, and Android version!

> [!TIP]
> **🚀 Free & Modern Alternative to Mantis Gamepad Pro, Panda Gamepad & Octopus**
> Unlike proprietary apps (*Mantis Gamepad Pro*, *Panda*, *Octopus*), **OpenMapper** is **100% free**, **without annoying ads**, **without paid subscriptions or paywalls**, and **fully open-source**. It offers native sub-millisecond zero-allocation input streaming, e-Sport 180° flick boost curves, full haptic feedback, and a built-in community profile hub!

---

## ⚡ Quick Start & Installation

Getting started takes less than 2 minutes. **100% PC-Free & No Root Required — everything is set up directly on your phone.**

### Step 1: Download the App
1. Go to the [**Latest Releases**](https://github.com/kinou-p/android-open-mapper/releases/latest) page.
2. Download and install **`OpenMapper-vX.X.X.apk`** on your Android device.

---

### Step 2: Install & Start Shizuku (Required)
OpenMapper requires **Shizuku** to inject touch events into games with high-speed `shell` privileges without requiring root.

1. **Install Shizuku** on your phone:
   - 🐙 [**Download APK from Shizuku GitHub Releases**](https://github.com/RikkaApps/Shizuku/releases) *(Direct APK)*
   - 🌐 [**Shizuku Official Website & Documentation**](https://shizuku.rikka.app/)
   - 🏪 [**Shizuku on Google Play Store**](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)

2. **Start Shizuku** using **Option A** (Phone only) or **Option B** (PC Cable):

#### 📱 Option A: 100% On Phone (Android 11+ • No PC Needed)
1. Go to *Settings* ➔ *About Phone* ➔ Tap **Build Number** 7 times to unlock **Developer Options**.
2. In *Developer Options*, enable **Wireless Debugging**.
3. Open the **Shizuku** app, tap **Pairing** (enter code from notification), then tap **Start**.

#### 💻 Option B: Via Computer (USB Cable • Android 8.0+)
1. Enable **USB Debugging** in *Developer Options*.
2. Connect your phone to your PC with a USB cable.
3. Open a terminal / command prompt on your PC and run:
   ```bash
   adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
   ```

> 💡 *Once started, Shizuku remains active in the background until your phone is rebooted.*

---

### Step 3: Launch OpenMapper
1. Connect your controller (Bluetooth or USB-C OTG): Xbox, PlayStation, Switch Pro, 8BitDo, Razer Kishi, etc.
2. Open **OpenMapper** and grant:
   - **Shizuku Permission** (Click *Authorize*).
   - **Display Over Other Apps** (Overlay permission).
3. Select your game profile (e.g., *CoD Mobile*, *Warzone*, *PUBG*, *Genshin*).
4. Tap **🚀 LAUNCH OVERLAY & MAPPING**.

---

### Step 4: Customize Controls in Game
1. Launch your game.
2. Swipe the **side handle 🎮** on the edge of the screen to open the fullscreen HUD Editor.
3. Drag and position buttons directly over your game's touchscreen controls.
4. Tap **💾 Save** and enjoy full gamepad controls!

---

## ✨ Key Features & Highlights

### 🏎️ High-Performance Mapping Engine
- **Ultra-Low Latency (< 0.5 ms)**: Direct Linux `/dev/input/event*` binary stream parsing with zero memory allocation and zero garbage collection overhead.
- **120 Hz – 240 Hz Real-Time Loop**: High-priority dedicated engine thread synchronized with modern high-refresh-rate gaming screens.
- **Seamless 360° Camera**: Dual-pointer handoff technology eliminating screen edge locks and angle snapping during continuous 360° rotations.
- **Dynamic Flick & Boost Curve**: Micro-precision center deadzone combined with instant 180° turn acceleration at outer stick reach.

### 🎯 Tactical Assists & Aim Enhancements
- **Organic Jiggle Strafe**: Automated left/right dodge patterns during weapon fire with randomized feints and humanized timing variations.
- **Vertical Anti-Recoil**: Smooth automated pull-down compensation with configurable speed (0.1x – 10.0x) and dedicated ADS-Only mode.
- **Rotational Aim Assist (RAA) Keep-Alive**: Micro-dithering ensures in-game aim assist rotational tracking remains active even when stationary.
- **Controller Rumble & Recoil**: Dynamic physical gamepad vibration with continuous firing recoil pulses and mechanical reload double-pulses.

### 🪟 In-Game HUD & Key Customization
- **In-Game Floating HUD Editor**: Retractable side-drawer to drag, scale, and test button mappings live without leaving your match.
- **Special Button Roles**: One-tap assignment for *Primary Fire*, *Reload Magazine*, and *ADS Scope* with dedicated sensitivities.
- **Multi-Profile Management**: Create, duplicate, rename, backup, and export custom mappings for any game.

### 🌐 Cloud Community & Hardware Diagnostic
- **1-Click Community Hub**: Search, download, and vote on community-made game presets backed by Cloudflare D1 edge database.
- **Hardware Diagnostic Suite**: Real-time analog stick visualizer, 3-second automated drift test, circularity error check, and polling rate (Hz) monitor.
- **In-App Auto-Updater**: Direct background detection and 1-tap installation of new GitHub releases directly within the app.

---

## 🗺️ Roadmap & Planned Features

Check out our [**TODO.md**](TODO.md) roadmap for upcoming features and technical improvements (Virtual Mouse mode, e-Sport Macros, Radial Menus, Per-App Auto-Switching, Advanced Community Filters, Adaptive Polling, and more).


---

## 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/kinou-p/android-open-mapper.git
cd android-open-mapper/android

# Run unit tests
./gradlew testDebugUnitTest

# Compile debug APK
./gradlew assembleDebug

# Install on connected device via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚖️ Legal Disclaimer & Trademarks

- **Independent Open-Source Project**: OpenMapper is a community-driven project for personal, non-commercial use. It is not affiliated with, endorsed, or sponsored by Activision, Tencent, HoYoverse, Krafton, or any other game publisher.
- **Trademarks**: All game titles, trademarks, and logos (*Call of Duty: Mobile*, *PUBG*, *Genshin Impact*, *Mantis Gamepad Pro*, *Panda*, *Octopus*, etc.) belong to their respective owners.
- **Responsibility**: Use of this tool is at the user's sole discretion and responsibility.

---

## 📄 License

This project is licensed under the [**PolyForm Noncommercial License 1.0.0**](LICENSE) (Free for personal, non-commercial and educational use. Commercial exploitation and resale are strictly prohibited).
