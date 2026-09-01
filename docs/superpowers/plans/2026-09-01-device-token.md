# Device Token émis par le serveur — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer le `deviceHash` calculé côté client (`SHA256(ANDROID_ID:salt)`) par un token opaque de haute entropie émis par le serveur, stocké chiffré côté client et haché (`SHA256`) côté serveur — afin d'éliminer la trivialité du forgage.

**Architecture:** Le client ne connaît plus de sel/salt de device. Au premier lancement, il appelle `POST /api/device/register` ; le serveur génère un token 64-hex, stocke `device_hash = SHA256(token)` dans `devices`, et renvoie le token une fois. Le client le persiste dans `EncryptedSharedPreferences` et l'envoie en `deviceToken` dans `/vote`, `/profiles`, `/telemetry/ping`. Le serveur en dérive `SHA256(deviceToken)` et réutilise la colonne `device_hash` (et les clés `dev:*`) inchangée. L'ancien champ `deviceHash` est rejeté (400) → ré-enregistrement forcé.

**Tech Stack:** Cloudflare Workers (Hono + D1, Web Crypto), TypeScript, vitest (backend tests) ; Android (Kotlin), Gson, `androidx.security:security-crypto`.

---

## File Structure

| Fichier | Responsabilité | Action |
|---|---|---|
| `backend/src/device-identity.ts` | Fonctions pures : génération + normalisation/hash du token | **Créer** |
| `backend/src/device-identity.test.ts` | Tests unitaires de `device-identity.ts` | **Créer** |
| `backend/package.json` | Script `test` + devDependency `vitest` | **Modifier** |
| `backend/vitest.config.ts` | Config vitest (env node) | **Créer** |
| `backend/src/index.ts` | Route `/api/device/register` ; routes `/vote`, `/profiles`, `/telemetry/ping` lisent `deviceToken` | **Modifier** |
| `android/app/build.gradle.kts` | Dépendance `androidx.security:security-crypto` | **Modifier** |
| `android/app/src/main/java/com/kinou/gameassist/data/community/DeviceTokenStore.kt` | Persistance chiffrée du token | **Créer** |
| `android/app/src/main/java/com/kinou/gameassist/data/community/DeviceFingerprint.kt` | Ancien calcul SHA256 — **supprimer** | **Supprimer** |
| `android/app/src/main/java/com/kinou/gameassist/data/community/CommunityApiClient.kt` | `ensureDeviceToken()`, `registerDevice()`, champ `deviceToken` | **Modifier** |
| `android/app/src/main/java/com/kinou/gameassist/data/community/CommunityProfile.kt` | Renommer `PublishProfileRequest.deviceHash` → `deviceToken` | **Modifier** |

Note tests backend : le repo n'a actuellement aucun framework de test. On ajoute vitest pour tester la logique **pure** (pas besoin de D1). Les routes (dépendantes de D1/env) sont vérifiées manuellement via `wrangler dev` + `curl`.

---

### Task 1: Module `device-identity` + setup vitest (backend)

**Files:**
- Create: `backend/src/device-identity.ts`
- Create: `backend/src/device-identity.test.ts`
- Create: `backend/vitest.config.ts`
- Modify: `backend/package.json`

- [ ] **Step 1: Write the failing test**

Create `backend/src/device-identity.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { generateDeviceToken, normalizeDeviceToken, isValidHex64 } from './device-identity';

describe('isValidHex64', () => {
  it('accepte une chaîne hex 64 minuscules', () => {
    expect(isValidHex64('a'.repeat(64))).toBe(true);
  });
  it('rejette les longueurs et caractères invalides', () => {
    expect(isValidHex64('a'.repeat(63))).toBe(false);
    expect(isValidHex64('g'.repeat(64))).toBe(false);
    expect(isValidHex64(null)).toBe(false);
    expect(isValidHex64(12345)).toBe(false);
  });
});

describe('generateDeviceToken', () => {
  it('produit un token hex 64 et des tokens différents', () => {
    const a = generateDeviceToken();
    const b = generateDeviceToken();
    expect(a).toMatch(/^[a-f0-9]{64}$/);
    expect(a).not.toEqual(b);
  });
});

describe('normalizeDeviceToken', () => {
  it('hash un token valide en 64 hex, différent de la valeur d’entrée', async () => {
    const input = 'a'.repeat(64);
    const hash = await normalizeDeviceToken(input);
    expect(hash).toMatch(/^[a-f0-9]{64}$/);
    expect(hash).not.toEqual(input);
  });
  it('renvoie null pour un token invalide', async () => {
    expect(await normalizeDeviceToken(undefined)).toBeNull();
    expect(await normalizeDeviceToken('')).toBeNull();
    expect(await normalizeDeviceToken('xyz')).toBeNull();
    expect(await normalizeDeviceToken(123)).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && npm install --save-dev vitest && npx vitest run src/device-identity.test.ts`
