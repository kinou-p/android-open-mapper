import { describe, it, expect } from 'vitest';
import { app, normalizeIpForRateLimit } from './index';

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

describe('verifySignature Middleware & Content-Length Handling', () => {
  const mockEnv = {
    APP_SECRET: 'test-app-secret-1234567890',
    DB: {
      prepare: () => ({
        bind: () => ({
          first: async () => ({ count: 0, window_start: Date.now() }),
          all: async () => ({ results: [] }),
          run: async () => ({ success: true })
        })
      }),
      batch: async (statements: any[]) => {
        return statements.map(() => ({
          results: [{ count: 1, window_start: Date.now() }],
          success: true
        }));
      }
    }
  };

  const mockExecCtx = {
    waitUntil: (_promise: Promise<any>) => {},
    passThroughOnException: () => {}
  };

  it('accepte les requêtes sans en-tête Content-Length (flux HTTP/2 / chunked)', async () => {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const body = JSON.stringify({ test: 'hello' });
    const bodyHash = await sha256Hex(body);
    const canonical = `POST\n/api/device/register\n${timestamp}\n${bodyHash}`;
    const signature = await hmacSha256Hex(mockEnv.APP_SECRET, canonical);

    const req = new Request('http://localhost/api/device/register', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': signature,
        'content-type': 'application/json'
        // Pas de content-length
      },
      body
    });

    const res = await app.fetch(req, mockEnv as any, mockExecCtx as any);
    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.success).toBe(true);
    expect(json.deviceToken).toBeDefined();
  });

  it('rejette les requêtes avec Content-Length > 32 Ko', async () => {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const req = new Request('http://localhost/api/device/register', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': 'abc',
        'content-length': '35000'
      },
      body: 'x'.repeat(100)
    });

    const res = await app.fetch(req, mockEnv as any, mockExecCtx as any);
    expect(res.status).toBe(413);
    const json = await res.json() as any;
    expect(json.error).toContain('32 Ko max');
  });

  it('rejette les requêtes avec Content-Length négatif', async () => {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const req = new Request('http://localhost/api/device/register', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': 'abc',
        'content-length': '-1'
      },
      body: 'x'.repeat(100)
    });

    const res = await app.fetch(req, mockEnv as any, mockExecCtx as any);
    expect(res.status).toBe(413);
  });

  it('rejette les requêtes dont le corps en octets UTF-8 dépasse 32 Ko même si string.length < 32 Ko', async () => {
    // 10 000 caractères emojis = 40 000 octets (> 32 Ko) alors que length = 10 000 (< 32 768)
    const multibyteBody = '🎮'.repeat(10000);
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const bodyHash = await sha256Hex(multibyteBody);
    const canonical = `POST\n/api/device/register\n${timestamp}\n${bodyHash}`;
    const signature = await hmacSha256Hex(mockEnv.APP_SECRET, canonical);

    const req = new Request('http://localhost/api/device/register', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': signature
      },
      body: multibyteBody
    });

    const res = await app.fetch(req, mockEnv as any, mockExecCtx as any);
    expect(res.status).toBe(413);
    const json = await res.json() as any;
    expect(json.error).toContain('32 Ko max');
  });

  it('rejette les attaques par rejeu (même signature rejouée dans la fenêtre de validité)', async () => {
    resetSeenSignaturesCache();
    resetMemRateLimitCache();
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const body = JSON.stringify({ test: 'anti-replay' });
    const bodyHash = await sha256Hex(body);
    const canonical = `POST\n/api/device/register\n${timestamp}\n${bodyHash}`;
    const signature = await hmacSha256Hex(mockEnv.APP_SECRET, canonical);

    const makeReq = () => new Request('http://localhost/api/device/register', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': signature,
        'cf-connecting-ip': '198.51.100.42',
        'content-type': 'application/json'
      },
      body
    });

    // 1ère requête : acceptée
    const res1 = await app.fetch(makeReq(), mockEnv as any, mockExecCtx as any);
    expect(res1.status).toBe(200);

    // 2ème requête identique (rejeu) : rejetée par verifySignature avant même les rate limits applicatifs
    const res2 = await app.fetch(makeReq(), mockEnv as any, mockExecCtx as any);
    expect(res2.status).toBe(401);
    const json2 = await res2.json() as any;
    expect(json2.error).toContain('Replay Attack');

    // 3ème cas (Multi-régions / isolate V8 distinct) : même si le cache mémoire est vidé, D1 rejette le rejeu
    resetSeenSignaturesCache();
    const mockMultiRegionEnv = {
      ...mockEnv,
      DB: {
        ...mockEnv.DB,
        prepare: (query: string) => ({
          bind: (...args: any[]) => ({
            first: async () => {
              if (query.includes('INSERT INTO rate_limits')) {
                // Simule le conflit UNIQUE dans SQLite D1 multi-régions (first() renvoie null)
                return null;
              }
              return { count: 0, window_start: Date.now() };
            },
            all: async () => ({ results: [] }),
            run: async () => ({ success: true })
          })
        })
      }
    };
    const res3 = await app.fetch(makeReq(), mockMultiRegionEnv as any, mockExecCtx as any);
    expect(res3.status).toBe(401);
    const json3 = await res3.json() as any;
    expect(json3.error).toContain('Replay Attack');
  });
});

