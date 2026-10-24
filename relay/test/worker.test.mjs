// Tests the Cloudflare Worker with an in-memory stand-in for an R2 bucket.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../cloudflare/worker.mjs";
import { mailboxFor } from "../protocol.mjs";
import { MemoryBucket } from "./support.mjs";

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
  // Inboxes without an owner mark are unclaimed; cellars always have an owner.
  assert.equal(stats.mailboxes, kept.filter((id) => id.startsWith("c")).length);
  assert.equal(stats.mailboxes + stats.unclaimed, kept.length);
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

test("before the first clean-up pass: an empty bucket starts at zero, else only small writes", async () => {
  const own = "unknown-totals-token-0123456789";
  const mailbox = await mailboxFor("c", own);
  const put = (bucket, path, size, extra = {}) => worker.fetch(new Request(`http://relay/v1/m/${mailbox}/${path}`, {
    method: "PUT", headers: { authorization: `Bearer ${own}` }, body: new Uint8Array(size),
  }), env(bucket, extra)).then((response) => response.status);
  // Empty: nothing to count, so the totals start now and every write adds to them.
  const fresh = new MemoryBucket();
  fresh.maxCalls = Infinity;
  assert.equal(await put(fresh, "a", 1000, { MAX_STORAGE_MB: "0.001" }), 201);
  assert.deepEqual(JSON.parse(new TextDecoder().decode(fresh.objects.get("_relay/stats"))).bytes, 1000);
  assert.equal(await put(fresh, "b", 100, { MAX_STORAGE_MB: "0.001" }), 507);
  // Files there but no totals yet (lost, or a pass not finished): large writes wait.
  const unknown = new MemoryBucket();
  unknown.maxCalls = Infinity;
  unknown.objects.set(`c${"0".repeat(32)}/f/snap/1`, encode("x".repeat(5000)));
  assert.equal(await put(unknown, "big", 5000), 503);
  assert.equal(await put(unknown, "small", 100), 201);
  while (!unknown.objects.has("_relay/stats")) await scheduled(unknown);
  assert.equal(await put(unknown, "big", 5000), 201);
});

test("a write stays well under 50 R2 calls, and the counters hold no address", async () => {
  const bucket = new MemoryBucket();
  const own = "deep-path-token-0123456789";
  const mailbox = await mailboxFor("c", own);
  const headers = { authorization: `Bearer ${own}`, "cf-connecting-ip": "203.0.113.7" };
  for (const path of ["snap/a/b/c/1", "snap/a/b/c/2"]) {
    bucket.invocation();
    const response = await worker.fetch(new Request(`http://relay/v1/m/${mailbox}/${path}`, { method: "PUT", headers, body: "x" }), env(bucket));
    assert.equal(response.status, 201);
    assert.ok(bucket.calls <= 20, `${bucket.calls} calls`);
  }
  const counters = [...bucket.objects.keys()].filter((key) => key.startsWith("_relay/clients/"));
  assert.ok(counters.length >= 2, "a salt and a counter");
  for (const key of counters) {
    assert.ok(!key.includes("203.0.113") && !new TextDecoder().decode(bucket.objects.get(key)).includes("203.0.113"), key);
  }
  // A past day's counters are deleted by the clean-up.
  bucket.objects.set("_relay/clients/2000-01-01/abc", encode({ bytes: 1, mailboxes: 1 }));
  await scheduled(bucket);
  assert.ok(!bucket.objects.has("_relay/clients/2000-01-01/abc"));
  assert.ok(counters.every((key) => bucket.objects.has(key)), "today's are kept");
});
