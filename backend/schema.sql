CREATE TABLE IF NOT EXISTS profiles (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT,
    game_name TEXT NOT NULL,
    package_name TEXT NOT NULL,
    author_name TEXT NOT NULL,
    controller_type TEXT DEFAULT 'Universal',
    profile_json TEXT NOT NULL,
    device_hash TEXT,
    client_ip TEXT,
    likes_count INTEGER DEFAULT 0,
    dislikes_count INTEGER DEFAULT 0,
    downloads_count INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_profiles_pkg ON profiles(package_name);
CREATE INDEX IF NOT EXISTS idx_profiles_likes ON profiles(likes_count DESC);
CREATE INDEX IF NOT EXISTS idx_profiles_created ON profiles(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_profiles_device ON profiles(device_hash, created_at);
CREATE INDEX IF NOT EXISTS idx_profiles_ip ON profiles(client_ip, created_at);

CREATE TABLE IF NOT EXISTS votes (
    profile_id TEXT NOT NULL,
    device_hash TEXT NOT NULL,
    client_ip TEXT,
    vote_type INTEGER NOT NULL,
    voted_at INTEGER NOT NULL,
    PRIMARY KEY (profile_id, device_hash),
    FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_votes_ip ON votes(client_ip, voted_at);
CREATE INDEX IF NOT EXISTS idx_votes_profile_type ON votes(profile_id, vote_type);

CREATE TABLE IF NOT EXISTS rate_limits (
    key TEXT PRIMARY KEY,
    last_seen INTEGER NOT NULL,
    request_count INTEGER DEFAULT 1,
    window_start INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_window ON rate_limits(window_start);

CREATE TABLE IF NOT EXISTS devices (
    device_hash TEXT PRIMARY KEY,
    first_seen INTEGER NOT NULL,
    last_seen INTEGER NOT NULL,
    app_version TEXT,
    launch_count INTEGER DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen DESC);

CREATE TABLE IF NOT EXISTS daily_activity (
    date TEXT NOT NULL,
    device_hash TEXT NOT NULL,
    app_version TEXT,
    launch_count INTEGER DEFAULT 1,
    last_seen INTEGER NOT NULL,
    PRIMARY KEY (date, device_hash)
);

CREATE INDEX IF NOT EXISTS idx_daily_activity_date ON daily_activity(date);

CREATE TABLE IF NOT EXISTS daily_downloads (
    date TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    count INTEGER DEFAULT 1,
    PRIMARY KEY (date, profile_id)
);

CREATE INDEX IF NOT EXISTS idx_daily_downloads_date ON daily_downloads(date);