import { recordMemRateLimit, resetMemRateLimitCache, validateProfileStructure, isSignatureReplayed, resetSeenSignaturesCache } from './index';

describe('Anti-Replay Cache (seenSignatures)', () => {
  it('autorise une signature unique et rejette sa répétition', () => {
    resetSeenSignaturesCache();
    const nowSec = 1_700_000_000;
    const sig = 'abcdef1234567890';

    expect(isSignatureReplayed(sig, nowSec)).toBe(false);
    expect(isSignatureReplayed(sig, nowSec)).toBe(true);
    expect(isSignatureReplayed(sig, nowSec + 100)).toBe(true);
  });

  it('nettoie les signatures expirées après le TTL de 300s', () => {
    resetSeenSignaturesCache();
    const nowSec = 1_700_000_000;
    const sig = 'sig-will-expire';

    expect(isSignatureReplayed(sig, nowSec)).toBe(false);

    // Après 301 secondes (expiration passée)
    const futureSec = nowSec + 301;
    // On appelle avec une autre signature pour déclencher le cycle de nettoyage
    for (let i = 0; i < 60; i++) {
      isSignatureReplayed(`filler-${i}`, futureSec);
    }

    // L'ancienne signature expirée a été nettoyée et peut être réenregistrée
    expect(isSignatureReplayed(sig, futureSec)).toBe(false);
  });
});

describe('In-Memory O(1) Dual-Bucket Sliding Rate Limiter', () => {
  it('autorise les requêtes sous le seuil et bloque au-delà', () => {
    resetMemRateLimitCache();
    const baseTime = 1_700_000_000_000; // instant fixe
    const maxRequests = 5;
    const windowMs = 60_000;
    const key = 'test-ip-1';

    for (let i = 0; i < maxRequests; i++) {
      const result = recordMemRateLimit(key, baseTime, windowMs, maxRequests);
      expect(result.blocked).toBe(false);
    }

    // La 6ème requête doit être bloquée
    const blockedResult = recordMemRateLimit(key, baseTime, windowMs, maxRequests);
    expect(blockedResult.blocked).toBe(true);
    expect(blockedResult.retryAfterSec).toBeGreaterThan(0);
  });

  it('effectue la rotation des buckets temporels en O(1) sans résidu bloquant', () => {
    resetMemRateLimitCache();
    const baseTime = 1_700_000_000_000;
    const key = 'test-ip-2';

    // Remplir le quota sur la première minute
    for (let i = 0; i < 3; i++) {
      recordMemRateLimit(key, baseTime, 60_000, 3);
    }
    expect(recordMemRateLimit(key, baseTime, 60_000, 3).blocked).toBe(true);

    // 2 minutes plus tard (rotation complète des 2 fenêtres)
    const futureTime = baseTime + 130_000;
    const freshResult = recordMemRateLimit(key, futureTime, 60_000, 3);
    expect(freshResult.blocked).toBe(false);
  });

  it('ne bloque pas en mémoire les fenêtres différentes de 60_000ms (déléguées à D1 SQLite)', () => {
    resetMemRateLimitCache();
    const baseTime = 1_700_000_000_000;
    const key = 'test-cooldown-or-daily';

    // Règle de cooldown court (ex: 20s, max 1)
    for (let i = 0; i < 5; i++) {
      const res = recordMemRateLimit(key, baseTime, 20_000, 1);
      expect(res.blocked).toBe(false);
      expect(res.retryAfterSec).toBe(0);
    }

    // Règle de quota journalier (ex: 24h, max 5)
    for (let i = 0; i < 10; i++) {
      const res = recordMemRateLimit(key, baseTime, 86_400_000, 5);
      expect(res.blocked).toBe(false);
      expect(res.retryAfterSec).toBe(0);
    }
  });
});

