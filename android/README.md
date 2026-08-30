# 🎮 OpenMapper (GameAssist) - Android Native APK + Shizuku

**OpenMapper (GameAssist)** est une application Android native autonome et ultra-performante (Kotlin + Jetpack Compose) permettant de jouer à n'importe quel jeu tactile mobile (**Call of Duty: Mobile**, **Warzone Mobile**, **PUBG Mobile**, **Genshin Impact**, etc.) avec n'importe quelle manette de jeu physique (Xbox, PlayStation DualSense/DualShock 4, Nintendo Switch Pro, 8BitDo, Razer Kishi, Backbone, ou OTG générique).

Grâce à l'intégration de **Shizuku**, l'application injecte des événements multi-touch directement dans le système d'exploitation avec une latence ultra-basse (**< 0.5 ms**), **sans aucun root** et **sans PC requis après configuration initiale**.

---

## ⚡ Caractéristiques Principales

### 🏎️ 1. Moteur Bas-Niveau & Performance Pure
- **Injection Multi-Touch Directe via Shizuku (`IInputManager`)** :
  - Privilèges `shell` (UID 2000) sans root.
  - Latence imperceptible ($< 0.5\text{ ms}$).
  - Multi-touch simultané jusqu'à 10 doigts sans conflit.
- **Moteur d'Entrée Binaire Zéro-Allocation (`BinaryInputParser`)** :
  - Streaming binaire direct des paquets `struct input_event` (24 octets / 16 octets) depuis `/dev/input/event*`.
  - Décodeur hexadécimal direct par bit-shifts (0 instanciation de `String`, 0 `Regex`, 0 Garbage Collection).
  - 100% furtif et transparent vis-à-vis des systèmes anti-cheats (aucun binaire tiers dans `/data/local/tmp`).

### 🎯 2. Contrôle & Précision FPS e-Sport
- **Caméra 360° Fluide (Algorithme Dual-Pointer Interlaced Handoff)** :
  - Élimine 100% des saccades et téléportations de visée lors des rotations infinies.
  - Lissage EMA (Exponential Moving Average) anti-jitter.
- **Courbes de Réponse Analogique Personnalisables** :
  - **🚀 Flick 180° (Dynamic Boost)** : Micro-visée chirurgicale au centre + accélération turbo en butée (>80%) pour demi-tours instantanés, avec sécurité ADS automatique (maintien de la précision lors de la visée à la lunette `LT`).
  - **⚡ Dynamique** : Courbe en S progressive.
  - **🌊 Standard & 📏 Linéaire** : Pente d'accélération configurable.
- **Déplacement Analogique & RAA Keep-Alive** :
  - Joystick gauche avec seuil de Sprint forcé automatique.
  - *RAA Keep-Alive* : Micro-oscillations sub-pixel maintenant active l'aide à la visée rotative (*Rotational Aim Assist*) en continu.
  - *Organic Jiggle Strafe* : Esquive automatique gauche-droite pendant le tir avec micro-variations biométriques humaines et anti-détection.

### 📳 3. Immersion Haptique & Remapping Direct
- **Retour Haptique Dynamique (Vibrations en Jeu)** :
  - Vibration de tir sèche simulant le recul d'arme à chaque coup tiré.
  - Double impulsion mécanique distincte simulant le rechargement.
  - Intensité réglable et boutons de test interactifs.
- **Gestionnaire de Touches Intégré dans l'App** :
  - Remappez n'importe quel bouton (`RT`, `LT`, `RB`, `LB`, `A`, `B`, `X`, `Y`, `L3`, `R3`, `D-Pad`...) en 1 clic.
  - Bascule instantanée de mode d'action : **HOLD** (Maintenu), **TAP** (Pression courte avec micro-drift humain), ou **SLIDE_CANCEL** (Combo glissade CoD).

### 📐 4. Diagnostic & Calibration Manette
- **Visualiseur en temps réel** : Position des sticks et matrice d'état de tous les boutons.
- **🪄 Auto-Test de Drift (3 secondes)** : Mesure l'erreur physique au repos et calcule la zone morte centrale idéale.
- **🎯 Test de Circularité & Portée Maximale** : Tracé radar à 360° et calibration de l'*Outer Deadzone* pour garantir 100% de vitesse en diagonale.

### 📱 5. Overlay Flottant en Jeu (HUD Editor)
- Volet latéral discret sur le bord de l'écran.
- Éditeur plein écran par-dessus le jeu avec glisser-déposer des boutons et mode d'apprentissage instantané.

---

## 📦 Compilation & Installation