Expected: FAIL — "Failed to resolve import './device-identity'".

- [ ] **Step 3: Write minimal implementation**

Create `backend/src/device-identity.ts`:

```ts
const HASH_REGEX = /^[a-f0-9]{64}$/i;

export function isValidHex64(value: unknown): value is string {
  return typeof value === 'string' && HASH_REGEX.test(value);
}

export function generateDeviceToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function normalizeDeviceToken(token: unknown): Promise<string | null> {
  if (!isValidHex64(token)) return null;
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && npx vitest run src/device-identity.test.ts`
Expected: PASS (3 suites, 6 tests).

- [ ] **Step 5: Add vitest config + test script**

Create `backend/vitest.config.ts`:

```ts
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
  },
});
```

Add the test script via npm (safer than hand-editing JSON):

```bash
cd backend && npm pkg set scripts.test="vitest run"
```

Verify full suite: `cd backend && npm test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/device-identity.ts backend/src/device-identity.test.ts backend/vitest.config.ts backend/package.json backend/package-lock.json
git commit -m "feat(device): module device-identity (+ vitest) pour token et hash serveur"
```

---

### Task 2: Route `/api/device/register` (backend)

**Files:**
- Modify: `backend/src/index.ts`

- [ ] **Step 1: Import the module**

Add near the top imports of `backend/src/index.ts`:

```ts
import { generateDeviceToken, normalizeDeviceToken } from './device-identity';
```

- [ ] **Step 2: Add the register route**

Insert this route after the `/api/profiles/:id/download` handler (before the telemetry ping route):