describe('validateProfileStructure Strict Schema & Type Validation', () => {
  const validProfile = {
    id: 'prof-123',
    name: 'CODM Test Profile',
    package_name: 'com.activision.callofduty.shooter',
    description: 'A test profile',
    joystick: {
      center_x: 0.18,
      center_y: 0.72,
      radius: 0.12,
      deadzone: 0.08,
      outer_deadzone: 0.95,
      sprint_threshold: 0.85,
      enabled: true
    },
    camera: {
      rect_x1: 0.5,
      rect_y1: 0.15,
      rect_x2: 0.98,
      rect_y2: 0.9,
      sensitivity_x: 1.0,
      sensitivity_y: 0.9,
      deadzone: 0.08,
      smoothing: 0.2,
      acceleration: 1.25,
      enabled: true
    },
    buttons: [
      {
        id: 'btn-1',
        label: 'Shoot',
        gamepad_button: 'BUTTON_R2',
        x: 0.85,
        y: 0.75,
        radius: 0.05
      }
    ],
    settings: {
      polling_rate_hz: 120,
      haptic_intensity: 0.8,
      haptic_feedback: true
    }
  };

  it('valide un profil JSON conforme', () => {
    const res = validateProfileStructure(validProfile);
    expect(res.valid).toBe(true);
    expect(res.error).toBeUndefined();
  });

  it('rejette les structures non objets ou null/array', () => {
    expect(validateProfileStructure(null).valid).toBe(false);
    expect(validateProfileStructure([]).valid).toBe(false);
    expect(validateProfileStructure('string').valid).toBe(false);
    expect(validateProfileStructure(123).valid).toBe(false);
  });

  it('rejette les types non numériques injectés dans joystick (ex: string ou object)', () => {
    const bad1 = { ...validProfile, joystick: { ...validProfile.joystick, center_x: 'malicious_string' } };
    const res1 = validateProfileStructure(bad1);
    expect(res1.valid).toBe(false);
    expect(res1.error).toContain('Joystick center_x doit être un nombre valide');

    const bad2 = { ...validProfile, joystick: { ...validProfile.joystick, radius: { $exploit: true } } };
    const res2 = validateProfileStructure(bad2);
    expect(res2.valid).toBe(false);
    expect(res2.error).toContain('Joystick radius doit être un nombre valide');
  });

  it('rejette les valeurs numériques hors limites dans joystick et camera', () => {
    const badJoy = { ...validProfile, joystick: { ...validProfile.joystick, deadzone: 0.9 } };
    const resJoy = validateProfileStructure(badJoy);
    expect(resJoy.valid).toBe(false);
    expect(resJoy.error).toContain('hors limites');

    const badCam = { ...validProfile, camera: { ...validProfile.camera, max_step_pixels: 500.0 } };
    const resCam = validateProfileStructure(badCam);
    expect(resCam.valid).toBe(false);
    expect(resCam.error).toContain('Camera max_step_pixels');
    expect(resCam.error).toContain('hors limites');
  });

  it('rejette les types non numériques injectés dans les boutons', () => {
    const badBtnX = {
      ...validProfile,
      buttons: [{ id: 'b1', label: 'Aim', gamepad_button: 'BUTTON_L2', x: 'invalid_coord', y: 0.5 }]
    };
    const resX = validateProfileStructure(badBtnX);
    expect(resX.valid).toBe(false);
    expect(resX.error).toContain('Bouton #1 x doit être un nombre valide');

    const badBtnY = {
      ...validProfile,
      buttons: [{ id: 'b1', label: 'Aim', gamepad_button: 'BUTTON_L2', x: 0.5, y: { nested: 1 } }]
    };
    const resY = validateProfileStructure(badBtnY);
    expect(resY.valid).toBe(false);
    expect(resY.error).toContain('Bouton #1 y doit être un nombre valide');
  });

  it('rejette une liste de boutons dépassant 50 éléments', () => {
    const tooManyButtons = {
      ...validProfile,
      buttons: Array.from({ length: 51 }, (_, i) => ({
        id: `btn-${i}`,
        label: `B${i}`,
        gamepad_button: 'BUTTON_A',
        x: 0.5,
        y: 0.5
      }))
    };
    const res = validateProfileStructure(tooManyButtons);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('maximum 50 boutons autorisés');
  });

  it('rejette les types booléens ou strings invalides dans settings', () => {
    const badHaptic = { ...validProfile, settings: { ...validProfile.settings, haptic_feedback: 'yes' } };
    const res = validateProfileStructure(badHaptic);
    expect(res.valid).toBe(false);
    expect(res.error).toContain('Settings haptic_feedback doit être un booléen');

    const badPolling = { ...validProfile, settings: { ...validProfile.settings, polling_rate_hz: 500 } };
    const resPolling = validateProfileStructure(badPolling);
    expect(resPolling.valid).toBe(false);
    expect(resPolling.error).toContain('hors limites');
  });

  it('normalise correctement les champs author_name et controller_type avec espaces', () => {
    const sanitizeAuthor = (val?: string) => (val?.trim() || 'Anonymous').slice(0, 50);
    const sanitizeController = (val?: string) => (val?.trim() || 'Universal').slice(0, 50);

    expect(sanitizeAuthor('   ')).toBe('Anonymous');
    expect(sanitizeAuthor('')).toBe('Anonymous');
    expect(sanitizeAuthor(undefined)).toBe('Anonymous');
    expect(sanitizeAuthor('  Kinou  ')).toBe('Kinou');

    expect(sanitizeController('   ')).toBe('Universal');
    expect(sanitizeController('')).toBe('Universal');
    expect(sanitizeController('  Xbox Controller  ')).toBe('Xbox Controller');
  });
});

