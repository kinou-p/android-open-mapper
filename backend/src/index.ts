import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { generateDeviceToken, normalizeDeviceToken } from './device-identity';

type Bindings = {
  DB: D1Database;
  IP_SALT?: string;
  APP_SECRET?: string;
};

type Variables = {
  rawBody: string;
};

const app = new Hono<{ Bindings: Bindings; Variables: Variables }>();

// 1. CORS global — lecture publique autorisée depuis n'importe quelle origine.
// Les routes d'écriture POST sont protégées par le middleware verifySignature ci-dessous.
app.use('*', cors({
  origin: '*',
  allowMethods: ['GET', 'POST', 'OPTIONS'],
  allowHeaders: [
    'Content-Type',
    'Accept',
    'User-Agent',
    'Authorization',
    'X-Requested-With',
    'Origin',
    'X-Timestamp',
    'X-Signature',
  ],
  maxAge: 86400,
}));

const SIGNATURE_WINDOW_SEC = 300;
const MAX_BODY_BYTES = 32 * 1024;

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    encoder.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256Hex(data: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

function timingSafeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

// Cache anti-rejeu en mémoire des signatures HMAC récemment validées (TTL = SIGNATURE_WINDOW_SEC = 300s).
// Protège l'instance contre le rejeu immédiat des requêtes signées interceptées.
const MAX_SEEN_SIGNATURES = 5000;
const seenSignatures = new Map<string, number>();

export function resetSeenSignaturesCache(): void {
  seenSignatures.clear();
}

export function isSignatureReplayed(signature: string, nowSec: number): boolean {
  // 1. Nettoyage paresseux périodique des signatures expirées
  if (seenSignatures.size > 50) {
    for (const [sig, exp] of seenSignatures.entries()) {
      if (exp <= nowSec) {
        seenSignatures.delete(sig);
      }
    }
  }

  // 2. Éviction FIFO si le cache atteint sa capacité maximale
  if (seenSignatures.size >= MAX_SEEN_SIGNATURES) {
    const oldestKey = seenSignatures.keys().next().value;
    if (oldestKey) {
      seenSignatures.delete(oldestKey);
    }
  }

  if (seenSignatures.has(signature)) {
    return true; // Déjà traitée (Rejeu détecté)
  }

  seenSignatures.set(signature, nowSec + SIGNATURE_WINDOW_SEC);
  return false;
}

/**
 * Middleware de protection des routes d'écriture POST.
 *
 * IMPORTANT — modèle de confiance : la clé APP_SECRET est embarquée dans l'APK
 * Android (extractible par décompilation). La signature HMAC-SHA256 apporte donc
 * uniquement une garantie d'INTÉGRITÉ/anti-falsification du corps (chaîne canonique
 * `METHOD\nPATH\nTIMESTAMP\nBODY_SHA256`), PAS une authentification réelle de l'appelant.
 * La véritable défense anti-abus repose sur :
 *   - les limites de débit serveur (IP + appareil),
 *   - le jeton d'appareil opaque émis par `/api/device/register` (émission rate-limitée),
 *   validé et haché côté serveur sur les routes sensibles.
 * Rejette les corps > 32 Ko avant toute lecture en mémoire.
 */
const verifySignature = async (c: any, next: any) => {
  const secret = c.env.APP_SECRET;
  if (!secret) {
    console.error('[Auth] APP_SECRET non configuré — rejet des routes POST');
    return c.json({ error: "Serveur mal configuré (secret d'authentification manquant)" }, 503);
  }

  // 1. Rejet précoce des corps trop volumineux si Content-Length est fourni.
  //    Si Content-Length est absent (ex: flux HTTP/2, HTTP/3 ou encodage chunked),
  //    la requête est acceptée et la taille exacte en octets sera vérifiée après lecture.
  const rawContentLength = c.req.header('content-length');
  if (rawContentLength !== null && rawContentLength !== undefined) {
    const contentLength = parseInt(rawContentLength, 10);
    if (!Number.isFinite(contentLength) || contentLength < 0 || contentLength > MAX_BODY_BYTES) {
      return c.json({ success: false, error: 'La taille de la requête dépasse la limite autorisée (32 Ko max)' }, 413);
    }
  }

  // 2. Rejet des requêtes sans en-têtes de signature ou horloge invalide
  const timestamp = c.req.header('x-timestamp') ?? '';
  const signature = (c.req.header('x-signature') ?? '').toLowerCase();
  if (!timestamp || !signature) {
    return c.json({ error: 'Signature manquante' }, 401);
  }
  const ts = parseInt(timestamp, 10);
  const nowSec = Math.floor(Date.now() / 1000);
  if (!Number.isFinite(ts) || Math.abs(nowSec - ts) > SIGNATURE_WINDOW_SEC) {
    return c.json({ error: 'Requête expirée ou horloge invalide' }, 401);
  }

  // 3. Lecture du corps en streaming borné pour éviter tout DoS mémoire sur flux chunked / sans Content-Length
  let rawBody = '';
  if (c.req.raw.body) {
    const reader = c.req.raw.body.getReader();
    const chunks: Uint8Array[] = [];
    let received = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        if (value) {
          received += value.byteLength;
          if (received > MAX_BODY_BYTES) {
            await reader.cancel();
            return c.json({ success: false, error: 'La taille de la requête dépasse la limite autorisée (32 Ko max)' }, 413);
          }
          chunks.push(value);
        }
      }
    } finally {
      reader.releaseLock();
    }
    const combined = new Uint8Array(received);
    let offset = 0;
    for (const chunk of chunks) {
      combined.set(chunk, offset);
      offset += chunk.byteLength;
    }
    rawBody = new TextDecoder().decode(combined);
  } else {
    rawBody = await c.req.text();
    if (new TextEncoder().encode(rawBody).byteLength > MAX_BODY_BYTES) {
      return c.json({ success: false, error: 'La taille de la requête dépasse la limite autorisée (32 Ko max)' }, 413);
    }
  }

  const path = new URL(c.req.url).pathname;
  const canonical = `${c.req.method}\n${path}\n${timestamp}\n${await sha256Hex(rawBody)}`;
  const expected = await hmacSha256Hex(secret, canonical);
  if (!timingSafeEqualHex(signature, expected)) {
    return c.json({ error: 'Signature invalide' }, 401);
  }

  // 4. Protection anti-rejeu : rejet des signatures déjà consommées
  // Échelon 1 : filtre mémoire rapide local à l'isolate
  if (isSignatureReplayed(signature, nowSec)) {
    return c.json({ error: 'Signature déjà utilisée (Replay Attack détectée)' }, 401);
  }

  // Échelon 2 : barrière atomique multi-régions persistée dans la base D1
  if (c.env.DB) {
    try {
      const sigKey = `sig:${signature}`;
      const replayCheck = await c.env.DB.prepare(`
        INSERT INTO rate_limits (key, last_seen, request_count, window_start)
        VALUES (?1, ?2, 1, ?2)
        ON CONFLICT(key) DO NOTHING
        RETURNING key
      `).bind(sigKey, Date.now()).first();

      if (!replayCheck) {
        return c.json({ error: 'Signature déjà utilisée (Replay Attack détectée)' }, 401);
      }
    } catch (dbErr: any) {
      console.error('[Anti-Replay] Erreur vérification D1:', dbErr);
    }
  }

  // 5. Corps authentifié mis à disposition des handlers
  c.set('rawBody', rawBody);
  await next();
};

