// Tests the Cloudflare Worker with an in-memory stand-in for an R2 bucket.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../cloudflare/worker.mjs";
import { mailboxFor } from "../protocol.mjs";

class MemoryBucket {
  constructor() { this.objects = new Map(); }
  async head(key) { return this.objects.has(key) ? { key } : null; }
  async get(key) {
    const value = this.objects.get(key);
    if (value === undefined) return null;
    return { text: async () => new TextDecoder().decode(value), arrayBuffer: async () => value.buffer };
  }
  async put(key, value, options = {}) {
    const onlyIfAbsent = options.onlyIf?.get?.("If-None-Match") === "*";
    if (onlyIfAbsent && this.objects.has(key)) return null;
    const bytes = typeof value === "string" ? new TextEncoder().encode(value) : new Uint8Array(value);
    this.objects.set(key, bytes);
    return { key };
  }
  async delete(keys) { for (const key of [keys].flat()) this.objects.delete(key); }
  async list({ prefix = "", delimiter, startAfter, limit = 1000 } = {}) {
    const keys = [...this.objects.keys()].filter((k) => k.startsWith(prefix) && (!startAfter || k > startAfter)).sort();
    if (delimiter) {
      const prefixes = [...new Set(keys.map((k) => k.slice(0, k.indexOf(delimiter, prefix.length) + 1)).filter(Boolean))];
      return { objects: [], delimitedPrefixes: prefixes, truncated: false };
    }
    const page = keys.slice(0, limit);
    return { objects: page.map((key) => ({ key, size: this.objects.get(key).length })), truncated: keys.length > limit };
  }
}

const env = (bucket) => ({ FILES: bucket });
const token = "token-of-the-cellar-0123456789";

test("files are stored and mailboxes not written to for a year are swept", async () => {
  const bucket = new MemoryBucket();
  const mailbox = await mailboxFor("c", token);
  const put = await worker.fetch(new Request(`http://relay/v1/m/${mailbox}/photo/a`, {
    method: "PUT", headers: { authorization: `Bearer ${token}` }, body: "x",
  }), env(bucket));
  assert.equal(put.status, 201);
  // Written just now: kept.
  let kept;
  await worker.scheduled({}, env(bucket), { waitUntil: (p) => { kept = p; } });
  await kept;
  assert.ok(bucket.objects.has(`${mailbox}/f/photo/a`));
  // Untouched for more than a year: swept, photo included.
  bucket.objects.set(`${mailbox}/touched`, new TextEncoder().encode(String(Date.now() - 400 * 86400_000)));
  let done;
  await worker.scheduled({}, env(bucket), { waitUntil: (p) => { done = p; } });
  await done;
  assert.equal([...bucket.objects.keys()].filter((k) => k.startsWith(mailbox)).length, 0);
});
