# Refonte « Profile Manager » + allègement du header d'accueil — Design

Date : 2026-10-12
Statut : proposé (à valider)

## Contexte et problème

`ui/screens/ProfileEditorScreen.kt` (2085 lignes) est une `LazyColumn` de `ProfileCard`. Une seule carte est
`selected` (`editingProfileId`, initialisée au profil courant) et, quand elle l'est, elle **déplie en inline** :
`Export`/`Dupliquer`, bouton « Éditeur visuel », puis les sous-onglets Aim/Movement/Haptic avec **tous les
réglages**. Conséquences :

- le profil actif est **toujours ouvert en grand**, et rien ne permet de le replier ;
- **« activer » et « éditer » sont le même geste** (le clic sur le nom, le bouton `Select/Active` et l'ouverture
  des réglages passent tous par `onSelect`) ;
- deux crayons redondants dans l'en-tête de carte : un pour renommer (à côté du nom), un pour choisir le jeu
  (à côté du package) ;
- `MainActivity` fournit `liveRx/liveRy` à `ProfileEditorScreen` uniquement pour la télémétrie du stick de la
  carte dépliée.

`ui/screens/HomeScreen.kt` : le header contient 4 contrôles à largeur fixe (`langue`, `update`, `manette`, `bug`)
et débordait à droite (atténué par un `weight(1f)` sur le bloc titre). Par ailleurs le bouton `manette` du header
ouvre `Screen.GAMEPAD_TEST`, **exactement le même écran que l'onglet « Diagnostic » de la barre de navigation
basse** : doublon.

## Objectif

1. **Séparer « activer » de « éditer »** : la liste des profils devient compacte (contour cyan = profil actif),
   l'édition migre dans un écran dédié plein écran.
2. **Supprimer les deux crayons** de la carte de liste (un seul point d'entrée vers l'édition).
3. **Alléger le header d'accueil** : retirer `manette` (doublon) ; déplacer `mise à jour` et `signaler un bug`
   en boutons secondaires sous l'action principale.

## Décisions clés (issues du brainstorming)

+--------------------------------------+-----------------------------------------------------------------------------+
| Question                             | Choix                                                                       |
+--------------------------------------+-----------------------------------------------------------------------------+
| Séparer activer / éditer             | Nouvel écran `ProfileEditScreen` (fichier `ProfileEditScreen.kt`)           |
| Actions de la carte de liste         | `Activer` (toggle) + crayon (ouvre l'édition) ; menu ⋮ = Dupliquer/Exporter/Supprimer |
| Éditeur visuel HUD                   | Bouton dans la page d'édition (plus sur la carte de liste)                  |
| Crayons nom / package                | Supprimés de la liste ; renommage et choix du jeu migrent dans l'édition    |
| Sélection « active »                 | Contour cyan sur la carte, **plus de dépliage inline**                      |
| Header d'accueil                     | Titre + sous-titre + chip langue uniquement ; `manette` retiré              |
| Mise à jour + bug report             | Deux boutons secondaires sous « LAUNCH OVERLAY »                            |
| Affichage de l'édition               | Plein écran, barre de navigation basse masquée (comme `VISUAL_HUD_EDITOR`)  |
| Sauvegarde des réglages              | Auto (inchangée : `onSave` à chaque `onValueChangeFinished` / toggle)       |
| Séquencement                         | Liste + édition + navigation + accueil livrés **d'un coup**                 |
+--------------------------------------+-----------------------------------------------------------------------------+

## Architecture

### Vue d'ensemble

`ProfileEditorScreen` ne contient plus que la **liste** ; tout le bloc d'édition (en-tête nom/jeu, éditeur visuel,
sous-onglets et réglages) devient `ProfileEditScreen`. `MainActivity` gagne un écran `PROFILE_EDIT` et un état
`editTargetProfile` **distinct** de `selectedProfile` (on peut éditer un profil sans l'activer). L'accueil perd
ses deux boutons de header et gagne deux boutons secondaires.

### Fichiers

| Fichier | Responsabilité | Action |
|---|---|---|
| `ui/screens/ProfileEditScreen.kt` | Écran d'édition plein écran (en-tête nom/jeu, éditeur visuel, sous-onglets réglages) | **Créer** |
| `ui/screens/ProfileEditorScreen.kt` | Liste compacte des profils + dialogues création/import/suppression ; `ProfileCard` compacte avec menu ⋮ | **Modifier** |
| `MainActivity.kt` | `Screen.PROFILE_EDIT`, état `editTargetProfile`, navigation et `BackHandler`, masquage de la barre basse | **Modifier** |
| `ui/screens/HomeScreen.kt` | Header allégé (titre + langue) ; deux boutons secondaires sous l'action principale | **Modifier** |
| `res/values/strings.xml`, `values-en/fr/es/pt/strings.xml` | Nouvelles chaînes (5 locales) | **Modifier** |

Aucune dépendance nouvelle, aucun changement de `GameProfile`, aucun changement de `ProfileRepository` ou du
service.

### Liste des profils (`ProfileEditorScreen`)

- `ProfileCard` compacte : `nom` (1-2 lignes, ellipsé), `package`, `« N touches configurées »`.
- **Contour `NeonCyan`** si le profil est le profil actif ; fond neutre sinon (plus de dépliage).
- **Le corps de la carte n'est pas cliquable** : l'activation se fait uniquement par le bouton `Activer`, pour
  éviter toute activation accidentelle (l'ancien clic sur le nom est supprimé).
- Bouton **`Activer` / `Actif`** (toggle) → `onActivate(profile)`, comportement actuel d'`onSelectProfile`
  conservé (`selectedProfile` + `OverlayService.updateLiveProfile`). Le libellé suit l'état (`btn_select` /
  `btn_active` existants).
- **Crayon unique** (`Icons.Default.Edit`) → `onEdit(profile)`.
- Menu **⋮** (`DropdownMenu`) : `Dupliquer`, `Exporter` (logique actuelle extraite telle quelle), `Supprimer`
  (masqué si `profiles.size <= 1`).
- Conservés tels quels : `TopAppBar` (nouveau profil / import), dialogues `CreateProfileDialog`,
  l'import et la confirmation de suppression.
- Retirés : sous-onglets, tous les réglages, `ResponseCurveVisualizer`, télémétrie `liveRx/liveRy`, les deux
  crayons nom/package, les paramètres `liveRx/liveRy` de `ProfileEditorScreen`.

### Page d'édition (`ProfileEditScreen`)

- Signature : `ProfileEditScreen(profile: GameProfile, liveRx: Float, liveRy: Float, onSaveProfile: (GameProfile) -> Unit, onOpenVisualEditor: (GameProfile) -> Unit, onBack: () -> Unit)`.
- `Scaffold` + `TopAppBar` : flèche retour (`onBack`) + titre « Éditer le profil ».
- **En-tête** : nom éditable via un crayon unique → dialogue **Renommer** (déplacé depuis `ProfileCard`) ;
  ligne `package` cliquable → `SelectGamePackageDialog` (déplacé depuis `ProfileCard`).
- Bouton pleine largeur **« Éditeur visuel HUD »** → `onOpenVisualEditor(profile)`.
- Sous-onglets et contenus **repris tels quels** depuis l'actuelle zone `isSelected` (Aim / Movement / Haptic,
  `ResponseCurveVisualizer` avec `liveRx/liveRy`).
