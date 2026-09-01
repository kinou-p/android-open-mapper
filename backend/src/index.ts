import { Hono } from 'hono';
import { cors } from 'hono/cors';

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
  allowHeaders: ['Content-Type', 'Accept', 'User-Agent', 'Authorization', 'X-Requested-With', 'Origin'],
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

/**
 * Middleware d'authentification des routes d'écriture POST.
 * Vérifie une signature HMAC-SHA256 (clé APP_SECRET partagée avec le client Android)
 * sur la chaîne canonique `METHOD\nPATH\nTIMESTAMP\nBODY_SHA256` pour authentifier
 * l'appelant et garantir l'intégrité du corps. Rejette les corps > 32 Ko avant lecture.
 * Remplace l'ancien contrôle par User-Agent, trivialement falsifiable.
 */
const verifySignature = async (c: any, next: any) => {
  const secret = c.env.APP_SECRET;
  if (!secret) {
    console.error('[Auth] APP_SECRET non configuré — rejet des routes POST');
    return c.json({ error: "Serveur mal configuré (secret d'authentification manquant)" }, 503);
  }

  // 1. Rejet précoce des corps trop volumineux (avant toute lecture en mémoire)
  const contentLength = parseInt(c.req.header('content-length') || '0', 10);
  if (contentLength > MAX_BODY_BYTES) {
    return c.json({ success: false, error: 'La taille de la requête dépasse la limite autorisée (32 Ko max)' }, 413);
  }

  // 2. Rejet des requêtes sans en-têtes de signature ou horloge invalide
  const timestamp = c.req.header('x-timestamp') ?? '';
  const signature = (c.req.header('x-signature') ?? '').toLowerCase();
  if (!timestamp || !signature) {
    return c.json({ error: 'Signature manquante' }, 401);
  }
  const ts = parseInt(timestamp, 10);
  if (!Number.isFinite(ts) || Math.abs(Math.floor(Date.now() / 1000) - ts) > SIGNATURE_WINDOW_SEC) {
    return c.json({ error: 'Requête expirée ou horloge invalide' }, 401);
  }

  // 3. Lecture du corps (bornée) puis vérification de la signature
  const rawBody = await c.req.text();
  if (rawBody.length > MAX_BODY_BYTES) {
    return c.json({ success: false, error: 'La taille de la requête dépasse la limite autorisée (32 Ko max)' }, 413);
  }

  const path = new URL(c.req.url).pathname;
  const canonical = `${c.req.method}\n${path}\n${timestamp}\n${await sha256Hex(rawBody)}`;
  const expected = await hmacSha256Hex(secret, canonical);
  if (!timingSafeEqualHex(signature, expected)) {
    return c.json({ error: 'Signature invalide' }, 401);
  }

  // 4. Corps authentifié mis à disposition des handlers
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
const HASH_REGEX = /^[a-f0-9]{64}$/i;
const MAX_PROFILE_JSON_BYTES = 16 * 1024; // 16 KB max per profile

// In-memory sliding rate limit filter to reduce D1 write load under heavy traffic
const memRateLimitCache = new Map<string, { count: number; windowStart: number }>();
const MAX_MEM_CACHE_SIZE = 5000;

function getClientIp(c: any): string {
  const cfIp = c.req.header('cf-connecting-ip');
  if (cfIp && cfIp.trim().length > 0) {
    return cfIp.trim();
  }
  // En environnement local de développement ou fallback sécurisé
  return '127.0.0.1';
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

function recordMemRateLimit(key: string, now: number, windowMs: number, maxRequests: number): { blocked: boolean; retryAfterSec: number } {
  const windowStart = now - windowMs;
  const memEntry = memRateLimitCache.get(key);
  if (memEntry) {
    if (memEntry.windowStart >= windowStart) {
      if (memEntry.count >= maxRequests) {
        const retryAfterMs = (memEntry.windowStart + windowMs) - now;
        return {
          blocked: true,
          retryAfterSec: Math.max(1, Math.ceil(retryAfterMs / 1000))
        };
      }
      memEntry.count++;
      return { blocked: false, retryAfterSec: 0 };
    } else {
      memEntry.count = 1;
      memEntry.windowStart = now;
      return { blocked: false, retryAfterSec: 0 };
    }
  } else {
    // Eviction if size exceeds MAX_MEM_CACHE_SIZE: purge expired entries or oldest 500 entries (FIFO)
    if (memRateLimitCache.size >= MAX_MEM_CACHE_SIZE) {
      for (const [k, v] of memRateLimitCache.entries()) {
        if (now - v.windowStart > 60_000) {
          memRateLimitCache.delete(k);
        }
      }
      if (memRateLimitCache.size >= MAX_MEM_CACHE_SIZE) {
        let deleted = 0;
        for (const k of memRateLimitCache.keys()) {
          memRateLimitCache.delete(k);
          deleted++;
          if (deleted >= 500) break;
        }
      }
    }
    memRateLimitCache.set(key, { count: 1, windowStart: now });
    return { blocked: false, retryAfterSec: 0 };
  }
}

function validateProfileStructure(obj: any): { valid: boolean; error?: string } {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return { valid: false, error: 'La configuration doit être un objet JSON valide' };
  }
  // Validate Joystick if present
  if (obj.joystick && typeof obj.joystick === 'object') {
    const { centerX, centerY, radius, deadzone, outerDeadzone, sprintThreshold } = obj.joystick;
    if (
      (typeof centerX === 'number' && (!Number.isFinite(centerX) || centerX < -0.5 || centerX > 1.5)) ||
      (typeof centerY === 'number' && (!Number.isFinite(centerY) || centerY < -0.5 || centerY > 1.5)) ||
      (typeof radius === 'number' && (!Number.isFinite(radius) || radius <= 0 || radius > 1.0)) ||
      (typeof deadzone === 'number' && (!Number.isFinite(deadzone) || deadzone < 0 || deadzone > 0.5)) ||
      (typeof outerDeadzone === 'number' && (!Number.isFinite(outerDeadzone) || outerDeadzone < 0.5 || outerDeadzone > 1.0)) ||
      (typeof sprintThreshold === 'number' && (!Number.isFinite(sprintThreshold) || sprintThreshold < 0.2 || sprintThreshold > 1.0))
    ) {
      return { valid: false, error: 'Paramètres de joystick invalides' };
    }
  }
  // Validate Camera if present
  if (obj.camera && typeof obj.camera === 'object') {
    const { rectX1, rectY1, rectX2, rectY2, sensitivityX, sensitivityY, deadzone, smoothing, acceleration } = obj.camera;
    if (
      (typeof rectX1 === 'number' && (!Number.isFinite(rectX1) || rectX1 < -0.5 || rectX1 > 1.5)) ||
      (typeof rectY1 === 'number' && (!Number.isFinite(rectY1) || rectY1 < -0.5 || rectY1 > 1.5)) ||
      (typeof rectX2 === 'number' && (!Number.isFinite(rectX2) || rectX2 < -0.5 || rectX2 > 1.5)) ||
      (typeof rectY2 === 'number' && (!Number.isFinite(rectY2) || rectY2 < -0.5 || rectY2 > 1.5)) ||
      (typeof sensitivityX === 'number' && (!Number.isFinite(sensitivityX) || sensitivityX < 0.1 || sensitivityX > 10.0)) ||
      (typeof sensitivityY === 'number' && (!Number.isFinite(sensitivityY) || sensitivityY < 0.1 || sensitivityY > 10.0)) ||
      (typeof deadzone === 'number' && (!Number.isFinite(deadzone) || deadzone < 0 || deadzone > 0.5)) ||
      (typeof smoothing === 'number' && (!Number.isFinite(smoothing) || smoothing < 0 || smoothing > 1.0)) ||
      (typeof acceleration === 'number' && (!Number.isFinite(acceleration) || acceleration < 0.5 || acceleration > 5.0))
    ) {
      return { valid: false, error: 'Paramètres de zone caméra invalides' };
    }
  }
  // Validate Buttons if present
  if (obj.buttons) {
    if (!Array.isArray(obj.buttons) || obj.buttons.length > 50) {
      return { valid: false, error: 'Liste de boutons invalide (maximum 50 boutons autorisés)' };
    }
    for (const btn of obj.buttons) {
      if (!btn || typeof btn !== 'object') {
        return { valid: false, error: 'Structure de bouton invalide' };
      }
      if (typeof btn.x === 'number' && (!Number.isFinite(btn.x) || btn.x < -0.5 || btn.x > 1.5)) {
        return { valid: false, error: 'Coordonnée de bouton X invalide' };
      }
      if (typeof btn.y === 'number' && (!Number.isFinite(btn.y) || btn.y < -0.5 || btn.y > 1.5)) {
        return { valid: false, error: 'Coordonnée de bouton Y invalide' };
      }
      if (typeof btn.radius === 'number' && (!Number.isFinite(btn.radius) || btn.radius <= 0 || btn.radius > 0.5)) {
        return { valid: false, error: 'Rayon de bouton invalide' };
      }
      if (typeof btn.label === 'string' && btn.label.length > 100) {
        return { valid: false, error: 'Libellé de bouton trop long (max 100 caractères)' };
      }
      if (typeof btn.gamepadButton === 'string' && btn.gamepadButton.length > 50) {
        return { valid: false, error: 'Nom de touche manette trop long (max 50 caractères)' };
      }
    }
  }
  // Validate Settings if present
  if (obj.settings && typeof obj.settings === 'object') {
    const { polling_rate_hz, haptic_intensity } = obj.settings;
    if (typeof polling_rate_hz === 'number' && (!Number.isFinite(polling_rate_hz) || polling_rate_hz < 30 || polling_rate_hz > 240)) {
      return { valid: false, error: 'Taux de rafraîchissement invalide (30 à 240 Hz)' };
    }
    if (typeof haptic_intensity === 'number' && (!Number.isFinite(haptic_intensity) || haptic_intensity < 0.0 || haptic_intensity > 1.0)) {
      return { valid: false, error: 'Intensité haptique invalide (0.0 à 1.0)' };
    }
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
 * Batched multi-key rate limiter to consolidate multiple rate limit checks into a single atomic D1 batch query.
 * Eliminates multiple roundtrips and locks for POST /api/profiles and other composite endpoints.
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
  const clientIp = getClientIp(c);
  try {
    const ipLimit = await checkRateLimit(c.env.DB, `ip:list:${clientIp}`, 60, 60_000, c.executionCtx);
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
      profiles: results
    });
  } catch (err: any) {
    console.error('[API Error] GET /api/profiles:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 2. Get Single Profile Details (Excluding sensitive PII client_ip & device_hash)
app.get('/api/profiles/:id', async (c) => {
  const id = c.req.param('id');
  const clientIp = getClientIp(c);
  try {
    const ipLimit = await checkRateLimit(c.env.DB, `ip:detail:${clientIp}`, 120, 60_000, c.executionCtx);
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

    const { title, description, game_name, package_name, author_name, controller_type, profile_json, deviceHash } = body;

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

    // 2. Validate device fingerprint format
    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash)) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou manquante' }, 400);
    }

    // 3. Anti-spam & Cooldown: Consolidated Batched Rate Limiting (Single D1 batch execution)
    const multiLimits = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:pub_cd:${clientIp}`, maxRequests: 1, windowMs: 20_000, errorMessage: 'Veuillez patienter avant de publier à nouveau.' },
      { key: `dev:pub_cd:${deviceHash}`, maxRequests: 1, windowMs: 15_000, errorMessage: 'Veuillez patienter entre chaque publication.' },
      { key: `ip:pub_daily:${clientIp}`, maxRequests: 5, windowMs: 24 * 60 * 60 * 1000, errorMessage: 'Limite journalière atteinte pour cette adresse IP (maximum 5 profils par 24h).' },
      { key: `dev:pub_daily:${deviceHash}`, maxRequests: 10, windowMs: 24 * 60 * 60 * 1000, errorMessage: 'Limite journalière atteinte pour cet appareil (maximum 10 profils par 24h).' }
    ], c.executionCtx);

    if (!multiLimits.allowed) {
      const retryMsg = multiLimits.retryAfterSec ? ` (Réessayez dans ${multiLimits.retryAfterSec}s)` : '';
      return c.json({ success: false, error: `${multiLimits.error}${retryMsg}` }, 429);
    }

    // 4. Validate profile_json size before parsing (max 16 KB)
    const finalJsonString = typeof profile_json === 'string' ? profile_json : JSON.stringify(profile_json);
    if (finalJsonString.length > MAX_PROFILE_JSON_BYTES) {
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
      (author_name || 'Anonymous').trim().slice(0, 50),
      (controller_type || 'Universal').trim().slice(0, 50),
      finalJsonString,
      deviceHash,
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

// 4. Vote on a Profile (Atomic Batch Transaction & Anti-Sybil IP Rate Limiting)
app.post('/api/profiles/:id/vote', verifySignature, async (c) => {
  const profileId = c.req.param('id');
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

    const { deviceHash, vote } = body;

    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash) || vote === undefined) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou paramètre de vote manquant' }, 400);
    }

    const voteVal = parseInt(vote, 10);
    if (![1, -1, 0].includes(voteVal)) {
      return c.json({ success: false, error: 'Vote must be 1, -1 or 0' }, 400);
    }

    // 1. Anti-Sybil Rate Limiting (IP + cooldown appareil + plafond journalier appareil)
    //    deviceHash étant désormais signé (HMAC), il constitue une identité fiable.
    const multiVoteLimits = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:vote:${clientIp}`, maxRequests: 30, windowMs: 60_000, errorMessage: 'Trop de votes enregistrés. Veuillez patienter un instant.' },
      { key: `dev:vote_cd:${deviceHash}`, maxRequests: 10, windowMs: 10_000, errorMessage: 'Votes trop rapides pour cet appareil.' },
      { key: `dev:vote_daily:${deviceHash}`, maxRequests: 200, windowMs: 24 * 60 * 60 * 1000, errorMessage: 'Limite de votes journalière atteinte pour cet appareil.' }
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
    const batchStatements: any[] = [];

    if (voteVal === 0) {
      // Cancel vote
      batchStatements.push(
        c.env.DB.prepare(`
          DELETE FROM votes WHERE profile_id = ? AND device_hash = ?
        `).bind(profileId, deviceHash)
      );
    } else {
      // Upsert vote atomically
      batchStatements.push(
        c.env.DB.prepare(`
          INSERT INTO votes (profile_id, device_hash, client_ip, vote_type, voted_at)
          VALUES (?, ?, ?, ?, ?)
          ON CONFLICT(profile_id, device_hash) DO UPDATE SET
            vote_type = excluded.vote_type,
            client_ip = excluded.client_ip,
            voted_at = excluded.voted_at
        `).bind(profileId, deviceHash, hashedIp, voteVal, now)
      );
    }

    // Recalculate likes and dislikes count atomically (accelerated by idx_votes_profile_type)
    batchStatements.push(
      c.env.DB.prepare(`
        UPDATE profiles 
        SET likes_count = (SELECT COUNT(*) FROM votes WHERE profile_id = ?1 AND vote_type = 1),
            dislikes_count = (SELECT COUNT(*) FROM votes WHERE profile_id = ?1 AND vote_type = -1),
            updated_at = ?2
        WHERE id = ?1
      `).bind(profileId, now)
    );

    // 4. Execute atomic transaction
    await c.env.DB.batch(batchStatements);

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

// 5. Increment Download Counter (Atomic Batch, Profile Check & IP Rate Limiting)
app.post('/api/profiles/:id/download', verifySignature, async (c) => {
  const profileId = c.req.param('id');
  const clientIp = getClientIp(c);

  try {
    // 1. Rate limit downloads per IP: max 60 per hour
    const ipDlLimit = await checkRateLimit(c.env.DB, `ip:dl:${clientIp}`, 60, 3600_000, c.executionCtx);
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

    // 3. Atomic update
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

// 6. Telemetry Ping (Anonymous Unique Devices & Rate-Limited Batch Update)
app.post('/api/telemetry/ping', verifySignature, async (c) => {
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

    const { deviceHash, appVersion } = body;

    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash)) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou manquante' }, 400);
    }

    // 1. Consolidated Batched Rate Limiting for Telemetry (IP & Device)
    const multiPing = await checkMultiRateLimits(c.env.DB, [
      { key: `ip:ping:${clientIp}`, maxRequests: 60, windowMs: 3600_000, errorMessage: 'throttled_ip' },
      { key: `ping:${deviceHash}`, maxRequests: 30, windowMs: 3600_000, errorMessage: 'throttled_device' }
    ], c.executionCtx);

    if (!multiPing.allowed) {
      return c.json({ success: true, note: 'throttled' });
    }

    const now = Date.now();
    const version = typeof appVersion === 'string' ? appVersion.slice(0, 20) : '1.0.0';
    const todayStr = new Date(now).toISOString().slice(0, 10);

    // Atomic batch updates
    await c.env.DB.batch([
      c.env.DB.prepare(`
        INSERT INTO devices (device_hash, first_seen, last_seen, app_version, launch_count)
        VALUES (?, ?, ?, ?, 1)
        ON CONFLICT(device_hash) DO UPDATE SET
          last_seen = excluded.last_seen,
          app_version = excluded.app_version,
          launch_count = launch_count + 1
      `).bind(deviceHash, now, now, version),
      c.env.DB.prepare(`
        INSERT INTO daily_activity (date, device_hash, app_version, launch_count, last_seen)
        VALUES (?, ?, ?, 1, ?)
        ON CONFLICT(date, device_hash) DO UPDATE SET
          launch_count = launch_count + 1,
          app_version = excluded.app_version,
          last_seen = excluded.last_seen
      `).bind(todayStr, deviceHash, version, now)
    ]);

    return c.json({ success: true });
  } catch (err: any) {
    console.error('[API Error] POST /api/telemetry/ping:', err);
    return c.json({ success: false, error: 'Une erreur interne est survenue sur le serveur.' }, 500);
  }
});

// 7. Global Statistics & Time-Series History
app.get('/api/stats', async (c) => {
  const clientIp = getClientIp(c);
  try {
    // Rate limit stats queries per IP: max 15 per minute to protect D1 read quotas
    const ipLimit = await checkRateLimit(c.env.DB, `ip:stats:${clientIp}`, 15, 60_000, c.executionCtx);
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
        env.DB.prepare('DELETE FROM devices WHERE last_seen < ?').bind(devicesCutoff),
        env.DB.prepare('DELETE FROM rate_limits WHERE window_start < ?').bind(rateLimitCutoff),
      ]);
      console.log('[Cron] cleanup effectué:', controller.cron);
    } catch (err) {
      console.error('[Cron] cleanup error:', err);
    }
  },
};

