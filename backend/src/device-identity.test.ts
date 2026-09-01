import { describe, it, expect } from 'vitest';
import { generateDeviceToken, normalizeDeviceToken, isValidHex64 } from './device-identity';

describe('isValidHex64', () => {
  it('accepte une chaîne hex 64 minuscules', () => {
    expect(isValidHex64('a'.repeat(64))).toBe(true);
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
  it('renvoie null pour un token invalide', async () => {
    expect(await normalizeDeviceToken(undefined)).toBeNull();
    expect(await normalizeDeviceToken('')).toBeNull();
    expect(await normalizeDeviceToken('xyz')).toBeNull();
    expect(await normalizeDeviceToken(123)).toBeNull();
  });
});