app.use('*', async (c, next) => {
  await next();
  c.res.headers.set('X-Content-Type-Options', 'nosniff');
  c.res.headers.set('X-Frame-Options', 'DENY');
  c.res.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
  c.res.headers.set('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  c.res.headers.set('Permissions-Policy', 'geolocation=(), camera=(), microphone=()');
});

// Helpers
const MAX_PROFILE_JSON_BYTES = 16 * 1024; // 16 KB max per profile

// In-memory sliding rate limit filter to reduce D1 write load under heavy traffic
// Utilise 2 buckets temporels partitionnés par minute pour une éviction O(1) sans parcours itératif
interface MemBucket {
  windowMinute: number;
  entries: Map<string, number>;
}

let currentBucket: MemBucket = { windowMinute: 0, entries: new Map() };
let previousBucket: MemBucket = { windowMinute: 0, entries: new Map() };
const MAX_MEM_BUCKET_ENTRIES = 5000;

export function resetMemRateLimitCache(): void {
  currentBucket = { windowMinute: 0, entries: new Map() };
  previousBucket = { windowMinute: 0, entries: new Map() };
}

/**
 * Normalise les adresses IPv4 et IPv6 pour les clés de rate limiting.
 * En IPv6, tronque l'adresse au sous-réseau /64 (les 4 premiers blocs de 16 bits)
 * pour éviter le contournement du rate limiter par rotation d'adresses IPv6 au sein du même préfixe.
 */
export function normalizeIpForRateLimit(ip: string): string {
  const trimmed = ip.trim();
  if (!trimmed.includes(':')) {
    return trimmed; // IPv4 standard
  }
  // Gestion des adresses IPv4-mapped IPv6 (ex: ::ffff:192.0.2.128)
  if (trimmed.toLowerCase().startsWith('::ffff:') && trimmed.indexOf('.', 7) !== -1) {
    return trimmed.substring(7);
  }
  // Normalisation IPv6 vers le sous-réseau /64 (les 4 premiers groupes de 16 bits)
  try {
    const parts = trimmed.split('::');
    let left: string[] = [];
    let right: string[] = [];
    if (parts.length === 2) {
      left = parts[0] ? parts[0].split(':').filter(Boolean) : [];
      right = parts[1] ? parts[1].split(':').filter(Boolean) : [];
      const missing = 8 - (left.length + right.length);
      const middle = Array(Math.max(0, missing)).fill('0');
      const full = [...left, ...middle, ...right];
      return full.slice(0, 4).map(h => (h ? h.toLowerCase() : '0')).join(':') + '::/64';
    } else if (parts.length === 1) {
      const full = parts[0].split(':').filter(Boolean);
      return full.slice(0, 4).map(h => (h ? h.toLowerCase() : '0')).join(':') + '::/64';
    }
  } catch {
    // Fallback de sécurité
  }
  return trimmed;
}

export function getClientIp(c: any): string {
  const cfIp = c.req.header('cf-connecting-ip');
  if (cfIp && cfIp.trim().length > 0) {
    return cfIp.trim();
  }
  // En environnement local de développement ou fallback sécurisé
  return '127.0.0.1';
}

export function getRateLimitIp(c: any): string {
  return normalizeIpForRateLimit(getClientIp(c));
}

async function hashIp(ip: string, salt?: string): Promise<string | null> {
  if (!salt) {
    // Si aucun sel sécurisé n'est configuré en production, on ne persiste pas d'empreinte IP prédictible
    return null;
  }
  const encoder = new TextEncoder();
  const data = encoder.encode(`${ip}:${salt}`);
  const hashBuf = await crypto.subtle.digest('SHA-256', data);
  const hashArr = Array.from(new Uint8Array(hashBuf));
  return hashArr.map(b => b.toString(16).padStart(2, '0')).join('').slice(0, 32);
}

export function recordMemRateLimit(
  key: string,
  now: number,
  windowMs: number,
  maxRequests: number
): { blocked: boolean; retryAfterSec: number } {
  // Le pré-filtre par bucket 1-minute n'est valide que pour les règles avec fenêtre de ~60 secondes
  if (windowMs !== 60_000) {
    return { blocked: false, retryAfterSec: 0 };
  }

  const currentMinute = Math.floor(now / 60_000);

  // Rotation des buckets en O(1) sans boucle d'itération
  if (currentBucket.windowMinute !== currentMinute) {
    if (currentBucket.windowMinute === currentMinute - 1) {
      previousBucket = currentBucket;
    } else {
      previousBucket = { windowMinute: currentMinute - 1, entries: new Map() };
    }
    currentBucket = { windowMinute: currentMinute, entries: new Map() };
  }

  const currentCount = currentBucket.entries.get(key) || 0;
  const previousCount = previousBucket.entries.get(key) || 0;

  // Calcul glissant proportionnel à l'avancement dans la minute courante
  const elapsedRatio = (now % 60_000) / 60_000;
  const prevWeight = Math.max(0, 1 - elapsedRatio);
  const estimatedCount = Math.floor(previousCount * prevWeight) + currentCount;

  if (estimatedCount >= maxRequests) {
    const retryAfterMs = Math.max(1000, 60_000 - (now % 60_000));
    return {
      blocked: true,
      retryAfterSec: Math.max(1, Math.ceil(retryAfterMs / 1000))
    };
  }

  // Stockage borné pour éviter toute allocation mémoire excessive par bucket
  if (currentBucket.entries.size < MAX_MEM_BUCKET_ENTRIES || currentBucket.entries.has(key)) {
    currentBucket.entries.set(key, currentCount + 1);
  }

  return { blocked: false, retryAfterSec: 0 };
}

function checkNumber(val: any, min: number, max: number, name: string): string | null {
  if (val === undefined || val === null) return null;
  if (typeof val !== 'number' || !Number.isFinite(val)) {
    return `${name} doit être un nombre valide`;
  }
  if (val < min || val > max) {
    return `${name} hors limites (${min} à ${max})`;
  }
  return null;
}

function checkBoolean(val: any, name: string): string | null {
  if (val === undefined || val === null) return null;
  if (typeof val !== 'boolean') {
    return `${name} doit être un booléen`;
  }
  return null;
}

function checkString(val: any, maxLen: number, name: string): string | null {
  if (val === undefined || val === null) return null;
  if (typeof val !== 'string') {
    return `${name} doit être une chaîne de caractères`;
  }
  if (val.length > maxLen) {
    return `${name} trop long (max ${maxLen} caractères)`;
  }
  return null;
}

function validateProfileStructure(obj: any): { valid: boolean; error?: string } {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return { valid: false, error: 'La configuration doit être un objet JSON valide' };
  }

  // Validate root string fields if present
  const rootIdErr = checkString(obj.id, 100, 'id');
  if (rootIdErr) return { valid: false, error: rootIdErr };
  const rootNameErr = checkString(obj.name, 100, 'name');
  if (rootNameErr) return { valid: false, error: rootNameErr };
  const rootPkgErr = checkString(obj.package_name || obj.packageName, 150, 'package_name');
  if (rootPkgErr) return { valid: false, error: rootPkgErr };
  const rootDescErr = checkString(obj.description, 500, 'description');
  if (rootDescErr) return { valid: false, error: rootDescErr };

  // Validate Joystick if present
  if (obj.joystick) {
    if (typeof obj.joystick !== 'object' || Array.isArray(obj.joystick)) {
      return { valid: false, error: 'Joystick doit être un objet JSON valide' };
    }
    const j = obj.joystick;
    const err =
      checkNumber(j.centerX ?? j.center_x, -0.5, 1.5, 'Joystick center_x') ||
      checkNumber(j.centerY ?? j.center_y, -0.5, 1.5, 'Joystick center_y') ||
      checkNumber(j.radius, 0.001, 1.0, 'Joystick radius') ||
      checkNumber(j.deadzone, 0.0, 0.5, 'Joystick deadzone') ||
      checkNumber(j.outerDeadzone ?? j.outer_deadzone, 0.5, 1.0, 'Joystick outer_deadzone') ||
      checkNumber(j.sprintThreshold ?? j.sprint_threshold, 0.2, 1.0, 'Joystick sprint_threshold') ||
      checkNumber(j.jiggleRandomness ?? j.jiggle_randomness, 0.0, 1.0, 'Joystick jiggle_randomness') ||
      checkNumber(j.jiggleSpeed ?? j.jiggle_speed, 0.1, 5.0, 'Joystick jiggle_speed') ||
      checkBoolean(j.enabled, 'Joystick enabled') ||
      checkBoolean(j.raaKeepAlive ?? j.raa_keep_alive, 'Joystick raa_keep_alive') ||
      checkBoolean(j.jiggleStrafe ?? j.jiggle_strafe, 'Joystick jiggle_strafe') ||
      checkBoolean(j.jiggleHumanize ?? j.jiggle_humanize, 'Joystick jiggle_humanize');
    if (err) return { valid: false, error: err };
  }

  // Validate Camera if present
  if (obj.camera) {
    if (typeof obj.camera !== 'object' || Array.isArray(obj.camera)) {
      return { valid: false, error: 'Camera doit être un objet JSON valide' };
    }
    const c = obj.camera;
    const err =
      checkNumber(c.rectX1 ?? c.rect_x1, -0.5, 1.5, 'Camera rect_x1') ||
      checkNumber(c.rectY1 ?? c.rect_y1, -0.5, 1.5, 'Camera rect_y1') ||
      checkNumber(c.rectX2 ?? c.rect_x2, -0.5, 1.5, 'Camera rect_x2') ||
      checkNumber(c.rectY2 ?? c.rect_y2, -0.5, 1.5, 'Camera rect_y2') ||
      checkNumber(c.sensitivityX ?? c.sensitivity_x, 0.1, 10.0, 'Camera sensitivity_x') ||
      checkNumber(c.sensitivityY ?? c.sensitivity_y, 0.1, 10.0, 'Camera sensitivity_y') ||
      checkNumber(c.deadzone, 0.0, 0.5, 'Camera deadzone') ||
      checkNumber(c.outerDeadzone ?? c.outer_deadzone, 0.5, 1.0, 'Camera outer_deadzone') ||
      checkNumber(c.smoothing, 0.0, 1.0, 'Camera smoothing') ||
      checkNumber(c.acceleration, 0.5, 5.0, 'Camera acceleration') ||
      checkNumber(c.flickBoost ?? c.flick_boost, 0.5, 10.0, 'Camera flick_boost') ||
      checkNumber(c.flickThreshold ?? c.flick_threshold, 0.1, 1.0, 'Camera flick_threshold') ||
      checkNumber(c.adsSensitivityMultiplier ?? c.ads_sensitivity_multiplier, 0.05, 5.0, 'Camera ads_sensitivity_multiplier') ||
      checkNumber(c.maxStepPixels ?? c.max_step_pixels, 1.0, 150.0, 'Camera max_step_pixels') ||
      checkBoolean(c.enabled, 'Camera enabled') ||
      checkBoolean(c.flickAdsSafety ?? c.flick_ads_safety, 'Camera flick_ads_safety') ||
      checkBoolean(c.adsSensitivityEnabled ?? c.ads_sensitivity_enabled, 'Camera ads_sensitivity_enabled') ||
      checkBoolean(c.invertX ?? c.invert_x, 'Camera invert_x') ||
      checkBoolean(c.invertY ?? c.invert_y, 'Camera invert_y') ||
      checkString(c.responseCurve ?? c.response_curve, 50, 'Camera response_curve');
    if (err) return { valid: false, error: err };
  }

  // Validate Buttons if present
  if (obj.buttons !== undefined && obj.buttons !== null) {
    if (!Array.isArray(obj.buttons) || obj.buttons.length > 50) {
      return { valid: false, error: 'Liste de boutons invalide (maximum 50 boutons autorisés)' };
    }
    for (let i = 0; i < obj.buttons.length; i++) {
      const btn = obj.buttons[i];
      if (!btn || typeof btn !== 'object' || Array.isArray(btn)) {
        return { valid: false, error: `Structure de bouton #${i + 1} invalide` };
      }
      const xVal = btn.x;
      const yVal = btn.y;
      const err =
        checkString(btn.id, 100, `Bouton #${i + 1} id`) ||
        checkString(btn.label, 100, `Bouton #${i + 1} label`) ||
        checkString(btn.gamepadButton ?? btn.gamepad_button, 50, `Bouton #${i + 1} gamepad_button`) ||
        checkNumber(xVal, -0.5, 1.5, `Bouton #${i + 1} x`) ||
        checkNumber(yVal, -0.5, 1.5, `Bouton #${i + 1} y`) ||
        checkNumber(btn.radius, 0.001, 0.5, `Bouton #${i + 1} radius`) ||
        checkString(btn.mode, 20, `Bouton #${i + 1} mode`) ||
        checkString(btn.role, 20, `Bouton #${i + 1} role`);
      if (err) return { valid: false, error: err };
    }
  }

  // Validate Settings if present
  if (obj.settings) {
    if (typeof obj.settings !== 'object' || Array.isArray(obj.settings)) {
      return { valid: false, error: 'Settings doit être un objet JSON valide' };
    }
    const s = obj.settings;
    const err =
      checkNumber(s.polling_rate_hz ?? s.pollingRateHz, 30, 240, 'Settings polling_rate_hz') ||
      checkNumber(s.haptic_intensity ?? s.hapticIntensity, 0.0, 1.0, 'Settings haptic_intensity') ||
      checkBoolean(s.haptic_feedback ?? s.hapticFeedback, 'Settings haptic_feedback') ||
      checkBoolean(s.haptic_device ?? s.hapticDevice, 'Settings haptic_device') ||
      checkBoolean(s.haptic_controller ?? s.hapticController, 'Settings haptic_controller') ||
      checkBoolean(s.haptic_fire ?? s.hapticFire, 'Settings haptic_fire') ||
      checkBoolean(s.haptic_reload ?? s.hapticReload, 'Settings haptic_reload');
    if (err) return { valid: false, error: err };
  }

  return { valid: true };
}