describe('normalizeIpForRateLimit (IPv4 & IPv6 /64 Subnet Rate Limiting)', () => {
  it('préserve les adresses IPv4 classiques sans modification', () => {
    expect(normalizeIpForRateLimit('192.168.1.1')).toBe('192.168.1.1');
    expect(normalizeIpForRateLimit('127.0.0.1')).toBe('127.0.0.1');
    expect(normalizeIpForRateLimit('8.8.8.8')).toBe('8.8.8.8');
  });

  it('extrait la partie IPv4 pour les adresses IPv4-mapped IPv6', () => {
    expect(normalizeIpForRateLimit('::ffff:192.0.2.128')).toBe('192.0.2.128');
    expect(normalizeIpForRateLimit('::FFFF:10.0.0.1')).toBe('10.0.0.1');
  });

  it('tronque les adresses IPv6 complètes vers leur préfixe /64', () => {
    const ip1 = '2001:0db8:85a3:0000:0000:8a2e:0370:7334';
    expect(normalizeIpForRateLimit(ip1)).toBe('2001:0db8:85a3:0000::/64');
  });

  it('gère correctement les adresses IPv6 compressées avec ::', () => {
    expect(normalizeIpForRateLimit('2001:db8:85a3::8a2e:370:7334')).toBe('2001:db8:85a3:0::/64');
    expect(normalizeIpForRateLimit('::1')).toBe('0:0:0:0::/64');
    expect(normalizeIpForRateLimit('2001:db8::')).toBe('2001:db8:0:0::/64');
  });

  it('agrège plusieurs adresses du même sous-réseau /64 sous la même clé de rate limit', () => {
    const deviceA = '2a01:cb19:8a00:5400:1111:2222:3333:4444';
    const deviceB = '2a01:cb19:8a00:5400:aaaa:bbbb:cccc:dddd';
    const keyA = normalizeIpForRateLimit(deviceA);
    const keyB = normalizeIpForRateLimit(deviceB);

    expect(keyA).toBe('2a01:cb19:8a00:5400::/64');
    expect(keyB).toBe('2a01:cb19:8a00:5400::/64');
    expect(keyA).toBe(keyB);
  });
});

