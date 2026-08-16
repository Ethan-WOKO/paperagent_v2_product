import { createHash, timingSafeEqual } from "node:crypto";

export function canonicalJson(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "number" || typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (typeof value === "object") {
    const object = value as Record<string, unknown>;
    return `{${Object.keys(object).sort(compareCodePoints).map((key) => `${JSON.stringify(key)}:${canonicalJson(object[key])}`).join(",")}}`;
  }
  throw new TypeError("Unsupported canonical JSON value");
}

export const sha256 = (value: string): string => createHash("sha256").update(value, "utf8").digest("hex");
export const digestObject = (value: unknown): string => sha256(canonicalJson(value));

export function safeEqual(left: string, right: string): boolean {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

export function bounded(value: string, maximum: number): string {
  return value.length <= maximum ? value : `${value.slice(0, maximum - 1)}…`;
}

export const terminal = (state: string): boolean => ["succeeded", "failed", "cancelled"].includes(state);

export class EngineProblem extends Error {
  constructor(public readonly status: number, public readonly problem: import("./types.js").Problem) {
    super(problem.message);
  }
}

export function problem(code: string, category: import("./types.js").Problem["category"], message: string, retryable = false, sourceRef?: string): import("./types.js").Problem {
  return { contractVersion: "1.0", code, category, message: bounded(message, 1000), retryable, ...(sourceRef ? { sourceRef } : {}) };
}

function compareCodePoints(left: string, right: string): number {
  const a = [...left].map((character) => character.codePointAt(0)!);
  const b = [...right].map((character) => character.codePointAt(0)!);
  for (let index = 0; index < Math.min(a.length, b.length); index += 1) if (a[index] !== b[index]) return a[index]! - b[index]!;
  return a.length - b.length;
}
