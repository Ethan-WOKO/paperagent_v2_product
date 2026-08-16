import { createHash } from 'node:crypto';

/** Compare two strings by Unicode code point order (contract: object keys sorted
 * in ascending Unicode code point order), not UTF-16 code units. */
function compareByCodePoint(a: string, b: string): number {
  const codePoints = (s: string) => {
    const out: number[] = [];
    for (const ch of s) {
      out.push(ch.codePointAt(0)!);
    }
    return out;
  };
  const left = codePoints(a);
  const right = codePoints(b);
  const length = Math.min(left.length, right.length);
  for (let i = 0; i < length; i++) {
    if (left[i] !== right[i]) return left[i] - right[i];
  }
  return left.length - right.length;
}

/** Contract §4 canonical JSON: all object keys sorted recursively in Unicode
 * code point order, arrays keep order, no extra whitespace, JSON escaping. */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) {
    return '[' + value.map(canonicalJson).join(',') + ']';
  }
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).sort((a, b) => compareByCodePoint(a[0], b[0]));
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

/** answerDigest: lowercase SHA-256 of the exact UTF-8 answer bytes. */
export function answerDigestOf(answer: string): string {
  return sha256Hex(answer);
}
