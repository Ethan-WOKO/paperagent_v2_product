import { createHash } from 'node:crypto';

/** Contract §4 canonical JSON: all object keys sorted recursively (Unicode code
 * point order), arrays keep order, no extra whitespace, JSON string escaping. */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) {
    return '[' + value.map(canonicalJson).join(',') + ']';
  }
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).sort(
      (a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0),
    );
    return '{' + entries.map(([k, v]) => JSON.stringify(k) + ':' + canonicalJson(v)).join(',') + '}';
  }
  return JSON.stringify(value);
}

export function sha256Hex(text: string): string {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

export function requestDigestOf(authority: unknown): string {
  return sha256Hex(canonicalJson(authority));
}
