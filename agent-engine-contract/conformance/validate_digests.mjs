import { createHash } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const canonicalDir = join(root, "canonical");
const files = (await readdir(canonicalDir))
  .filter((name) => name.endsWith(".canonical.json"))
  .sort();

if (files.length === 0) {
  throw new Error("no canonical digest fixtures");
}

for (const file of files) {
  const base = file.slice(0, -".canonical.json".length);
  const canonical = (await readFile(join(canonicalDir, file), "utf8")).replace(/\r?\n$/, "");
  const expected = (await readFile(join(canonicalDir, `${base}.sha256`), "utf8")).trim();
  const actual = createHash("sha256").update(canonical, "utf8").digest("hex");
  if (actual !== expected) {
    throw new Error(`${base} digest mismatch: ${actual}`);
  }
}

console.log(`JavaScript canonical digest validation passed: ${files.length} fixtures`);