```ts
// 6. Enregistrement d'appareil : émet un token opaque (une seule fois) — identité non dérivable depuis ANDROID_ID
app.post('/api/device/register', verifySignature, async (c) => {
  const clientIp = getClientIp(c);
  try {
    let body: any;
    try {
      body = JSON.parse(c.get('rawBody'));
    } catch {
      return c.json({ success: false, error: 'Corps JSON invalide' }, 400);
    }

    if (!body || typeof body !== 'object') {
      return c.json({ success: false, error: 'Corps de requête invalide' }, 400);
    }

    // Anti-abus : cooldown 30s puis plafond 5/h par IP
    const regLimits = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:register_cd:${clientIp}`, maxRequests: 1, windowMs: 30_000, errorMessage: 'Trop de tentatives. Réessayez dans un instant.' },
      { key: `ip:register:${clientIp}`, maxRequests: 5, windowMs: 3600_000, errorMessage: 'Nombre maximal d\'enregistrements atteint pour cette adresse IP (5 par heure).' }
    ], c.executionCtx);

    if (!regLimits.allowed) {
      const retryMsg = regLimits.retryAfterSec ? ` (Réessayez dans ${regLimits.retryAfterSec}s)` : '';
      return c.json({ success: false, error: `${regLimits.error}${retryMsg}` }, 429);
    }

    const rawToken = generateDeviceToken();
    const tokenHash = await normalizeDeviceToken(rawToken);
    if (!tokenHash) {
      return c.json({ success: false, error: 'Erreur interne de génération d\'identité.' }, 500);
    }
    const now = Date.now();
    const version = typeof body.appVersion === 'string' ? body.appVersion.slice(0, 20) : '1.0.0';

    await c.env.DB.prepare(`
      INSERT INTO devices (device_hash, first_seen, last_seen, app_version, launch_count)
      VALUES (?, ?, ?, ?, 1)
      ON CONFLICT(device_hash) DO NOTHING
    `).bind(tokenHash, now, now, version).run();

    return c.json({ success: true, deviceToken: rawToken });
  } catch (err: any) {
    console.error('[API Error] POST /api/device/register:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});
```

- [ ] **Step 3: Type-check**

Run: `cd backend && npx tsc --noEmit`
Expected: no errors.

- [ ] **Step 4: Verify manually (wrangler dev + curl)**

Run in one terminal: `cd backend && npm run dev`
In another terminal (adjust for your local auth env):

```bash
TS=$(date +%s)
BODY='{"appVersion":"1.0.1"}'
HASH=$(printf '%s' "$BODY" | sha256sum | cut -d' ' -f1)
CANON="POST\n/api/device/register\n$TS\n$HASH"
SIG=$(printf '%b' "$CANON" | openssl dgst -sha256 -hmac "$APP_SECRET" -hex | awk '{print $2}')
curl -s -X POST "http://localhost:8787/api/device/register" \
  -H "Content-Type: application/json" \
  -H "X-Timestamp: $TS" \
  -H "X-Signature: $SIG" \
  -d "$BODY"
```

Expected: `{"success":true,"deviceToken":"<64 hex>"}`.

Repeat the same command immediately → Expected: `429` with « Trop de tentatives » (cooldown 30s).

- [ ] **Step 5: Commit**

```bash
git add backend/src/index.ts
git commit -m "feat(device): route POST /api/device/register émettant un token opaque"
```

---

### Task 3: Basculer `/vote`, `/profiles`, `/telemetry/ping` sur `deviceToken` (backend)

**Files:**
- Modify: `backend/src/index.ts`

- [ ] **Step 1: Route `/api/profiles` (publish)**

Replace at the top of the handler:
```ts
const { title, description, game_name, package_name, author_name, controller_type, profile_json, deviceHash } = body;
```
with:
```ts
const { title, description, game_name, package_name, author_name, controller_type, profile_json, deviceToken } = body;
```

Replace the device fingerprint validation block:
```ts
    // 2. Validate device fingerprint format
    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash)) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou manquante' }, 400);
    }
```
with:
```ts
    // 2. Valider l'identité d'appareil : token opaque émis par le serveur → hash interne
    const deviceIdentity = await normalizeDeviceToken(deviceToken);
    if (!deviceIdentity) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou manquante (réenregistrez l\'appareil)' }, 400);
    }
```

Replace the rate-limit keys `dev:pub_cd:${deviceHash}` and `dev:pub_daily:${deviceHash}` with `${deviceIdentity}`.

Replace the insert `.bind(...)` value `deviceHash,` with `deviceIdentity,` (the 9th positional bind in the `INSERT INTO profiles` block, after `finalJsonString`).

Expected end state: no remaining reference to `deviceHash` variable in this handler.

- [ ] **Step 2: Route `/api/profiles/:id/vote`**

Replace:
```ts
    const { deviceHash, vote } = body;

    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash) || vote === undefined) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou paramètre de vote manquant' }, 400);
    }
```
with:
```ts
    const { deviceToken, vote } = body;

    const deviceIdentity = await normalizeDeviceToken(deviceToken);
    if (!deviceIdentity || vote === undefined) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou paramètre de vote manquant' }, 400);
    }
```

Replace the misleading comment block:
```ts
    // 1. Anti-Sybil Rate Limiting (IP + cooldown appareil + plafond journalier appareil)
    //    deviceHash étant désormais signé (HMAC), il constitue une identité fiable.
```
with:
```ts
    // 1. Anti-Sybil Rate Limiting (IP + cooldown appareil + plafond journalier appareil)
    //    deviceIdentity = hash(deviceToken) : identité opaque, toujours falsifiable (copiable) — PAS une confiance matérielle.