- Sauvegarde auto inchangée : chaque modification appelle `onSaveProfile(profile)`.
- `BackHandler` → `onBack`.

### Navigation (`MainActivity`)

- Ajout de `Screen.PROFILE_EDIT`.
- Nouvel état `editTargetProfile: GameProfile?` (indépendant de `selectedProfile`).
- `ProfileEditorScreen` : `onEdit = { editTargetProfile = it; currentScreen = Screen.PROFILE_EDIT }`.
- `ProfileEditScreen` : `onSaveProfile` → `repository.saveProfileAsync` + `OverlayService.updateLiveProfile`
  (comme aujourd'hui) ; `onOpenVisualEditor = { editTargetProfile = it; visualEditorOrigin = Screen.PROFILE_EDIT; currentScreen = Screen.VISUAL_HUD_EDITOR }`.
- `BackHandler` :
  - `PROFILE_EDIT` → `PROFILES` ;
  - `VISUAL_HUD_EDITOR` → `visualEditorOrigin` (`PROFILE_EDIT` s'il a été ouvert depuis l'édition, sinon `PROFILES`) ;
  - les autres écrans (hors `HOME`) → `HOME`.
- Barre de navigation basse masquée pour `VISUAL_HUD_EDITOR` **et** `PROFILE_EDIT`.
- La signature de `HomeScreen` perd `onNavigateToTest` (plus d'appelant dans le header).

### Accueil (`HomeScreen`)

- Header : titre + sous-titre (`weight(1f)` + ellipsis, conservé) + chip langue. **Retrait** des `IconButton`
  `update`, `manette` et `bug report`.
- Sous le bouton principal « LAUNCH OVERLAY & MAPPING », une `Row` de deux boutons secondaires de largeur égale
  (`weight(1f)`), libellés courts, texte 12 sp, `maxLines = 1` :
  - **« Mises à jour »** → déclenche le flux de vérification existant (`AppUpdateManager`), avec l'indicateur de
    chargement repris de l'ancien bouton ;
  - **« Signaler un bug »** → `onNavigateToBugReport`.
- La bannière « mise à jour disponible » et le dialogue restent inchangés.

### Chaînes

Nouvelles chaînes dans les 5 dossiers `values*` : `profile_edit_title` (« Éditer le profil »),
`btn_edit_profile` (description du crayon), `profile_actions` (description du menu ⋮), `home_check_update`
(« Mises à jour »), `home_report_bug` (« Signaler un bug »). Réutilisation des chaînes existantes
`btn_select` / `btn_active` (bouton Activer), `btn_duplicate`, `btn_export`, `btn_delete` (menu ⋮),
`btn_visual_editor` et `btn_rename`.

## Vérification

- **Tests** : aucune logique pure n'est ajoutée ou modifiée ⇒ pas de nouveau test unitaire pertinent. La suite
  existante (**130 tests**) doit rester verte.
- **Build** : `assembleDebug` (JDK 17) sans erreur ni warning.
- **Sur l'appareil** (captures avant/après) : liste compacte avec contour actif, menu ⋮, page d'édition
  (nom/jeu éditable + éditeur visuel + sous-onglets), retour, et accueil avec les deux boutons secondaires.
- **Smoke** : lancement sans crash (`logcat`).
- *Limite assumée* : le repo n'a pas de test de layout automatisé ; la régression visuelle est vérifiée par
  captures, pas par un test.

## Hors scope

- Réorganisation ou renommage des réglages ; changement de l'auto-save ; migration de profils existants.
- Refonte visuelle globale (couleurs, typo) hors des écrans touchés.
- Modification du comportement de `VisualHudEditorScreen`.

## Risques

- **Extraction de ~1600 lignes** de `ProfileEditorScreen` vers `ProfileEditScreen` : risque de régression
  visuelle ou d'état perdu (`remember(profile.id)`). Mitigation : reprise telle quelle des blocs, vérif par
  captures.
- **Navigation à plusieurs niveaux** (`PROFILE_EDIT` ↔ `VISUAL_HUD_EDITOR`) : risque de retour incorrect.
  Mitigation : `visualEditorOrigin` explicite + vérif des retours.
