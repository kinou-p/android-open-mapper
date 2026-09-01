# Device Token émis par le serveur — Design

Date : 2026-09-01
Statut : validé (brainstorming)

## Contexte et problème

L'identité d'appareil actuelle est `deviceHash = SHA256(ANDROID_ID : salt_fixe)`, calculé en entier côté client
(`android/.../DeviceFingerprint.kt`) et envoyé dans le corps des requêtes `POST /vote`, `/profiles` et
`/telemetry/ping`. Le middleware `verifySignature` (HMAC sur `method + path + timestamp + body`) authentifie le
corps en bloc, mais pas l'identité de l'appareil : quiconque détient `APP_SECRET` (incrusté dans l'APK — C1) peut
signer un corps contenant n'importe quel `deviceHash` et contourner les plafonds par-appareil (10 publis/jour,
200 votes/jour, etc.). Le commentaire « deviceHash étant désormais signé (HMAC), il constitue une identité fiable »
(`backend/src/index.ts:688`) est donc faux.

## Objectif

Éliminer la trivialité du forgage : un identifiant ne doit plus être dérivable localement depuis une valeur prédictible
`ANDROID_ID`. On passe à un **token opaque de haute entropie, émis par le serveur**, stocké chiffré côté client.

Limite assumée (validée) : le token reste un *bearer* copiable. Il ne constitue pas une preuve cryptographique
d'appareil. L'upgrade vers l'attestation (Play Integrity) est hors scope.

## Décisions clés (issues du brainstorming)

+----------------------------------------------------------------------------------------------------------+-----------------------------------+
| Question                                                                                                 | Choix                             |
+----------------------------------------------------------------------------------------------------------+-----------------------------------+
| Gestion des anciens `deviceHash` (SHA256(ANDROID_ID:salt))                                                | Ré-enregistrement forcé           |
| Point d'émission du token                                                                                 | Route dédiée `/api/device/register`|
| Anti-abus sur `/register`                                                                                 | Rate-limit IP + cooldown          |
| Format du token sur le fil et lien avec les tables                                                        | `deviceToken` brut + hash serveur |
| Stockage côté client                                                                                      | EncryptedSharedPreferences        |
+----------------------------------------------------------------------------------------------------------+-----------------------------------+

## Architecture

### Vue d'ensemble

1. Au premier lancement (aucun token), le client appelle `POST /api/device/register`.
2. Le serveur génère `token = crypto.randomBytes(32).toString('hex')` (64 hex, ~256 bits), stocke
   `device_hash = SHA256(token)` dans la table `devices`, et renvoie `{ deviceToken: token }` une seule fois.
3. Le client persiste le token en clair localement (chiffré au repos via EncryptedSharedPreferences).
4. Les requêtes suivantes (`/vote`, `/profiles`, `/telemetry/ping`) portent le token brut dans le champ
   `deviceToken`. Le serveur calcule `SHA256(deviceToken)` et réutilise tel quel la colonne `device_hash`
   comme identité interne et clé de limitation.
5. Le champ `deviceHash` (ancien format) est rejeté (400).

### Schéma de données

Aucune colonne nouvelle obligatoire : `device_hash` (`PK` de `devices`, colonne de `profiles`, `votes`,
`daily_activity`) est réutilisé et vaut désormais `SHA256(token)`. Le token brut n'est jamais persisté côté serveur.

### Détail serveur (`backend/src/index.ts`)

#### Nouvelle route `POST /api/device/register`

- Protégée par `verifySignature` (HMAC) — authentifie l'origine applicative.
- Rate-limit :
  - `ip:register:${clientIp}` — `maxRequests: 5`, `windowMs: 3600_000` → 429.
  - `ip:register_cd:${clientIp}` — `maxRequests: 1`, `windowMs: 30_000` → 429.
- Logique :
  - Génère le token, calcule `tokenHash = sha256(token)`.
  - Corps accepté : `{ appVersion? }` (borné à 20 caractères, défaut `'1.0.0'`) — cohérent avec ping.
  - `INSERT INTO devices (device_hash, first_seen, last_seen, app_version, launch_count) VALUES (?, ?, ?, ?, 1) ON CONFLICT(device_hash) DO NOTHING` (app_version issue du corps, bornée).
  - Renvoie `{ success: true, deviceToken: token }` (le token n'est renvoyé qu'ici).

