# Profile Manager Refactor + Home Header Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Statut : ✅ Implémenté** — livré par le commit `b78c3ea` (*refactor(profiles): split list and edit screen, simplify home header*), qui couvre les Task 1 et 2.

**Goal:** Séparer « activer » de « éditer » un profil (liste compacte + écran d'édition dédié) et alléger le header de l'accueil (mise à jour et signaler un bug en boutons secondaires).

**Architecture:** `ProfileEditorScreen.kt` ne garde que la liste compacte (`ProfileCard` avec contour actif, bouton `Activer`, crayon d'édition, menu ⋮). Tout le bloc d'édition part dans un nouveau `ProfileEditScreen.kt` plein écran. `MainActivity` gagne `Screen.PROFILE_EDIT` et un état `editTargetProfile` distinct du profil actif. `HomeScreen` perd ses trois `IconButton` de header au profit de deux boutons secondaires.

**Tech Stack:** Android (Kotlin, Jetpack Compose, Material3), Gradle 8.7 / AGP 8.5.2, JDK 17.

**Spec:** `docs/superpowers/specs/2026-10-12-profile-edit-screen-design.md`

## Global Constraints

- **JDK 17 obligatoire** pour tout build : `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew ...` (le Java 25 par défaut casse Gradle 8.7).
- Travailler depuis `android/`. Suite unitaire de référence : `:app:testDebugUnitTest` — **130 tests doivent rester verts** (aucun test n'est ajouté par ce plan, aucune logique pure n'est modifiée).
- Chaînes dans les **5** dossiers de ressources : `res/values`, `values-en`, `values-fr`, `values-es`, `values-pt`.
- Aucune dépendance nouvelle. Aucun changement de `GameProfile`, `ProfileRepository`, `OverlayService`.
- Sauvegarde **auto** conservée : `onSaveProfile(profile)` est appelé à chaque `onValueChangeFinished` / toggle, jamais sur un bouton « Enregistrer ».
- Couleurs/typo issues de `ui/theme/*` uniquement (`NeonCyan`, `DarkCard`, `DarkCardBorder`, `TextPrimary`, `TextSecondary`, `DarkBackground`, `DarkSurface`, `NeonGreen`, `NeonPink`).
- **Pas de test automatisé d'UI** : le repo n'en a aucun et le spec l'exclut. La vérification de chaque tâche = build + captures sur l'appareil connecté (`b684d6d4`).
- **Les numéros de ligne cités sont ceux du fichier avant toute modification de la tâche** (les edits décalent les lignes).

## Review Focus

Ces comportements ne sont couverts par aucun test automatique et cassent facilement à l'œil nu ; chaque tâche vérifie les siens sur l'appareil :

1. **Perte de réglage à l'édition** : un slider déplacé sur la page d'édition doit persister (auto-save) et survivre à un retour puis une réouverture — l'ancien état vivait dans `remember(profile.id)`.
2. **Activer ≠ éditer** : le crayon ne doit **jamais** changer le profil actif ; le bouton `Activer` ne doit **jamais** ouvrir l'édition.
3. **Retour depuis l'éditeur visuel** : ouvert depuis la page d'édition, le retour doit revenir à la page d'édition, pas à la liste.
4. **Suppression du dernier profil protégé** : l'action `Supprimer` doit rester absente quand un seul profil existe (`canDelete`).
5. **Débordement du header d'accueil** : après retrait des boutons, le header (titre + sous-titre + chip langue) ne doit plus déborder, y compris sur écran étroit.

---

### Task 1: Écran d'édition dédié + liste compacte + navigation

**Files:**
- Create: `android/app/src/main/java/com/kinou/gameassist/ui/screens/ProfileEditScreen.kt`
- Modify: `android/app/src/main/java/com/kinou/gameassist/ui/screens/ProfileEditorScreen.kt` (liste + `ProfileCard`, lignes 48-150 et 263-1407)
- Modify: `android/app/src/main/java/com/kinou/gameassist/MainActivity.kt`
- Modify: `android/app/src/main/res/values/strings.xml`, `values-en/strings.xml`, `values-fr/strings.xml`, `values-es/strings.xml`, `values-pt/strings.xml`

**Interfaces:**
- Consumes: `GameProfile` (`data/model/GameProfile.kt`), `HapticManager` (`engine/HapticManager.kt`), `ProfileRepository.exportProfileToJson`, `OverlayService.updateLiveProfile`, et les composables restants de `ProfileEditorScreen.kt` : `ResponseCurveVisualizer`, `SliderSetting`, `SelectGamePackageDialog`, `CreateProfileDialog`.
- Produces:
  - `ProfileEditScreen(profile: GameProfile, liveRx: Float, liveRy: Float, onSaveProfile: (GameProfile) -> Unit, onOpenVisualEditor: (GameProfile) -> Unit, onBack: () -> Unit)` — rendu **sans** barre de navigation basse.
  - `ProfileEditorScreen(profiles: List<GameProfile>, currentProfile: GameProfile?, onActivate: (GameProfile) -> Unit, onEdit: (GameProfile) -> Unit, onSaveProfile: (GameProfile) -> Unit, onDeleteProfile: (String) -> Unit, onDuplicateProfile: (GameProfile) -> Unit, onCreateProfile: ((String, String) -> Unit)?, onImportProfile: (String, (Boolean) -> Unit) -> Unit, onBack: () -> Unit)` — `onSelectProfile` devient `onActivate` ; `onOpenVisualEditor`, `liveRx`, `liveRy` disparaissent.
  - `ProfileCard(profile: GameProfile, isActive: Boolean, canDelete: Boolean, onActivate: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit)`.

- [x] **Step 1: Ajouter les chaînes dans les 5 locales**

Dans chacun de `res/values/strings.xml`, `values-en`, `values-fr`, `values-es`, `values-pt`, juste avant `</resources>`, ajouter :

```xml
<string name="profile_edit_title">Éditer le profil</string>   <!-- traduire par locale -->
<string name="btn_edit_profile">Éditer</string>
<string name="profile_actions">Plus d\'actions</string>
```

Adaptations : `values`/`values-en` en anglais (`Edit profile`, `Edit`, `More actions`), `values-es` (`Editar el perfil`, `Editar`, `Más acciones`), `values-pt` (`Editar perfil`, `Editar`, `Mais ações`).

- [x] **Step 2: Créer `ProfileEditScreen.kt`**

Créer le fichier avec le package `com.kinou.gameassist.ui.screens`, un `@OptIn(ExperimentalMaterial3Api::class) @Composable fun ProfileEditScreen(...)` de la signature ci-dessus, et **copier** dedans, depuis `ProfileEditorScreen.kt:263-1407`, en conservant le code tel quel (la suppression du bloc d'origine se fait au Step 3) :

- tous les `remember` d'état (lignes 280-320) ;
- les 4 onglets et leurs réglages (bloc `if (isSelected)` lignes 426-1317) **moins** la rangée `Export`/`Dupliquer` (430-453), qui part au menu ⋮ de la liste ;
- le bouton « Éditeur visuel » (456-472), recâblé sur `onOpenVisualEditor(profile)` ;
- les dialogues **Renommer** (1321-1389) et **Package** (1391-1406) ;
- le `HapticManager` + son `DisposableEffect` (63-68).

Le `Scaffold` de l'écran porte : `TopAppBar` (flèche `onBack`, titre `R.string.profile_edit_title`), puis un en-tête avec le **nom** éditable (crayon → `showRenameDialog`) et le **package** cliquable (→ `showPackageDialog`), puis le bouton « Éditeur visuel », puis les sous-onglets et leurs contenus. Ajouter un `BackHandler { onBack() }`. **Ne pas** réintroduire de `Card` ni de `if (isSelected)` : le contenu est affiché directement.

- [x] **Step 3: Composant `ProfileCard` compact + liste**

Dans `ProfileEditorScreen.kt`, remplacer tout le corps de `ProfileCard` (263-1407) par une carte compacte : `Card` (`fillMaxWidth`, contour `NeonCyan` si `isActive`, sinon `DarkCardBorder`) → `Row` :
- `Column(weight(1f))` : `profile.name` (bold, `maxLines=2`, ellipsis), `profile.packageName` (12 sp, `TextSecondary`, 1 ligne ellipsée), `stringResource(R.string.buttons_count_configured, profile.buttons.size)`.
- Bouton `Activer`/`Actif` : libellé `if (isActive) R.string.btn_active else R.string.btn_select`, `onClick = onActivate`.
- `IconButton(Icons.Default.Edit)` → `onEdit`, `contentDescription = R.string.btn_edit_profile`.
- `Box { IconButton(Icons.Default.MoreVert) → DropdownMenu }` avec `Dupliquer` (`onDuplicate`), `Exporter` (`onExport`), `Supprimer` (affiché seulement si `canDelete`, `onDelete`).

Mettre à jour l'appel dans la `LazyColumn` (lignes 116-149) : passer `isActive = prof.id == currentProfile?.id`, `onActivate = { onSelectProfile(prof) }` → `onActivate`, `onEdit = { onEditProfile(prof) }`, et déplacer la logique d'export JSON actuelle (lignes 133-147) en paramètre `onExport`. Supprimer `editingProfileId`, les paramètres `liveRx`/`liveRy`/`onOpenVisualEditor`, et les imports devenus inutilisés.

- [x] **Step 4: Navigation dans `MainActivity.kt`**

- Ajouter `PROFILE_EDIT` à `enum class Screen`.
- Ajouter les états `var editTargetProfile by remember { mutableStateOf<GameProfile?>(null) }` et `var visualEditorOrigin by remember { mutableStateOf(Screen.PROFILES) }`.
- `Screen.PROFILES` : `onEdit = { editTargetProfile = it; currentScreen = Screen.PROFILE_EDIT }` ; retirer `onOpenVisualEditor` et `liveRx`/`liveRy` du passage à `ProfileEditorScreen`.
- Nouvelle branche `Screen.PROFILE_EDIT -> editTargetProfile?.let { ProfileEditScreen(profile = it, liveRx = liveRx.floatValue, liveRy = liveRy.floatValue, onSaveProfile = { p -> lifecycleScope.launch { repository.saveProfileAsync(p) }; OverlayService.updateLiveProfile(p) }, onOpenVisualEditor = { p -> editTargetProfile = p; visualEditorOrigin = Screen.PROFILE_EDIT; currentScreen = Screen.VISUAL_HUD_EDITOR }, onBack = { currentScreen = Screen.PROFILES }) } ?: run { currentScreen = Screen.PROFILES }`.
- `Screen.VISUAL_HUD_EDITOR` : `onBack = { currentScreen = visualEditorOrigin }` ; l'ouverture depuis `Screen.PROFILES` met `visualEditorOrigin = Screen.PROFILES` avant de naviguer.
- `BackHandler` : `when (currentScreen) { VISUAL_HUD_EDITOR -> visualEditorOrigin; HOME -> HOME; else -> HOME }` en remplaçant le `if` actuel, et `PROFILE_EDIT` → `PROFILES`.
- Masquer la barre basse pour `PROFILE_EDIT` comme pour `VISUAL_HUD_EDITOR` (conditions aux lignes 167 et 233).

- [x] **Step 5: Build + suite unitaire**

```bash
cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```
Attendu : `BUILD SUCCESSFUL`, **130 tests**, aucune erreur ni warning Kotlin.

- [x] **Step 6: Vérification sur l'appareil**

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.kinou.gameassist && adb shell am start -n com.kinou.gameassist/.MainActivity
adb exec-out screencap -p > /tmp/opencode/profiles_after.png
```
Vérifier dans les captures, en cochant les points 1-4 du Review Focus : la liste est compacte avec contour sur le profil actif, `Activer` change l'actif sans ouvrir l'édition, le crayon ouvre l'édition, un slider déplacé y persiste après retour/réouverture, `⋮` propose dupliquer/exporter/supprimer, le retour de l'éditeur visuel revient à l'édition.

- [x] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/kinou/gameassist/ui/screens/ProfileEditScreen.kt \
        android/app/src/main/java/com/kinou/gameassist/ui/screens/ProfileEditorScreen.kt \
        android/app/src/main/java/com/kinou/gameassist/MainActivity.kt \
        android/app/src/main/res/values*/strings.xml
git commit -m "refactor(profiles): split profile list from a dedicated edit screen"
```

---

### Task 2: Allègement du header d'accueil

**Files:**
- Modify: `android/app/src/main/java/com/kinou/gameassist/ui/screens/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/kinou/gameassist/MainActivity.kt`
- Modify: `res/values/strings.xml`, `values-en`, `values-fr`, `values-es`, `values-pt`

**Interfaces:**
- Consumes: le flux `AppUpdateManager.checkForUpdate` et la bannière existante (inchangés) ; `onNavigateToBugReport` (déjà fourni par `MainActivity`).
- Produces: `HomeScreen(profiles, selectedProfile, onSelectProfile, onStartService, onStopService, isServiceRunning, onNavigateToProfiles, onNavigateToBugReport)` — le paramètre `onNavigateToTest` disparaît.

- [x] **Step 1: Ajouter les chaînes dans les 5 locales**

```xml
<string name="home_check_update">Mises à jour</string>   <!-- traduire par locale -->
<string name="home_report_bug">Signaler un bug</string>
```

Adaptations : `values`/`values-en` (`Updates`, `Report a bug`), `values-es` (`Actualizaciones`, `Informar de un error`), `values-pt` (`Atualizações`, `Reportar um erro`).

- [x] **Step 2: Retirer les trois `IconButton` du header**

Dans `HomeScreen.kt`, supprimer les `IconButton` *Check Update* (210-246), *Gamepad Tester* (248-258) et *Bug Report* (260-271) du `Row` du header. Conserver le bloc titre (`Column(Modifier.weight(1f))` avec `maxLines = 1` + `TextOverflow.Ellipsis`, lignes 126-143) et la chip langue (151-208). Retirer `onNavigateToTest` de la signature et `Icons.Default.CloudSync` / `SportsEsports` / `BugReport` s'ils deviennent inutilisés.

- [x] **Step 3: Ajouter les deux boutons secondaires**

Dans la `Column` principale, juste **après** le bouton principal « LAUNCH OVERLAY & MAPPING », insérer une `Row(fillMaxWidth, spacedBy(8.dp))` de deux `OutlinedButton(weight(1f))`, texte 12 sp, `maxLines = 1` :
- `R.string.home_check_update` : reprend **tel quel** le corps `onClick` de l'ancien bouton *Check Update* (210-229), avec `CircularProgressIndicator(Modifier.size(16.dp))` en icône de tête quand `isCheckingUpdate`.
- `R.string.home_report_bug` : `onClick = onNavigateToBugReport`.

- [x] **Step 4: Mettre à jour l'appel dans `MainActivity.kt`**

Retirer `onNavigateToTest = { ... }` du passage à `HomeScreen` (l'onglet basse `Diagnostic` reste le seul accès à `GAMEPAD_TEST`).

- [x] **Step 5: Build + suite unitaire**

```bash
cd android && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```
Attendu : `BUILD SUCCESSFUL`, 130 tests, aucun warning.

- [x] **Step 6: Vérification sur l'appareil**

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.kinou.gameassist && adb shell am start -n com.kinou.gameassist/.MainActivity
adb exec-out screencap -p > /tmp/opencode/home_after_v2.png
```
Vérifier (Review Focus point 5) : header = titre + sous-titre ellipsé + chip langue **uniquement**, aucun débordement ; les deux boutons s'affichent sous l'action principale ; « Mises à jour » déclenche le flux et affiche l'indicateur ; « Signaler un bug » ouvre l'écran de rapport ; l'onglet basse `Diagnostic` ouvre toujours l'écran de test.

- [x] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/kinou/gameassist/ui/screens/HomeScreen.kt \
        android/app/src/main/java/com/kinou/gameassist/MainActivity.kt \
        android/app/src/main/res/values*/strings.xml
git commit -m "refactor(home): move update and bug report out of the header"
```
