import { describe, it, expect } from 'vitest';
import { generateDeviceToken, normalizeDeviceToken, isValidHex64 } from './device-identity';

describe('isValidHex64', () => {
  it('accepte une chaîne hex 64 majuscules ou minuscules', () => {
    expect(isValidHex64('a'.repeat(64))).toBe(true);
    expect(isValidHex64('A'.repeat(64))).toBe(true);
    expect(isValidHex64('aAbBcC0123456789'.repeat(4))).toBe(true);
  });
  it('rejette les longueurs et caractères invalides', () => {
    expect(isValidHex64('a'.repeat(63))).toBe(false);
    expect(isValidHex64('g'.repeat(64))).toBe(false);
    expect(isValidHex64(null)).toBe(false);
    expect(isValidHex64(12345)).toBe(false);
  });
});

describe('generateDeviceToken', () => {
  it('produit un token hex 64 et des tokens différents', () => {
    const a = generateDeviceToken();
    const b = generateDeviceToken();
    expect(a).toMatch(/^[a-f0-9]{64}$/);
    expect(a).not.toEqual(b);
  });
});

describe('normalizeDeviceToken', () => {
  it('hash un token valide en 64 hex, différent de la valeur d’entrée', async () => {
    const input = 'a'.repeat(64);
    const hash = await normalizeDeviceToken(input);
    expect(hash).toMatch(/^[a-f0-9]{64}$/);
    expect(hash).not.toEqual(input);
  });
  it('génère un hash identique quelle que soit la casse du token', async () => {
    const lower = 'abcdef0123456789'.repeat(4);
    const upper = 'ABCDEF0123456789'.repeat(4);
    const hashLower = await normalizeDeviceToken(lower);
    const hashUpper = await normalizeDeviceToken(upper);
    expect(hashLower).toBe(hashUpper);
  });
  it('renvoie null pour un token invalide', async () => {
    expect(await normalizeDeviceToken(undefined)).toBeNull();
    expect(await normalizeDeviceToken('')).toBeNull();
    expect(await normalizeDeviceToken('xyz')).toBeNull();
    expect(await normalizeDeviceToken(123)).toBeNull();
  });
});
