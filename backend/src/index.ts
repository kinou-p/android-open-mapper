import { Hono } from 'hono';
import { cors } from 'hono/cors';

type Bindings = {
  DB: D1Database;
};

const app = new Hono<{ Bindings: Bindings }>();

// Enable CORS for all routes
app.use('*', cors());

// Health & Info
app.get('/', (c) => {
  return c.json({
    name: 'OpenMapper Community API',
    version: '1.1.3',
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
  const game = c.req.query('game'); // package_name or game_name filter
  const search = c.req.query('search');
  const sort = c.req.query('sort') || 'popular'; // popular, recent, downloads
  const page = Math.max(1, parseInt(c.req.query('page') || '1'));
  const limit = Math.min(50, Math.max(1, parseInt(c.req.query('limit') || '20')));
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
    query += ` AND (package_name = ? OR game_name LIKE ?)`;
    params.push(game, `%${game}%`);
  }

  if (search) {
    query += ` AND (title LIKE ? OR description LIKE ? OR author_name LIKE ? OR game_name LIKE ?)`;
    const s = `%${search}%`;
    params.push(s, s, s, s);
  }

  if (sort === 'recent') {
    query += ` ORDER BY created_at DESC`;
  } else if (sort === 'downloads') {
    query += ` ORDER BY downloads_count DESC, likes_count DESC`;
  } else {
    // popular by default: (likes - dislikes) then likes
    query += ` ORDER BY (likes_count - dislikes_count) DESC, likes_count DESC, created_at DESC`;
  }

  query += ` LIMIT ? OFFSET ?`;
  params.push(limit, offset);

  try {
    const { results } = await c.env.DB.prepare(query).bind(...params).all();

    // Count total
    let countQuery = `SELECT COUNT(*) as total FROM profiles WHERE 1=1`;
    const countParams: any[] = [];
    if (game) {
      countQuery += ` AND (package_name = ? OR game_name LIKE ?)`;
      countParams.push(game, `%${game}%`);
    }
    if (search) {
      countQuery += ` AND (title LIKE ? OR description LIKE ? OR author_name LIKE ? OR game_name LIKE ?)`;
      const s = `%${search}%`;
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
    return c.json({ success: false, error: err.message }, 500);
  }
});

// 2. Get Single Profile Details (including JSON payload)
app.get('/api/profiles/:id', async (c) => {
  const id = c.req.param('id');
  try {
    const profile = await c.env.DB.prepare(`
      SELECT * FROM profiles WHERE id = ?
    `).bind(id).first();

    if (!profile) {
      return c.json({ success: false, error: 'Profile not found' }, 404);
    }

    return c.json({
      success: true,
      profile
    });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

const HASH_REGEX = /^[a-f0-9]{64}$/i;
const MAX_PROFILE_JSON_BYTES = 16 * 1024; // 16 KB max per profile

// 3. Publish a Community Profile
app.post('/api/profiles', async (c) => {
  try {
    const body = await c.req.json();
    const { title, description, game_name, package_name, author_name, controller_type, profile_json, deviceHash } = body;

    if (!title || !game_name || !package_name || !profile_json) {
      return c.json({ success: false, error: 'Champs requis manquants (titre, nom du jeu, package_name, configuration)' }, 400);
    }

    // 1. Validate device fingerprint
    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash)) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou manquante' }, 400);
    }

    // 2. Validate that profile_json is valid JSON
    let parsed;
    try {
      parsed = typeof profile_json === 'string' ? JSON.parse(profile_json) : profile_json;
    } catch {
      return c.json({ success: false, error: 'Format JSON invalide dans profile_json' }, 400);
    }

    const finalJsonString = typeof profile_json === 'string' ? profile_json : JSON.stringify(profile_json);

    // 3. Check payload size limit (max 16 KB)
    if (finalJsonString.length > MAX_PROFILE_JSON_BYTES) {
      return c.json({ success: false, error: 'La taille du profil dépasse la limite autorisée (16 Ko max)' }, 400);
    }

    const now = Date.now();

    // 4. Anti-spam: Cooldown check (minimum 15 seconds between publications per device)
    const lastSubmission: any = await c.env.DB.prepare(`
      SELECT created_at FROM profiles WHERE device_hash = ? ORDER BY created_at DESC LIMIT 1
    `).bind(deviceHash).first();

    if (lastSubmission && (now - lastSubmission.created_at) < 15_000) {
      return c.json({ success: false, error: 'Veuillez patienter 15 secondes entre chaque publication.' }, 429);
    }

    // 5. Anti-spam: Daily quota (maximum 10 profiles per 24 hours per device)
    const oneDayAgo = now - 24 * 60 * 60 * 1000;
    const dailyCount: any = await c.env.DB.prepare(`
      SELECT COUNT(*) as count FROM profiles WHERE device_hash = ? AND created_at > ?
    `).bind(deviceHash, oneDayAgo).first();

    if (dailyCount && dailyCount.count >= 10) {
      return c.json({ success: false, error: 'Limite journalière atteinte : maximum 10 profils par 24h par appareil.' }, 429);
    }

    const id = crypto.randomUUID();

    await c.env.DB.prepare(`
      INSERT INTO profiles (
        id, title, description, game_name, package_name, author_name, controller_type,
        profile_json, device_hash, likes_count, dislikes_count, downloads_count, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?)
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
      now,
      now
    ).run();

    return c.json({
      success: true,
      id,
      message: 'Profile published successfully!'
    }, 201);
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

// 4. Vote on a Profile (Like / Dislike / Cancel)
app.post('/api/profiles/:id/vote', async (c) => {
  const profileId = c.req.param('id');
  try {
    const body = await c.req.json();
    const { deviceHash, vote } = body; // vote: 1 (like), -1 (dislike), 0 (cancel)

    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash) || vote === undefined) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou paramètre de vote manquant' }, 400);
    }

    const voteVal = parseInt(vote);
    if (![1, -1, 0].includes(voteVal)) {
      return c.json({ success: false, error: 'Vote must be 1, -1 or 0' }, 400);
    }

    // Check existing vote
    const existingVote: any = await c.env.DB.prepare(`
      SELECT vote_type FROM votes WHERE profile_id = ? AND device_hash = ?
    `).bind(profileId, deviceHash).first();

    const now = Date.now();
    let likeDelta = 0;
    let dislikeDelta = 0;

    if (!existingVote) {
      if (voteVal === 1) {
        likeDelta = 1;
        await c.env.DB.prepare(`
          INSERT INTO votes (profile_id, device_hash, vote_type, voted_at) VALUES (?, ?, 1, ?)
        `).bind(profileId, deviceHash, now).run();
      } else if (voteVal === -1) {
        dislikeDelta = 1;
        await c.env.DB.prepare(`
          INSERT INTO votes (profile_id, device_hash, vote_type, voted_at) VALUES (?, ?, -1, ?)
        `).bind(profileId, deviceHash, now).run();
      }
    } else {
      const prev = existingVote.vote_type;
      if (voteVal === 0 || voteVal === prev) {
        // Cancel vote
        if (prev === 1) likeDelta = -1;
        if (prev === -1) dislikeDelta = -1;
        await c.env.DB.prepare(`
          DELETE FROM votes WHERE profile_id = ? AND device_hash = ?
        `).bind(profileId, deviceHash).run();
      } else {
        // Switch vote
        if (prev === 1 && voteVal === -1) {
          likeDelta = -1;
          dislikeDelta = 1;
        } else if (prev === -1 && voteVal === 1) {
          dislikeDelta = -1;
          likeDelta = 1;
        }
        await c.env.DB.prepare(`
          UPDATE votes SET vote_type = ?, voted_at = ? WHERE profile_id = ? AND device_hash = ?
        `).bind(voteVal, now, profileId, deviceHash).run();
      }
    }

    if (likeDelta !== 0 || dislikeDelta !== 0) {
      await c.env.DB.prepare(`
        UPDATE profiles 
        SET likes_count = MAX(0, likes_count + ?),
            dislikes_count = MAX(0, dislikes_count + ?)
        WHERE id = ?
      `).bind(likeDelta, dislikeDelta, profileId).run();
    }

    // Return updated stats
    const updated: any = await c.env.DB.prepare(`
      SELECT likes_count, dislikes_count FROM profiles WHERE id = ?
    `).bind(profileId).first();

    return c.json({
      success: true,
      likes: updated?.likes_count || 0,
      dislikes: updated?.dislikes_count || 0,
      currentVote: (existingVote && (voteVal === 0 || voteVal === existingVote.vote_type)) ? 0 : voteVal
    });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

// 5. Increment Download Counter
app.post('/api/profiles/:id/download', async (c) => {
  const profileId = c.req.param('id');
  try {
    await c.env.DB.prepare(`
      UPDATE profiles SET downloads_count = downloads_count + 1 WHERE id = ?
    `).bind(profileId).run();

    return c.json({ success: true });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

// 6. Telemetry Ping (Anonymous Unique Devices & Active Sessions)
app.post('/api/telemetry/ping', async (c) => {
  try {
    const body = await c.req.json();
    const { deviceHash, appVersion } = body;

    if (!deviceHash || typeof deviceHash !== 'string' || !HASH_REGEX.test(deviceHash)) {
      return c.json({ success: false, error: 'Empreinte d\'appareil invalide ou manquante' }, 400);
    }

    const now = Date.now();
    const version = typeof appVersion === 'string' ? appVersion.slice(0, 20) : '1.0.0';

    await c.env.DB.prepare(`
      INSERT INTO devices (device_hash, first_seen, last_seen, app_version, launch_count)
      VALUES (?, ?, ?, ?, 1)
      ON CONFLICT(device_hash) DO UPDATE SET
        last_seen = excluded.last_seen,
        app_version = excluded.app_version,
        launch_count = launch_count + 1
    `).bind(deviceHash, now, now, version).run();

    return c.json({ success: true });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

// 7. Global Statistics (Unique Devices, Active Devices, Profiles & Downloads)
app.get('/api/stats', async (c) => {
  try {
    const now = Date.now();
    const dayAgo = now - 24 * 60 * 60 * 1000;
    const weekAgo = now - 7 * 24 * 60 * 60 * 1000;
    const monthAgo = now - 30 * 24 * 60 * 60 * 1000;

    const devicesStats: any = await c.env.DB.prepare(`
      SELECT 
        COUNT(*) as total_devices,
        SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) as active_24h,
        SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) as active_7d,
        SUM(CASE WHEN last_seen >= ? THEN 1 ELSE 0 END) as active_30d,
        COALESCE(SUM(launch_count), 0) as total_launches
      FROM devices
    `).bind(dayAgo, weekAgo, monthAgo).first();

    const profileStats: any = await c.env.DB.prepare(`
      SELECT 
        COUNT(*) as total_profiles,
        COALESCE(SUM(downloads_count), 0) as total_downloads,
        COALESCE(SUM(likes_count), 0) as total_likes
      FROM profiles
    `).first();

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
      }
    });
  } catch (err: any) {
    return c.json({ success: false, error: err.message }, 500);
  }
});

export default app;
