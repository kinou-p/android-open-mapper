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

CREATE TABLE IF NOT EXISTS votes (
    profile_id TEXT NOT NULL,
    device_hash TEXT NOT NULL,
    vote_type INTEGER NOT NULL,
    voted_at INTEGER NOT NULL,
    PRIMARY KEY (profile_id, device_hash),
    FOREIGN KEY (profile_id) REFERENCES profiles(id) ON DELETE CASCADE
);
