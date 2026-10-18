// Tests the Cloudflare Worker with an in-memory stand-in for an R2 bucket.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../cloudflare/worker.mjs";
import { mailboxFor } from "../protocol.mjs";

/// Like R2: sorted keys, startAfter and delimiter, and like the Free plan, at most
/// 50 calls per invocation (reset with `invocation()`).
class MemoryBucket {
  constructor() { this.objects = new Map(); this.calls = 0; this.maxCalls = 50; }
  invocation() { this.calls = 0; }
  count() { if (++this.calls > this.maxCalls) throw new Error("too many subrequests"); }
  async head(key) { this.count(); return this.objects.has(key) ? { key } : null; }
  async get(key) {
    this.count();
    const value = this.objects.get(key);
    if (value === undefined) return null;
    return { text: async () => new TextDecoder().decode(value), arrayBuffer: async () => value.slice().buffer };
  }
  async put(key, value, options = {}) {
    this.count();
    const onlyIfAbsent = options.onlyIf?.get?.("If-None-Match") === "*";
    if (onlyIfAbsent && this.objects.has(key)) return null;
    const bytes = typeof value === "string" ? new TextEncoder().encode(value) : new Uint8Array(value);
    this.objects.set(key, bytes);
    return { key };
  }
  async delete(keys) { this.count(); for (const key of [keys].flat()) this.objects.delete(key); }
  async list({ prefix = "", delimiter, startAfter, cursor, limit = 1000 } = {}) {
    this.count();
    const from = cursor ?? startAfter;
    const keys = [...this.objects.keys()].filter((k) => k.startsWith(prefix) && (!from || k > from)).sort();
    if (delimiter) {
      const prefixes = [...new Set(keys.map((k) => {
        const end = k.indexOf(delimiter, prefix.length);
        return end < 0 ? null : k.slice(0, end + 1);
      }).filter(Boolean))];
      const page = prefixes.slice(0, limit);
      return { objects: [], delimitedPrefixes: page, truncated: prefixes.length > limit,
               cursor: page.length ? page[page.length - 1] + "￿" : undefined };
    }
    const page = keys.slice(0, limit);
    return { objects: page.map((key) => ({ key, size: this.objects.get(key).length })), truncated: keys.length > limit,
             cursor: page.length ? page[page.length - 1] : undefined };
  }
}

const env = (bucket, extra = {}) => ({ FILES: bucket, ...extra });
const token = "token-of-the-cellar-0123456789";
const encode = (value) => new TextEncoder().encode(typeof value === "string" ? value : JSON.stringify(value));

async function scheduled(bucket, extra) {
  bucket.invocation();
  let work;
  await worker.scheduled({}, env(bucket, extra), { waitUntil: (p) => { work = p; } });
  await work;
}

test("files are stored and mailboxes not written to for a year are swept", async () => {
  const bucket = new MemoryBucket();
  const mailbox = await mailboxFor("c", token);
  const put = await worker.fetch(new Request(`http://relay/v1/m/${mailbox}/photo/a`, {
    method: "PUT", headers: { authorization: `Bearer ${token}` }, body: "x",
  }), env(bucket));
  assert.equal(put.status, 201);
  // Written just now: kept.
  await scheduled(bucket);
  assert.ok(bucket.objects.has(`${mailbox}/f/photo/a`));
  // Untouched for more than a year: swept, photo included.
  bucket.objects.set(`${mailbox}/touched`, encode({ time: Date.now() - 400 * 86400_000, bytes: 1 }));
  await scheduled(bucket);
  await scheduled(bucket);
  assert.equal([...bucket.objects.keys()].filter((k) => k.startsWith(mailbox)).length, 0);
});

test("the clean-up of a large relay stays under 50 calls a run and gets through it all", async () => {
  const bucket = new MemoryBucket();
  const old = Date.now() - 400 * 86400_000;
  const kept = [], expired = [];
  for (let i = 0; i < 120; i++) {
    const id = (i % 2 ? "c" : "i") + i.toString(16).padStart(32, "0");
    const isOld = i % 4 === 0;
    (isOld ? expired : kept).push(id);
    // One expired mailbox needs several pages to delete.
    const files = i === 8 ? 2500 : 2;
    for (let f = 0; f < files; f++) bucket.objects.set(`${id}/f/snap/${f}`, encode("x"));
    bucket.objects.set(`${id}/touched`, encode({ time: isOld ? old : Date.now(), bytes: 2 }));
  }
  // A kept mailbox whose marker is unreadable is kept.
  bucket.objects.set(`${kept[0]}/touched`, encode(""));
  let runs = 0;
  while (!bucket.objects.has("_relay/stats") && runs < 100) { await scheduled(bucket); runs += 1; }
  assert.ok(bucket.objects.has("_relay/stats"), "a full pass finishes");
  assert.ok(runs > 2, "it took several runs");
  for (const id of expired) assert.ok(![...bucket.objects.keys()].some((k) => k.startsWith(id)), `${id} deleted`);
  for (const id of kept) assert.ok(bucket.objects.has(`${id}/f/snap/0`), `${id} kept`);
  const stats = JSON.parse(new TextDecoder().decode(bucket.objects.get("_relay/stats")));
  assert.equal(stats.mailboxes, kept.length);
  assert.equal(stats.bytes, (kept.length - 1) * 2);
});

test("the relay-wide limits use the totals of the last clean-up pass", async () => {
  const bucket = new MemoryBucket();
  bucket.maxCalls = Infinity;
  bucket.objects.set("_relay/stats", encode({ bytes: 10, mailboxes: 3 }));
  const putIn = async (kind, name) => {
    const own = `${name}-token-0123456789`;
    const mailbox = await mailboxFor(kind, own);
    return (await worker.fetch(new Request(`http://relay/v1/m/${mailbox}/a`, {
      method: "PUT", headers: { authorization: `Bearer ${own}` }, body: "x",
    }), env(bucket, { MAX_MAILBOXES: "3" }))).status;
  };
  assert.equal(await putIn("c", "new-mailbox"), 507);
  const bucket2 = new MemoryBucket();
  bucket2.objects.set("_relay/stats", encode({ bytes: 10, mailboxes: 2 }));
  const own = "second-token-0123456789";
  const mailbox = await mailboxFor("c", own);
  const status = (await worker.fetch(new Request(`http://relay/v1/m/${mailbox}/a`, {
    method: "PUT", headers: { authorization: `Bearer ${own}` }, body: "x",
  }), env(bucket2, { MAX_MAILBOXES: "3" }))).status;
  assert.equal(status, 201);
});

test("the access key, the rate limiter and its failures", async () => {
  const bucket = new MemoryBucket();
  bucket.maxCalls = Infinity;
  const health = (extra, headers = {}) => worker.fetch(new Request("http://relay/v1/health", { headers }), env(bucket, extra));
  assert.equal((await health({ RELAY_ACCESS_KEY: "private-key-for-tests" })).status, 403);
  assert.equal((await health({ RELAY_ACCESS_KEY: "private-key-for-tests" }, { "x-relay-key": "private-key-for-tests" })).status, 200);
  assert.equal((await health({ RATE_LIMITER: { limit: async () => ({ success: false }) } })).status, 429);
  assert.equal((await health({ RATE_LIMITER: { limit: async () => { throw new Error("down"); } } })).status, 500);
});