function escapeSqlLike(str: string): string {
  return str.replace(/[%_\\]/g, '\\$&');
}

/**
 * High-performance sliding window rate limiter backed by D1 SQLite with in-memory caching filter.
 * Uses atomic upsert with RETURNING to eliminate TOCTOU race conditions and protects against D1 write overload.
 */
async function checkRateLimit(
  db: D1Database,
  key: string,
  maxRequests: number,
  windowMs: number,
  ctx?: { waitUntil: (promise: Promise<any>) => void } | any
): Promise<{ allowed: boolean; remaining: number; retryAfterSec: number }> {
  const now = Date.now();
  const windowStart = now - windowMs;

  // 1. Fast in-memory check to reject obvious burst spam without hitting D1 writes
  const memCheck = recordMemRateLimit(key, now, windowMs, maxRequests);
  if (memCheck.blocked) {
    return {
      allowed: false,
      remaining: 0,
      retryAfterSec: memCheck.retryAfterSec
    };
  }

  try {
    // Probabilistic cleanup of expired rate limits (1% chance per call)
    if (Math.random() < 0.01) {
      const expiredThreshold = now - 24 * 60 * 60 * 1000; // 24h retention
      const cleanupPromise = db.prepare('DELETE FROM rate_limits WHERE window_start < ?').bind(expiredThreshold).run().catch(() => {});
      if (ctx && typeof ctx.waitUntil === 'function') {
        ctx.waitUntil(cleanupPromise);
      }
    }

    // Atomic upsert with RETURNING to eliminate TOCTOU race conditions
    const row: any = await db.prepare(`
      INSERT INTO rate_limits (key, last_seen, request_count, window_start)
      VALUES (?1, ?2, 1, ?2)
      ON CONFLICT(key) DO UPDATE SET
        request_count = CASE 
          WHEN window_start < ?3 THEN 1 
          ELSE request_count + 1 
        END,
        window_start = CASE 
          WHEN window_start < ?3 THEN ?2 
          ELSE window_start 
        END,
        last_seen = ?2
      RETURNING request_count, window_start
    `).bind(key, now, windowStart).first();

    if (!row) {
      return { allowed: true, remaining: maxRequests - 1, retryAfterSec: 0 };
    }

    const count = Number(row.request_count);
    const winStart = Number(row.window_start);

    if (count > maxRequests) {
      const retryAfterMs = (winStart + windowMs) - now;
      return {
        allowed: false,
        remaining: 0,
        retryAfterSec: Math.max(1, Math.ceil(retryAfterMs / 1000))
      };
    }

    return {
      allowed: true,
      remaining: Math.max(0, maxRequests - count),
      retryAfterSec: 0
    };
  } catch (err) {
    console.error('[RateLimit Error]', err);
    // Fail-open on database rate_limits lookup error to avoid service disruption
    return { allowed: true, remaining: maxRequests, retryAfterSec: 0 };
  }
}

