import { describe, it, expect } from 'vitest';
import { app } from './index';

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
});

import { recordMemRateLimit, resetMemRateLimitCache } from './index';

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
});