```

Replace `dev:vote_cd:${deviceHash}` and `dev:vote_daily:${deviceHash}` with `${deviceIdentity}`.

Replace `device_hash` SQL binds that use `deviceHash`:
- Cancel: `.bind(profileId, deviceHash)` → `.bind(profileId, deviceIdentity)`
- Upsert: `.bind(profileId, deviceHash, hashedIp, voteVal, now)` → `.bind(profileId, deviceIdentity, hashedIp, voteVal, now)`

- [ ] **Step 3: Route `/api/telemetry/ping`**

Replace:
```ts
    const { deviceHash, appVersion } = body;

    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash)) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou manquante' }, 400);
    }
```
with:
```ts
    const { deviceToken, appVersion } = body;

    const deviceIdentity = await normalizeDeviceToken(deviceToken);
    if (!deviceIdentity) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou manquante' }, 400);
    }
```

Replace `ping:${deviceHash}` with `ping:${deviceIdentity}`.

Replace `.bind(deviceHash, now, now, version)` and `.bind(todayStr, deviceHash, version, now)` with `deviceIdentity`.

- [ ] **Step 4: Remove unused `HASH_REGEX`**

The constant `const HASH_REGEX = /^[a-f0-9]{64}$/i;` (line ~114) is now unused. Delete it.

- [ ] **Step 5: Type-check + full tests**

Run: `cd backend && npx tsc --noEmit && npm test`
Expected: type-check OK, vitest PASS.

- [ ] **Step 6: Manual sanity (curl)**

Repeat the vote/ping curl with a token obtained from `/api/device/register`, using `"deviceToken"` in the body. Expected: `success:true`. With the old key `"deviceHash"` → Expected: `400`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/index.ts
git commit -m "feat(device): routes /vote /profiles /telemetry via deviceToken (hash serveur)"
```

---

### Task 4: Dépendance `security-crypto` + `DeviceTokenStore` (Android)

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/kinou/gameassist/data/community/DeviceTokenStore.kt`

- [ ] **Step 1: Add dependency**

In `android/app/build.gradle.kts`, in the `dependencies` block add:

```kotlin
    // EncryptedSharedPreferences (stockage chiffré du token appareil)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

- [ ] **Step 2: Create `DeviceTokenStore.kt`**

```kotlin
package com.kinou.gameassist.data.community

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object DeviceTokenStore {
    private const val PREFS = "openmapper_device_token"
    private const val KEY = "device_token"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun get(context: Context): String? = prefs(context).getString(KEY, null)

    fun save(context: Context, token: String) {
        prefs(context).edit().putString(KEY, token).apply()
    }
}
```

- [ ] **Step 3: Verify compile (can fail if dependency unresolved — run after Gradle sync)**

> Build requiert les secrets du repo (`APP_SECRET`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`) via env ou `android/local.properties` ; sans eux `assembleDebug` échoue avant la compilation.

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (dependency download may take a while first time).

---

### Task 5: `CommunityApiClient` + `PublishProfileRequest` (Android)

**Files:**
- Modify: `android/app/src/main/java/com/kinou/gameassist/data/community/CommunityApiClient.kt`
- Modify: `android/app/src/main/java/com/kinou/gameassist/data/community/CommunityProfile.kt`
- Delete: `android/app/src/main/java/com/kinou/gameassist/data/community/DeviceFingerprint.kt`

- [ ] **Step 1: Rename `PublishProfileRequest.deviceHash`**

In `CommunityProfile.kt`, change:
```kotlin
@SerializedName("deviceHash") var deviceHash: String? = null
```
to:
```kotlin
@SerializedName("deviceToken") var deviceToken: String? = null
```

- [ ] **Step 2: Add `RegisterResponse` + token logic**

In `CommunityApiClient.kt`, add near the top (after the data class declarations):

```kotlin
data class RegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("deviceToken") val deviceToken: String?
)
```

Replace:
```kotlin
    private val deviceHash by lazy { DeviceFingerprint.getDeviceHash(context) }