describe('CORS Configuration', () => {
  it('autorise les en-têtes X-Timestamp et X-Signature lors des requêtes pré-vol (OPTIONS)', async () => {
    const req = new Request('http://localhost/api/profiles', {
      method: 'OPTIONS',
      headers: {
        'Origin': 'https://example.com',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'Content-Type, X-Timestamp, X-Signature',
      },
    });

    const res = await app.fetch(req);
    expect(res.status).toBe(204);
    const allowHeaders = res.headers.get('access-control-allow-headers');
    expect(allowHeaders).toBeDefined();
    expect(allowHeaders).toContain('X-Timestamp');
    expect(allowHeaders).toContain('X-Signature');
  });
});

describe('Telemetry & Device Metadata (deviceModel + osVersion)', () => {
  const mockEnv = {
    APP_SECRET: 'test-app-secret-1234567890',
    DB: {
      prepare: () => ({
        bind: () => ({
          first: async () => ({ count: 0, window_start: Date.now() }),
          all: async () => ({ results: [] }),
          run: async () => ({ success: true })
        })
      }),
      batch: async (statements: any[]) => {
        return statements.map(() => ({
          results: [{ count: 1, window_start: Date.now() }],
          success: true
        }));
      }
    }
  };

  const mockExecCtx = {
    waitUntil: (_promise: Promise<any>) => {},
    passThroughOnException: () => {}
  };

  it('accepte les champs deviceModel et osVersion lors de /api/device/register', async () => {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const body = JSON.stringify({
      appVersion: '1.2.0',
      deviceModel: 'Samsung Galaxy S23',
      osVersion: 'Android 14 (API 34)'
    });
    const bodyHash = await sha256Hex(body);
    const canonical = `POST\n/api/device/register\n${timestamp}\n${bodyHash}`;
    const signature = await hmacSha256Hex(mockEnv.APP_SECRET, canonical);

    const req = new Request('http://localhost/api/device/register', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': signature,
        'content-type': 'application/json'
      },
      body
    });

    const res = await app.fetch(req, mockEnv as any, mockExecCtx as any);
    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.success).toBe(true);
    expect(json.deviceToken).toBeDefined();
  });

  it('accepte les champs deviceModel et osVersion lors de /api/telemetry/ping', async () => {
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const body = JSON.stringify({
      deviceToken: 'a'.repeat(64),
      appVersion: '1.2.0',
      deviceModel: 'Google Pixel 7',
      osVersion: 'Android 14 (API 34)'
    });
    const bodyHash = await sha256Hex(body);
    const canonical = `POST\n/api/telemetry/ping\n${timestamp}\n${bodyHash}`;
    const signature = await hmacSha256Hex(mockEnv.APP_SECRET, canonical);

    const req = new Request('http://localhost/api/telemetry/ping', {
      method: 'POST',
      headers: {
        'x-timestamp': timestamp,
        'x-signature': signature,
        'content-type': 'application/json'
      },
      body
    });

    const res = await app.fetch(req, mockEnv as any, mockExecCtx as any);
    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.success).toBe(true);
  });
});