#### Routes modifiées (`/vote`, `/profiles`, `/telemetry/ping`)

- Remplacer la lecture de `deviceHash` par `deviceToken` dans le corps.
- Helper commun `deviceIdentityFromBody(body)` :
  - valide présence + `HASH_REGEX` (`/^[a-f0-9]{64}$/i`) sur `deviceToken`,
  - renvoie `sha256(deviceToken)` (identité interne, = `device_hash`),
  - sinon `null` → 400.
- Les clés de limite `dev:pub_*`, `dev:vote_*`, `ping:*`, et les colonnes `profiles.device_hash`,
  `votes.device_hash`, `devices.device_hash`, `daily_activity.device_hash` utilisent ce hash.
- `/download` : corps `{}`, sans identité — inchangé.

#### Nettoyage

- Réécrire/retirer le commentaire trompeur `backend/src/index.ts:688` et l'assertion « fiable ».

### Détail client (Android)

#### Remplacement de `DeviceFingerprint.kt`

Nouveau store de token (ou réécriture du fichier) :
- Lecture/écriture dans `EncryptedSharedPreferences` (`androidx.security:security-crypto`).
- `getOrCreateToken(context) : String` : lit le token stocké ; si absent → appel `POST /api/device/register`
  (corps signé HMAC), persiste le `deviceToken`, le renvoie.
- Suppression complète du calcul `SHA256(ANDROID_ID:salt)` et des accès `Settings.Secure.ANDROID_ID`.

#### `CommunityApiClient.kt`

- `private val deviceHash by lazy { DeviceFingerprint.getDeviceHash(context) }` → token chargé du store.
- Champ payload `"deviceHash"` → `"deviceToken"` dans `vote(...)`, `publishProfile(...)`, `sendTelemetryPing(...)`.
- Ajout de `registerDevice(context) : String` (POST signé `/api/device/register`, parse `deviceToken`, persiste).
- Garantir un token avant toute écriture : `ensureToken()` appelé en amont ; sinon l'écriture échoue proprement.

#### `MainActivity.kt`

- Heartbeat `sendTelemetryPing()` (ligne ~105) : s'assurer que le token est obtenu (enregistré si besoin) avant le ping.

#### Dépendance

- Ajouter `androidx.security:security-crypto` (`build.gradle*`).

## Flux (fresh install)

1. Lancement → `ensureToken()`.
2. Aucun token → `POST /api/device/register` → réponse `{ deviceToken }` → stockage chiffré.
3. Heartbeat ping (avec `deviceToken`) + écritures (vote/upload) utilisent le token.

## Gestion d'erreurs

- `/register` en 429 → message « trop de tentatives » ; le client conserve un éventuel token déjà persisté.
- Token absent/invalide sur `/vote` ou `/profiles` → 400.
- Échec réseau d'enregistrement → l'écriture échoue proprement (identité indisponible), message convivial.

## Sécurité / résidus

- **Gain** : suppression du forgage trivial (identité non dérivable depuis `ANDROID_ID`).
- **Restant** : token = secret *bearer* ; copiable par extraction. La DB ne stocke que le hash.
- **Hors scope** : preuve matérielle d'appareil (Play Integrity) — voie d'upgrade si la confiance device devient critique.
- `/register` est une nouvelle porte d'entrée : spam borné par rate-limit IP + cooldown.

## Tests

### Serveur
- `/register` : émet un token unique (64 hex), persiste `SHA256(token)` dans `devices`, renvoie le token une seule fois.
- Rate-limit `/register` par IP (5/h + cooldown 30s) → 429 au-delà.
- `/vote`, `/profiles`, `/telemetry/ping` acceptent `deviceToken` et en dérivent le hash.
- Ancien champ `deviceHash` → 400.

### Client
- Token persisté (EncryptedSharedPreferences) et réutilisé entre sessions.
- `/register` appelé une seule fois (au 1er lancement), pas à chaque écriture.
- Écritures fonctionnent avec token ; message convivial si échec d'enregistrement.

## Fichiers touchés (estimation)

- `backend/src/index.ts`
- `backend/schema.sql` (aucune modification requise — à confirmer)
- `android/app/src/main/java/.../DeviceFingerprint.kt` (remplacé)
- `android/app/src/main/java/.../CommunityApiClient.kt`
- `android/app/src/main/java/.../MainActivity.kt`
- `android/app/build.gradle*` (dépendance `security-crypto`)
