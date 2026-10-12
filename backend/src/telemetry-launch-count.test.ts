import { describe, it, expect, beforeEach } from 'vitest';
import { app, resetMemRateLimitCache, resetSeenSignaturesCache } from './index';

/**
 * Comptage des lancements (register + ping).
 *
 * Bug corrigé ici : `/api/device/register` créait la ligne `devices` avec `launch_count = 1`,
 * puis le premier `/api/telemetry/ping` faisait `launch_count = launch_count + 1` → **tout appareil
 * neuf démarrait à 2**, ce qui affichait un « taux de retour » de ~99 % (mesuré : 1344/1351).
 *
 * Invariant visé : **un lancement = un ping**. L'enregistrement n'alloue qu'une identité, il ne
 * compte aucun lancement ; la ligne `devices` est créée par le premier ping, avec `launch_count = 1`.
 */

const APP_SECRET = 'test-app-secret-1234567890';
const DEVICE_TOKEN = 'a'.repeat(64);

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return Array.from(new Uint8Array(sig)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function sha256Hex(data: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(data));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function signedRequest(path: string, payload: Record<string, unknown>): Promise<Request> {
  // Un nonce par requête : deux pings réels ne portent jamais le même corps, et l'anti-rejeu
  // rejetterait deux signatures identiques émises dans la même seconde.
  const body = JSON.stringify({ ...payload, _nonce: ++requestCounter });
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const canonical = `POST\n${path}\n${timestamp}\n${await sha256Hex(body)}`;
  const signature = await hmacSha256Hex(APP_SECRET, canonical);
  return new Request(`http://localhost${path}`, {
    method: 'POST',
    headers: { 'x-timestamp': timestamp, 'x-signature': signature, 'content-type': 'application/json' },
    body,
  });
}

let requestCounter = 0;

const mockExecCtx = {
  waitUntil: (_promise: Promise<any>) => {},
  passThroughOnException: () => {},
};

/**
 * Faux D1 : journalise les requêtes exécutées et simule la seule table qui nous intéresse
 * (`devices`), en reproduisant fidèlement les deux écritures du backend :
 *   - `INSERT INTO devices (...) VALUES (..., 1, ...)` → création à 1 ;
 *   - `ON CONFLICT(device_hash) DO UPDATE SET launch_count = launch_count + 1` → +1.
 */
function createFakeDb() {
  const executed: string[] = [];
  const devices = new Map<string, number>();

  const applyEffect = (query: string, args: any[]) => {
    if (/INSERT INTO devices/i.test(query)) {
      const hash = String(args[0]);
      devices.set(hash, (devices.get(hash) ?? 0) + 1);
    }
  };

  const statement = (query: string, args: any[] = []): any => ({
    bind: (...bound: any[]) => statement(query, bound),
    run: async () => {
      executed.push(query);
      applyEffect(query, args);
      return { success: true };
    },
    first: async () => ({}),
    all: async () => ({ results: [] }),
    // Les écritures passées par `db.batch()` (c'est le cas de l'upsert du ping) sont rejouées ici.
    __replay: () => {
      executed.push(query);
      applyEffect(query, args);
    },
  });

  return {
    executed,
    devices,
    db: {
      prepare: (query: string) => statement(query),
      batch: async (statements: any[]) => {
        for (const stmt of statements) {
          // le faux statement expose sa requête via bind() ; on rejoue l'effet au moment du batch
          if (typeof stmt.__replay === 'function') stmt.__replay();
        }
        return statements.map(() => ({
          results: [{ request_count: 1, window_start: Date.now() }],
          success: true,
        }));
      },
    },
  };
}

describe('Comptage des lancements (register + ping)', () => {
  beforeEach(() => {
    resetMemRateLimitCache();
    resetSeenSignaturesCache();
  });

  it('register n’alimente pas la table devices : il n’alloue qu’une identité', async () => {
    const fake = createFakeDb();
    const env = { APP_SECRET, DB: fake.db };

    const res = await app.fetch(
      await signedRequest('/api/device/register', { appVersion: '1.2.5', deviceModel: 'Pixel', osVersion: 'Android 16' }),
      env as any,
      mockExecCtx as any
    );

    expect(res.status).toBe(200);
    const json = await res.json() as any;
    expect(json.deviceToken).toBeDefined();
    expect(fake.executed.filter((q) => /devices/i.test(q))).toEqual([]);
    expect(fake.devices.size).toBe(0);
  });

  it('le premier lancement compte un seul lancement (et non deux)', async () => {
    const fake = createFakeDb();
    const env = { APP_SECRET, DB: fake.db };

    // Chaîne réelle : l'app s'enregistre, reçoit un token, puis pingue avec CE token.
    const regRes = await app.fetch(await signedRequest('/api/device/register', { appVersion: '1.2.5' }), env as any, mockExecCtx as any);
    const { deviceToken } = await regRes.json() as any;
    expect(deviceToken).toBeDefined();

    const pingRes = await app.fetch(
      await signedRequest('/api/telemetry/ping', { deviceToken, appVersion: '1.2.5' }),
      env as any,
      mockExecCtx as any
    );
    expect(pingRes.status).toBe(200);

    expect([...fake.devices.values()]).toEqual([1]);
  });

  it('chaque ping suivant compte un lancement de plus', async () => {
    const fake = createFakeDb();
    const env = { APP_SECRET, DB: fake.db };

    const r1 = await app.fetch(await signedRequest('/api/telemetry/ping', { deviceToken: DEVICE_TOKEN, appVersion: '1.2.5' }), env as any, mockExecCtx as any);
    expect(r1.status).toBe(200);
    const r2 = await app.fetch(await signedRequest('/api/telemetry/ping', { deviceToken: DEVICE_TOKEN, appVersion: '1.2.5' }), env as any, mockExecCtx as any);
    expect(r2.status).toBe(200);

    expect([...fake.devices.values()]).toEqual([2]);
  });
});

describe('Rétention : jours calendaires distincts', () => {
  /** Faux D1 qui répond par requête pour /api/stats. */
  function statsFakeDb() {
    const statement = (query: string): any => ({
      bind: () => statement(query),
      first: async () => {
        if (/HAVING COUNT\(DISTINCT date\)/.test(query)) return { returning_users: 7 };
        if (/AS eligible_devices/.test(query)) return { eligible_devices: 20, d1_users: 5 };
        if (/AVG\(CASE WHEN launch_count >= 2/.test(query)) {
          return { total_devices: 10, repeat_users: 2, returned_d1: 4, returned_d7: 1, avg_active_span_hours: 30 };
        }
        if (/as total_profiles/.test(query)) return { total_profiles: 3, total_downloads: 5, total_likes: 2 };
        return {};
      },
      all: async () => ({ results: [] }),
      run: async () => ({ success: true }),
    });
    return { prepare: (q: string) => statement(q), batch: async () => [] };
  }

  it('expose la rétention « jours distincts » à côté des métriques existantes', async () => {
    const env = { APP_SECRET, DB: statsFakeDb() };

    const res = await app.fetch(new Request('http://localhost/api/stats'), env as any, mockExecCtx as any);
    expect(res.status).toBe(200);
    const json = await res.json() as any;

    // Nouvelle métrique, calculée par le Worker (7 appareils revenus un autre jour sur 10)
    expect(json.retention.returning_users).toBe(7);
    expect(json.retention.returning_rate).toBe(70);
    // Métriques historiques conservées
    expect(json.retention.total_devices).toBe(10);
    expect(json.retention.repeat_rate).toBe(20);
    // Rétention en cohorte J+1 : 5 appareils revenus sur 20 éligibles
    expect(json.retention.d1_cohort_users).toBe(5);
    expect(json.retention.d1_cohort_eligible).toBe(20);
    expect(json.retention.d1_cohort_rate).toBe(25);
  });
});