/**
 * Batched multi-key rate limiter to consolidate multiple rate limit checks into a single D1 batch query.
 * Reduces roundtrips for POST /api/profiles and other composite endpoints.
 * (Note : db.batch() n'est pas transactionnel ; acceptable ici car chaque upsert est
 *  atomique individuellement et le limiteur échoue en mode ouvert — fail-open — sur erreur.)
 */
async function checkMultiRateLimits(
  db: D1Database,
  items: { key: string; maxRequests: number; windowMs: number; errorMessage: string }[],
  ctx?: { waitUntil: (promise: Promise<any>) => void } | any
): Promise<{ allowed: boolean; error?: string; retryAfterSec?: number }> {
  const now = Date.now();

  // 1. Fast in-memory pre-check
  for (const item of items) {
    const memCheck = recordMemRateLimit(item.key, now, item.windowMs, item.maxRequests);
    if (memCheck.blocked) {
      return {
        allowed: false,
        error: item.errorMessage,
        retryAfterSec: memCheck.retryAfterSec
      };
    }
  }

  try {
    const statements = items.map(item => {
      const windowStart = now - item.windowMs;
      return db.prepare(`
        INSERT INTO rate_limits (key, last_seen, request_count, window_start)
        VALUES (?1, ?2, 1, ?2)
        ON CONFLICT(key) DO UPDATE SET
          request_count = CASE 
            WHEN window_start < ?3 THEN 1 
            ELSE request_count + 1 
          END,
          window_start = CASE 
            WHEN window_start < ?3 THEN ?2 
            ELSE window_start 
          END,
          last_seen = ?2
        RETURNING request_count, window_start
      `).bind(item.key, now, windowStart);
    });

    const batchResults = await db.batch(statements);

    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      const res = batchResults[i];
      const row: any = res?.results?.[0];
      if (row) {
        const count = Number(row.request_count);
        const winStart = Number(row.window_start);
        if (count > item.maxRequests) {
          const retryAfterMs = (winStart + item.windowMs) - now;
          return {
            allowed: false,
            error: item.errorMessage,
            retryAfterSec: Math.max(1, Math.ceil(retryAfterMs / 1000))
          };
        }
      }
    }

    return { allowed: true };
  } catch (err) {
    console.error('[MultiRateLimit Error]', err);
    return { allowed: true };
  }
}