```
with:
```kotlin
    @Volatile private var cachedDeviceToken: String? = null

    // Token opaque émis par le serveur via /api/device/register, persisté chiffré côté client.
    private suspend fun ensureDeviceToken(): String {
        cachedDeviceToken?.let { return it }
        DeviceTokenStore.get(context)?.let {
            cachedDeviceToken = it
            return it
        }
        val token = registerDevice()
        DeviceTokenStore.save(context, token)
        cachedDeviceToken = token
        return token
    }

    private suspend fun registerDevice(): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/api/device/register")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OpenMapper-Android/${BuildConfig.VERSION_NAME}")
            }
            val payload = mapOf("appVersion" to BuildConfig.VERSION_NAME)
            val jsonBody = gson.toJson(payload)
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)

            applySignature(conn, "POST", url.path, bodyBytes)
            conn.outputStream.use { it.write(bodyBytes) }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val response = gson.fromJson(json, RegisterResponse::class.java)
                response.deviceToken ?: throw Exception("Token absent de la réponse serveur")
            } else {
                val rawErr = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                throw Exception(extractServerErrorMessage(rawErr, responseCode))
            }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
```

- [ ] **Step 3: Remap payload field in `vote`**

Replace:
```kotlin
            val payload = mapOf(
                "deviceHash" to deviceHash,
                "vote" to voteValue
            )
```
with:
```kotlin
            val payload = mapOf(
                "deviceToken" to ensureDeviceToken(),
                "vote" to voteValue
            )
```

- [ ] **Step 4: Remap payload field in `publishProfile`**

Replace:
```kotlin
            request.deviceHash = deviceHash
```
with:
```kotlin
            request.deviceToken = ensureDeviceToken()
```

- [ ] **Step 5: Remap payload field in `sendTelemetryPing`**

Replace:
```kotlin
            val payload = mapOf(
                "deviceHash" to deviceHash,
                "appVersion" to appVersion
            )
```
with:
```kotlin
            val payload = mapOf(
                "deviceToken" to ensureDeviceToken(),
                "appVersion" to appVersion
            )
```

- [ ] **Step 6: Verify no remaining `deviceHash` / `DeviceFingerprint` references**

Run: `cd android && ./gradlew assembleDebug`
Note: if `DeviceFingerprint.kt` still exists, delete it (Task 5 Step 7) — otherwise remove usage so compile passes.

- [ ] **Step 7: Delete `DeviceFingerprint.kt`**

```bash
git rm android/app/src/main/java/com/kinou/gameassist/data/community/DeviceFingerprint.kt
```

- [ ] **Step 8: Build**

> Nécessite `APP_SECRET`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` (env ou `local.properties`).

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Manual verification**

- First launch (fresh install / cleared data) → app registers via `/api/device/register`, token stored, ping sent.
- Subsequent launches → no `/register` call (token reused), ping + vote + publish use `deviceToken`.

- [ ] **Step 10: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/kinou/gameassist/data/community/
git commit -m "feat(device): token serveur chiffré (EncryptedSharedPreferences) côté Android"
```

---

## Self-Review

**Spec coverage:**
- Ré-enregistrement forcé → Task 3 rejette `deviceHash` (400). ✔
- Route dédiée `/api/device/register` → Task 2. ✔
- Anti-abus rate-limit IP + cooldown → Task 2 (`ip:register_cd`, `ip:register`). ✔
- `deviceToken` brut + hash serveur (DB inchangée) → Tasks 2 & 3. ✔
- EncryptedSharedPreferences → Task 4. ✔
- Client : `ensureDeviceToken()`, `registerDevice()`, rename → Task 5. ✔
- `/download` inchangé → aucune modification (confirmé : corps `{}`, pas d'identité). ✔
- Commentaire trompeur (index.ts:688) corrigé → Task 3 Step 2. ✔

**Placeholder scan:** aucun TBD/TODO ; tous les steps contiennent code/commandes exacts. ✔

**Type consistency:** `deviceToken` (fil) → `deviceIdentity` (hash interne) → `device_hash` (DB). `normalizeDeviceToken`, `generateDeviceToken`, `isValidHex64` cohérents entre Task 1 (test), Task 2/3 (usage), et client (Task 5). ✔

**Limite connue:** le backend n'a pas de harnais D1 — les routes sont vérifiées via `wrangler dev` + `curl` (Task 2/3) et `tsc` + tests unitaires purs. Aucune route ne dépend d'un module exporté, donc pas de test unitaire de route supprimé.