### Option 1 : En Ligne de Commande (Recommandé)
```bash
cd android
./gradlew testDebugUnitTest  # Lance la suite de tests unitaires
./gradlew assembleDebug      # Compile l'APK

# Installation directe sur smartphone connecté en USB/Wi-Fi
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option 2 : Avec Android Studio
1. Ouvrez le dossier `android/` dans **Android Studio**.
2. Connectez votre smartphone en USB ou Wi-Fi.
3. Cliquez sur le bouton vert **Run ▶️** (ou `Shift + F10`).

---

## 🚀 Guide de Démarrage Rapide

### 1. Activer Shizuku sur votre Smartphone (Sans PC)
1. Installez **Shizuku** depuis Google Play ou GitHub sur votre téléphone.
2. Activez les **Options pour les développeurs** (7 taps sur le Numéro de build dans les paramètres Android).
3. Activez le **Débogage sans fil** (*Wireless Debugging*).
4. Ouvrez Shizuku, choisissez **Démarrer via le Débogage sans fil**, puis entrez le code d'association.
5. Shizuku est prêt et restera actif jusqu'au prochain redémarrage !

### 2. Lancer OpenMapper
1. Connectez votre manette (Bluetooth ou USB-C OTG).
2. Ouvrez **OpenMapper**.
3. Accordez l'autorisation Shizuku et l'autorisation d'affichage en superposition (Overlay).
4. Sélectionnez votre profil (ex: **CoD Mobile - Multijoueur**).
5. Cliquez sur **🚀 LANCER L'OVERLAY & MAPPING**.

### 3. Personnaliser en Jeu
1. Lancez **Call of Duty: Mobile**.
2. Glissez le **volet latéral 🎮** pour ouvrir l'éditeur de HUD.
3. Ajustez les positions des boutons sur votre interface tactile.
4. Cliquez sur **💾 Sauver** et jouez !

---

## 🏗️ Structure du Projet

```
android/
├── build.gradle.kts
├── settings.gradle.kts
└── app/
    ├── src/
    │   ├── main/
    │   │   ├── AndroidManifest.xml
    │   │   ├── aidl/android/hardware/input/
    │   │   │   └── IInputManager.aidl          # Interface Binder système pour injection
    │   │   ├── java/com/kinou/gameassist/
    │   │   │   ├── MainActivity.kt             # Interface principale Jetpack Compose
    │   │   │   ├── data/
    │   │   │   │   ├── model/                  # Modèles de données (GameProfile, ButtonConfig...)
    │   │   │   │   └── repository/             # Stockage & Export/Import JSON des profils
    │   │   │   ├── injector/
    │   │   │   │   ├── ShizukuManager.kt       # Gestionnaire du service Shizuku
    │   │   │   │   ├── PointerPool.kt          # Pool des 10 pointeurs multi-touch
    │   │   │   │   └── ShizukuTouchInjector.kt # Injecteur MotionEvent asynchrone ultra-rapide
    │   │   │   ├── engine/
    │   │   │   │   ├── BinaryInputParser.kt    # Décodeur binaire & hexadécimal zéro-allocation
    │   │   │   │   ├── LinuxInputReader.kt     # Streaming direct /dev/input sans Garbage Collection
    │   │   │   │   ├── HapticManager.kt        # Moteur de retour haptique (tir & rechargement)
    │   │   │   │   ├── GamepadEngine.kt        # Moteur principal de synchronisation
    │   │   │   │   ├── MovementProcessor.kt    # Déplacement stick gauche, sprint & Jiggle Strafe
    │   │   │   │   ├── CameraProcessor.kt      # Visée fluide 360° (Dual-Pointer Handoff)
    │   │   │   │   └── ButtonProcessor.kt      # Actions multi-touch, retour haptique & macros
    │   │   │   ├── service/
    │   │   │   │   └── OverlayService.kt       # Foreground Service avec WindowManager
    │   │   │   └── ui/
    │   │   │       ├── screens/                # Écrans Compose (Home, Diagnostic Test, Profils)
    │   │   │       ├── overlay/                # Vues overlay (Volet latéral & Éditeur HUD)
    │   │   │       └── theme/                  # Thème Cyberpunk / Neon & typographie
    │   │   └── res/                            # Ressources XML, icônes et styles
    │   └── test/java/com/kinou/gameassist/engine/
    │       └── LinuxInputReaderTest.kt         # Tests unitaires du moteur binaire
```

---

## ⚖️ Avertissement Légal & Disclaimer

- **Projet Open-Source Indépendant** : OpenMapper est un projet communautaire gratuit pour un usage strictement personnel. Il n'est en aucun cas affilié, sponsorisé ou approuvé par Activision, Tencent, HoYoverse, Krafton ou tout autre éditeur de jeux vidéo.
- **Marques Déposées** : Toutes les marques, noms de jeux et logos cités (*Call of Duty: Mobile*, *PUBG*, *Genshin Impact*, etc.) sont la propriété exclusive de leurs détenteurs respectifs.
- **Utilisation Responsable** : L'utilisation de cet outil est sous la seule responsabilité de l'utilisateur final.

---

## 📄 Licence
Ce projet est distribué sous licence **PolyForm Noncommercial License 1.0.0** (Usage personnel, éducatif et communautaire autorisé — **Toute exploitation ou revente commerciale est strictement interdite**). Voir le fichier [LICENSE](../LICENSE) pour plus de détails.
