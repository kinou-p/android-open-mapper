import { describe, it, expect, beforeEach } from 'vitest';
import { app, resetMemRateLimitCache, resetSeenSignaturesCache } from './index';

/**
 * `/api/stats` était public, non signé et sans cache : le tableau de bord local l'interroge toutes
 * les 15 s, et chaque appel lit ~11 300 lignes D1 → ~65 M lignes/jour, soit le quota gratuit
 * (5 M) épuisé en moins de deux heures. Il exige désormais la même signature HMAC que les autres
 * routes protégées, et la réponse est mise en cache au bord.
 */

const APP_SECRET = 'test-app-secret-1234567890';

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256Hex(data: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** GET signé : chemin sans query string, corps vide (formule de verifySignature). */
async function signedGet(path: string, query = ''): Promise<Request> {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const canonical = `GET\n${path}\n${timestamp}\n${await sha256Hex('')}`;
  const signature = await hmacSha256Hex(APP_SECRET, canonical);
  return new Request(`http://localhost${path}${query}`, {
    method: 'GET',
    headers: { 'x-timestamp': timestamp, 'x-signature': signature },
  });
}

function fakeDb() {
  const statement = (query: string): any => ({
    bind: () => statement(query),
    first: async () => {
      if (/HAVING COUNT\(DISTINCT date\)/.test(query)) return { returning_users: 7 };
      if (/AS eligible_devices/.test(query)) return { eligible_devices: 20, d1_users: 5 };
      if (/AVG\(CASE WHEN launch_count >= 2/.test(query)) {
        return { total_devices: 10, repeat_users: 2, returned_d1: 4, returned_d7: 1, active_span: 30 };
      }
      return {};
    },
    all: async () => ({ results: [] }),
    run: async () => ({ success: true }),
  });
  return { prepare: (q: string) => statement(q), batch: async () => [] };
}

const mockExecCtx = { waitUntil: (_p: Promise<any>) => {}, passThroughOnException: () => {} };

describe('/api/stats : signature HMAC obligatoire', () => {
  beforeEach(() => {
    resetMemRateLimitCache();
    resetSeenSignaturesCache();
  });

  it('rejette une requête non signée', async () => {
    const env = { APP_SECRET, DB: fakeDb() };

    const res = await app.fetch(new Request('http://localhost/api/stats'), env as any, mockExecCtx as any);

    expect(res.status).toBe(401);
  });

  it('accepte une requête signée (même avec un ?days= dans l’URL)', async () => {
    const env = { APP_SECRET, DB: fakeDb() };

    const res = await app.fetch(await signedGet('/api/stats', '?days=7'), env as any, mockExecCtx as any);

    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.success).toBe(true);
    expect(json.retention.d1_cohort_rate).toBe(25);
  });

  it('rejette une signature calculée sur l’URL avec query string (le chemin seul est signé)', async () => {
    const env = { APP_SECRET, DB: fakeDb() };
    const timestamp = Math.floor(Date.now() / 1000).toString();
    const canonical = `GET\n/api/stats?days=7\n${timestamp}\n${await sha256Hex('')}`;
    const bad = await hmacSha256Hex(APP_SECRET, canonical);

    const res = await app.fetch(
      new Request('http://localhost/api/stats?days=7', {
        method: 'GET',
        headers: { 'x-timestamp': timestamp, 'x-signature': bad },
      }),
      env as any,
      mockExecCtx as any
    );

    expect(res.status).toBe(401);
  });
});