// Health & Info
app.get('/', (c) => {
  return c.json({
    name: 'OpenMapper Community API',
    version: '1.1.4',
    status: 'online',
    endpoints: [
      'GET /api/profiles',
      'GET /api/profiles/:id',
      'POST /api/profiles',
      'POST /api/profiles/:id/vote',
      'POST /api/profiles/:id/download',
      'POST /api/telemetry/ping',
      'GET /api/stats'
    ]
  });
});

// 1. List Community Profiles
app.get('/api/profiles', async (c) => {
  const rateLimitIp = getRateLimitIp(c);
  try {
    const ipLimit = await checkRateLimit(c.env.DB, `ip:list:${rateLimitIp}`, 60, 60_000, c.executionCtx);
    if (!ipLimit.allowed) {
      return c.json({ success: false, error: 'Trop de requêtes sur la liste des profils. Veuillez patienter.' }, 429);
    }

    c.header('Cache-Control', 'public, max-age=10, s-maxage=30, stale-while-revalidate=30');

    const rawGame = c.req.query('game');
    const rawSearch = c.req.query('search');
    const game = rawGame ? rawGame.trim().slice(0, 100) : undefined;
    const search = rawSearch ? rawSearch.trim().slice(0, 100) : undefined;
    const sort = c.req.query('sort') || 'popular';

    const rawPage = parseInt(c.req.query('page') || '1', 10);
    const page = Number.isFinite(rawPage) && rawPage >= 1 ? rawPage : 1;

    const rawLimit = parseInt(c.req.query('limit') || '20', 10);
    const limit = Number.isFinite(rawLimit) ? Math.min(50, Math.max(1, rawLimit)) : 20;

    const offset = (page - 1) * limit;

    let query = `
      SELECT 
        id, title, description, game_name, package_name, author_name, controller_type,
        likes_count, dislikes_count, downloads_count, created_at, updated_at
      FROM profiles
      WHERE 1=1
    `;
    const params: any[] = [];

    if (game) {
      query += ` AND (package_name = ? OR game_name LIKE ? ESCAPE '\\')`;
      const escapedGame = `%${escapeSqlLike(game)}%`;
      params.push(game, escapedGame);
    }

    if (search) {
      query += ` AND (title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR author_name LIKE ? ESCAPE '\\' OR game_name LIKE ? ESCAPE '\\')`;
      const s = `%${escapeSqlLike(search)}%`;
      params.push(s, s, s, s);
    }

    if (sort === 'recent') {
      query += ` ORDER BY created_at DESC`;
    } else if (sort === 'downloads') {
      query += ` ORDER BY downloads_count DESC, likes_count DESC`;
    } else {
      query += ` ORDER BY (likes_count - dislikes_count) DESC, likes_count DESC, created_at DESC`;
    }

    query += ` LIMIT ? OFFSET ?`;
    params.push(limit, offset);

    const { results } = await c.env.DB.prepare(query).bind(...params).all();

    // Count total
    let countQuery = `SELECT COUNT(*) as total FROM profiles WHERE 1=1`;
    const countParams: any[] = [];
    if (game) {
      countQuery += ` AND (package_name = ? OR game_name LIKE ? ESCAPE '\\')`;
      countParams.push(game, `%${escapeSqlLike(game)}%`);
    }
    if (search) {
      countQuery += ` AND (title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR author_name LIKE ? ESCAPE '\\' OR game_name LIKE ? ESCAPE '\\')`;
      const s = `%${escapeSqlLike(search)}%`;
      countParams.push(s, s, s, s);
    }
    const totalResult: any = await c.env.DB.prepare(countQuery).bind(...countParams).first();

    return c.json({
      success: true,
      page,
      limit,
      total: totalResult?.total || 0,
      profiles: results || []
    });
  } catch (err: any) {
    console.error('[API Error] GET /api/profiles:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 2. Get Single Profile Details (Excluding sensitive PII client_ip & device_hash)
app.get('/api/profiles/:id', async (c) => {
  const id = c.req.param('id');
  const rateLimitIp = getRateLimitIp(c);
  try {
    const ipLimit = await checkRateLimit(c.env.DB, `ip:detail:${rateLimitIp}`, 120, 60_000, c.executionCtx);
    if (!ipLimit.allowed) {
      return c.json({ success: false, error: 'Trop de requêtes sur le détail des profils. Veuillez patienter.' }, 429);
    }

    c.header('Cache-Control', 'public, max-age=30, s-maxage=60, stale-while-revalidate=60');

    const profile = await c.env.DB.prepare(`
      SELECT 
        id, title, description, game_name, package_name, author_name, controller_type,
        profile_json, likes_count, dislikes_count, downloads_count, created_at, updated_at
      FROM profiles WHERE id = ?
    `).bind(id).first();

    if (!profile) {
      return c.json({ success: false, error: 'Profile not found' }, 404);
    }

    return c.json({
      success: true,
      profile
    });
  } catch (err: any) {
    console.error('[API Error] GET /api/profiles/:id:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 3. Publish a Community Profile
app.post('/api/profiles', verifySignature, async (c) => {
  const clientIp = getClientIp(c);
  const rateLimitIp = normalizeIpForRateLimit(clientIp);
  try {
    // Corps déjà authentifié et borné par le middleware verifySignature
    const rawText = c.get('rawBody');

    let body: any;
    try {
      body = JSON.parse(rawText);
    } catch {
      return c.json({ success: false, error: 'Format JSON invalide dans le corps de la requête' }, 400);
    }

    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      return c.json({ success: false, error: 'Corps de requête invalide' }, 400);
    }

    const { title, description, game_name, package_name, author_name, controller_type, profile_json, deviceToken } = body;

    // Strict type checks on required and optional fields
    if (
      typeof title !== 'string' || !title.trim() ||
      typeof game_name !== 'string' || !game_name.trim() ||
      typeof package_name !== 'string' || !package_name.trim() ||
      !profile_json || (typeof profile_json !== 'string' && typeof profile_json !== 'object')
    ) {
      return c.json({ success: false, error: 'Champs requis manquants ou invalides (titre, nom du jeu, package_name, configuration)' }, 400);
    }

    if (typeof description !== 'undefined' && typeof description !== 'string') {
      return c.json({ success: false, error: 'Le champ description doit être une chaîne de caractères' }, 400);
    }
    if (typeof author_name !== 'undefined' && typeof author_name !== 'string') {
      return c.json({ success: false, error: 'Le champ author_name doit être une chaîne de caractères' }, 400);
    }
    if (typeof controller_type !== 'undefined' && typeof controller_type !== 'string') {
      return c.json({ success: false, error: 'Le champ controller_type doit être une chaîne de caractères' }, 400);
    }

    // 2. Valider l'identité d'appareil : token opaque émis par le serveur → hash interne
    const deviceIdentity = await normalizeDeviceToken(deviceToken);
    if (!deviceIdentity) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou manquante (réenregistrez l\'appareil)' }, 400);
    }

    // 3. Anti-spam & Cooldown: Consolidated Batched Rate Limiting (Single D1 batch execution)
    const multiLimits = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:pub_cd:${rateLimitIp}`, maxRequests: 1, windowMs: 20_000, errorMessage: 'Veuillez patienter avant de publier à nouveau.' },
      { key: `dev:pub_cd:${deviceIdentity}`, maxRequests: 1, windowMs: 15_000, errorMessage: 'Veuillez patienter entre chaque publication.' },
      { key: `ip:pub_daily:${rateLimitIp}`, maxRequests: 5, windowMs: 24 * 60 * 60 * 1000, errorMessage: 'Limite journalière atteinte pour cette adresse IP (maximum 5 profils par 24h).' },
      { key: `dev:pub_daily:${deviceIdentity}`, maxRequests: 10, windowMs: 24 * 60 * 60 * 1000, errorMessage: 'Limite journalière atteinte pour cet appareil (maximum 10 profils par 24h).' }
    ], c.executionCtx);

    if (!multiLimits.allowed) {
      const retryMsg = multiLimits.retryAfterSec ? ` (Réessayez dans ${multiLimits.retryAfterSec}s)` : '';
      return c.json({ success: false, error: `${multiLimits.error}${retryMsg}` }, 429);
    }

    // 4. Validate profile_json size before parsing (max 16 KB)
    const finalJsonString = typeof profile_json === 'string' ? profile_json : JSON.stringify(profile_json);
    const profileJsonByteLength = new TextEncoder().encode(finalJsonString).byteLength;
    if (profileJsonByteLength > MAX_PROFILE_JSON_BYTES) {
      return c.json({ success: false, error: 'La taille du profil dépasse la limite autorisée (16 Ko max)' }, 400);
    }

    let parsed: any;
    try {
      parsed = typeof profile_json === 'string' ? JSON.parse(profile_json) : profile_json;
    } catch {
      return c.json({ success: false, error: 'Format JSON invalide dans profile_json' }, 400);
    }

    // Deep structural validation
    const validation = validateProfileStructure(parsed);
    if (!validation.valid) {
      return c.json({ success: false, error: validation.error || 'Structure de configuration de profil invalide' }, 400);
    }

    const now = Date.now();
    const id = crypto.randomUUID();
    const hashedIp = await hashIp(clientIp, c.env.IP_SALT || c.env.APP_SECRET);

    await c.env.DB.prepare(`
      INSERT INTO profiles (
        id, title, description, game_name, package_name, author_name, controller_type,
        profile_json, device_hash, client_ip, likes_count, dislikes_count, downloads_count, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?)
    `).bind(
      id,
      title.trim().slice(0, 100),
      (description || '').trim().slice(0, 500),
      game_name.trim().slice(0, 100),
      package_name.trim().slice(0, 150),
      (author_name?.trim() || 'Anonymous').slice(0, 50),
      (controller_type?.trim() || 'Universal').slice(0, 50),
      finalJsonString,
      deviceIdentity,
      hashedIp,
      now,
      now
    ).run();

    return c.json({
      success: true,
      id,
      message: 'Profile published successfully!'
    }, 201);
  } catch (err: any) {
    console.error('[API Error] POST /api/profiles:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 4. Vote on a Profile (Anti-Sybil Rate Limiting ; compteurs maintenus atomiquement par triggers SQLite)
app.post('/api/profiles/:id/vote', verifySignature, async (c) => {
  const profileId = c.req.param('id');
  const clientIp = getClientIp(c);
  const rateLimitIp = normalizeIpForRateLimit(clientIp);

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

    const { deviceToken, vote } = body;

    const deviceIdentity = await normalizeDeviceToken(deviceToken);
    if (!deviceIdentity || vote === undefined) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou paramètre de vote manquant' }, 400);
    }

    const voteVal = parseInt(vote, 10);
    if (![1, -1, 0].includes(voteVal)) {
      return c.json({ success: false, error: 'Vote must be 1, -1 or 0' }, 400);
    }

    // 1. Anti-Sybil Rate Limiting (IP + cooldown appareil + plafond journalier appareil)
    //    deviceIdentity = hash(deviceToken) : identité opaque, toujours falsifiable (copiable) — PAS une confiance matérielle.
    const multiVoteLimits = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:vote:${rateLimitIp}`, maxRequests: 30, windowMs: 60_000, errorMessage: 'Trop de votes enregistrés. Veuillez patienter un instant.' },
      { key: `dev:vote_cd:${deviceIdentity}`, maxRequests: 10, windowMs: 10_000, errorMessage: 'Votes trop rapides pour cet appareil.' },
      { key: `dev:vote_daily:${deviceIdentity}`, maxRequests: 200, windowMs: 24 * 60 * 60 * 1000, errorMessage: 'Limite de votes journalière atteinte pour cet appareil.' }
    ], c.executionCtx);

    if (!multiVoteLimits.allowed) {
      const retryMsg = multiVoteLimits.retryAfterSec ? ` (Réessayez dans ${multiVoteLimits.retryAfterSec}s)` : '';
      return c.json({ success: false, error: `${multiVoteLimits.error}${retryMsg}` }, 429);
    }

    // 2. Check if target profile exists
    const targetProfile: any = await c.env.DB.prepare(`
      SELECT id FROM profiles WHERE id = ?
    `).bind(profileId).first();

    if (!targetProfile) {
      return c.json({ success: false, error: 'Profil introuvable' }, 404);
    }

    const now = Date.now();
    const hashedIp = await hashIp(clientIp, c.env.IP_SALT || c.env.APP_SECRET);

    // Les compteurs likes_count/dislikes_count sont maintenus de façon ATOMIQUE par des
    // triggers SQLite (voir schema.sql) déclenchés dans la même transaction que l'écriture
    // de vote. db.batch() de D1 n'étant PAS transactionnel, on ne recale plus les compteurs
    // via un second statement qui pourrait échouer séparément et laisser des compteurs faux.
    if (voteVal === 0) {
      // Annulation de vote : trg_votes_delete décrémente les compteurs automatiquement
      await c.env.DB.prepare(`
        DELETE FROM votes WHERE profile_id = ? AND device_hash = ?
      `).bind(profileId, deviceIdentity).run();
    } else {
      // Upsert atomique : trg_votes_insert / trg_votes_update maintiennent les compteurs
      await c.env.DB.prepare(`
        INSERT INTO votes (profile_id, device_hash, client_ip, vote_type, voted_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(profile_id, device_hash) DO UPDATE SET
          vote_type = excluded.vote_type,
          client_ip = excluded.client_ip,
          voted_at = excluded.voted_at
      `).bind(profileId, deviceIdentity, hashedIp, voteVal, now).run();
    }

    // 5. Return updated stats
    const updated: any = await c.env.DB.prepare(`
      SELECT likes_count, dislikes_count FROM profiles WHERE id = ?
    `).bind(profileId).first();

    return c.json({
      success: true,
      likes: updated?.likes_count || 0,
      dislikes: updated?.dislikes_count || 0,
      currentVote: voteVal
    });
  } catch (err: any) {
    console.error('[API Error] POST /api/profiles/:id/vote:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 5. Increment Download Counter (Profile Check & IP Rate Limiting)
app.post('/api/profiles/:id/download', verifySignature, async (c) => {
  const profileId = c.req.param('id');
  const rateLimitIp = getRateLimitIp(c);

  try {
    let body: any;
    try {
      body = JSON.parse(c.get('rawBody'));
    } catch {
      return c.json({ success: false, error: 'Corps JSON invalide' }, 400);
    }

    // Identité d'appareil requise (cohérence avec vote/publish) : le simple HMAC
    // partagé n'est pas une authentification fiable (clé extractible de l'APK).
    const deviceIdentity = await normalizeDeviceToken(body?.deviceToken);
    if (!deviceIdentity) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou manquante' }, 400);
    }

    // 1. Rate limit downloads per IP: max 60 per hour
    const ipDlLimit = await checkRateLimit(c.env.DB, `ip:dl:${rateLimitIp}`, 60, 3600_000, c.executionCtx);
    if (!ipDlLimit.allowed) {
      return c.json({ success: false, error: 'Trop de téléchargements enregistrés.' }, 429);
    }

    // 2. Check if target profile exists before incrementing daily downloads
    const targetProfile: any = await c.env.DB.prepare(`
      SELECT id FROM profiles WHERE id = ?
    `).bind(profileId).first();

    if (!targetProfile) {
      return c.json({ success: false, error: 'Profil introuvable' }, 404);
    }

    const now = Date.now();
    const todayStr = new Date(now).toISOString().slice(0, 10);

    // 3. Mise à jour (best-effort, non transactionnelle) : les deux statements sont
    //    indépendants et une dérive éventuelle du compteur de téléchargements est sans
    //    gravité (métrique communautaire non critique).
    await c.env.DB.batch([
      c.env.DB.prepare(`
        UPDATE profiles SET downloads_count = downloads_count + 1 WHERE id = ?
      `).bind(profileId),
      c.env.DB.prepare(`
        INSERT INTO daily_downloads (date, profile_id, count)
        VALUES (?, ?, 1)
        ON CONFLICT(date, profile_id) DO UPDATE SET count = count + 1
      `).bind(todayStr, profileId)
    ]);

    return c.json({ success: true });
  } catch (err: any) {
    console.error('[API Error] POST /api/profiles/:id/download:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 6. Enregistrement d'appareil : émet un token opaque (une seule fois) — identité non dérivable depuis ANDROID_ID
app.post('/api/device/register', verifySignature, async (c) => {
  const rateLimitIp = getRateLimitIp(c);
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
      { key: `ip:register_cd:${rateLimitIp}`, maxRequests: 1, windowMs: 30_000, errorMessage: 'Trop de tentatives. Réessayez dans un instant.' },
      { key: `ip:register:${rateLimitIp}`, maxRequests: 5, windowMs: 3600_000, errorMessage: 'Nombre maximal d\'enregistrements atteint pour cette adresse IP (5 par heure).' }
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

// 7. Telemetry Ping (Anonymous Unique Devices & Rate-Limited Batch Update)
app.post('/api/telemetry/ping', verifySignature, async (c) => {
  const rateLimitIp = getRateLimitIp(c);
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

    const { deviceToken, appVersion } = body;

    const deviceIdentity = await normalizeDeviceToken(deviceToken);
    if (!deviceIdentity) {
      return c.json({ success: false, error: 'Identité d\'appareil invalide ou manquante' }, 400);
    }

    // 1. Consolidated Batched Rate Limiting for Telemetry (IP & Device)
    const multiPing = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:ping:${rateLimitIp}`, maxRequests: 60, windowMs: 3600_000, errorMessage: 'throttled_ip' },
      { key: `ping:${deviceIdentity}`, maxRequests: 30, windowMs: 3600_000, errorMessage: 'throttled_device' }
    ], c.executionCtx);

    if (!multiPing.allowed) {
      return c.json({ success: true, note: 'throttled' });
    }

    const now = Date.now();
    const version = typeof appVersion === 'string' ? appVersion.slice(0, 20) : '1.0.0';
    const todayStr = new Date(now).toISOString().slice(0, 10);

    // Batch best-effort (non transactionnel) : chaque upsert est atomique individuellement,
    // et une dérive éventuelle de la télémétrie est sans gravité.
    await c.env.DB.batch([
      c.env.DB.prepare(`
        INSERT INTO devices (device_hash, first_seen, last_seen, app_version, launch_count)
        VALUES (?, ?, ?, ?, 1)
        ON CONFLICT(device_hash) DO UPDATE SET
          last_seen = excluded.last_seen,
          app_version = excluded.app_version,
          launch_count = launch_count + 1
      `).bind(deviceIdentity, now, now, version),
      c.env.DB.prepare(`
        INSERT INTO daily_activity (date, device_hash, app_version, launch_count, last_seen)
        VALUES (?, ?, ?, 1, ?)
        ON CONFLICT(date, device_hash) DO UPDATE SET
          launch_count = launch_count + 1,
          app_version = excluded.app_version,
          last_seen = excluded.last_seen
      `).bind(todayStr, deviceIdentity, version, now)
    ]);

    return c.json({ success: true });
  } catch (err: any) {
    console.error('[API Error] POST /api/telemetry/ping:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 8. Global Statistics & Time-Series History
app.get('/api/stats', async (c) => {
  const rateLimitIp = getRateLimitIp(c);
  try {
    // Rate limit stats queries per IP: max 15 per minute to protect D1 read quotas
    const ipLimit = await checkRateLimit(c.env.DB, `ip:stats:${rateLimitIp}`, 15, 60_000, c.executionCtx);
    if (!ipLimit.allowed) {
      return c.json({ success: false, error: 'Trop de requêtes sur les statistiques. Veuillez patienter.' }, 429);
    }

    // Cloudflare Edge Cache headers (60s browser, 120s edge, 60s stale-while-revalidate)
    c.header('Cache-Control', 'public, max-age=60, s-maxage=120, stale-while-revalidate=60');

    const now = Date.now();
    const dayAgo = now - 24 * 60 * 60 * 1000;
    const weekAgo = now - 7 * 24 * 60 * 60 * 1000;
    const monthAgo = now - 30 * 24 * 60 * 60 * 1000;

    const rawDays = parseInt(c.req.query('days') || '30', 10);
    const days = Number.isFinite(rawDays) ? Math.min(90, Math.max(7, rawDays)) : 30;
    const sinceTimestamp = now - (days - 1) * 24 * 60 * 60 * 1000;
    const sinceDate = new Date(sinceTimestamp).toISOString().slice(0, 10);

    // Global device aggregates
    const devicesStats: any = await c.env.DB.prepare(`
      SELECT 
        COUNT(*) as total_devices,
        SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) as active_24h,
        SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) as active_7d,
        SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) as active_30d,
        COALESCE(SUM(launch_count), 0) as total_launches
      FROM devices
    `).bind(dayAgo, weekAgo, monthAgo).first();

    // Community aggregates
    const profileStats: any = await c.env.DB.prepare(`
      SELECT 
        COUNT(*) as total_profiles,
        COALESCE(SUM(downloads_count), 0) as total_downloads,
        COALESCE(SUM(likes_count), 0) as total_likes
      FROM profiles
    `).first();

    // Time-series daily history
    const historyMap = new Map<string, { date: string; active_devices: number; new_devices: number; launches: number; downloads: number }>();
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(now - i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      historyMap.set(d, { date: d, active_devices: 0, new_devices: 0, launches: 0, downloads: 0 });
    }

    // Query active devices & launches per day
    const { results: actResults } = await c.env.DB.prepare(`
      SELECT date, COUNT(DISTINCT device_hash) as active_devices, SUM(launch_count) as launches
      FROM daily_activity WHERE date >= ? GROUP BY date
    `).bind(sinceDate).all();

    for (const row of (actResults || []) as any[]) {
      if (historyMap.has(row.date)) {
        const item = historyMap.get(row.date)!;
        item.active_devices = row.active_devices || 0;
        item.launches = row.launches || 0;
      }
    }

    // Query new installations per day
    const { results: newDevResults } = await c.env.DB.prepare(`
      SELECT strftime('%Y-%m-%d', first_seen / 1000, 'unixepoch') as date, COUNT(*) as new_devices
      FROM devices WHERE first_seen >= ? GROUP BY date
    `).bind(sinceTimestamp).all();

    for (const row of (newDevResults || []) as any[]) {
      if (historyMap.has(row.date)) {
        historyMap.get(row.date)!.new_devices = row.new_devices || 0;
      }
    }

    // Query downloads per day
    const { results: dlResults } = await c.env.DB.prepare(`
      SELECT date, SUM(count) as downloads
      FROM daily_downloads WHERE date >= ? GROUP BY date
    `).bind(sinceDate).all();

    for (const row of (dlResults || []) as any[]) {
      if (historyMap.has(row.date)) {
        historyMap.get(row.date)!.downloads = row.downloads || 0;
      }
    }

    // Versions breakdown
    const { results: versionResults } = await c.env.DB.prepare(`
      SELECT app_version, COUNT(*) as device_count, SUM(launch_count) as total_launches
      FROM devices GROUP BY app_version ORDER BY device_count DESC
    `).all();

    return c.json({
      success: true,
      devices: {
        total_unique_devices: devicesStats?.total_devices || 0,
        active_24h: devicesStats?.active_24h || 0,
        active_7d: devicesStats?.active_7d || 0,
        active_30d: devicesStats?.active_30d || 0,
        total_app_launches: devicesStats?.total_launches || 0
      },
      community: {
        total_profiles: profileStats?.total_profiles || 0,
        total_profile_downloads: profileStats?.total_downloads || 0,
        total_profile_likes: profileStats?.total_likes || 0
      },
      versions: versionResults || [],
      history: Array.from(historyMap.values())
    });
  } catch (err: any) {
    console.error('[API Error] GET /api/stats:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

export { app, validateProfileStructure };

export default {
  fetch: app.fetch,
  async scheduled(controller: ScheduledController, env: Bindings, ctx: ExecutionContext) {
    try {
      const now = Date.now();
      // Rétention : 180 jours pour l'activité quotidienne, 1 an pour les appareils,
      // 24 h pour les entrées de rate-limiting (garde-fou, en plus du purge probabiliste).
      const activityDate = new Date(now - 180 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      const devicesCutoff = now - 365 * 24 * 60 * 60 * 1000;
      const rateLimitCutoff = now - 24 * 60 * 60 * 1000;

      await env.DB.batch([
        env.DB.prepare('DELETE FROM daily_activity WHERE date < ?').bind(activityDate),
        env.DB.prepare('DELETE FROM daily_downloads WHERE date < ?').bind(activityDate),
        env.DB.prepare('DELETE FROM devices WHERE last_seen < ?').bind(devicesCutoff),
        env.DB.prepare('DELETE FROM rate_limits WHERE window_start < ?').bind(rateLimitCutoff),
      ]);
      console.log('[Cron] cleanup effectué:', controller.cron);
    } catch (err) {
      console.error('[Cron] cleanup error:', err);
    }
  },
};

