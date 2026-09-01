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
