// Tests the request handler shared by the Node server and the Worker, in memory:
// node --test relay/test/handler.test.mjs. No server, no network, no timers.
import { test } from "node:test";
import assert from "node:assert/strict";
import { LIMITS, clientKey, handle, isValidMailbox, isValidPath, mailboxFor, settings } from "../protocol.mjs";

const BASE = "http://relay.test";
const cellarToken = "cellar-token-0123456789";
const inboxToken = "inbox-token-0123456789ab";
const cellar = await mailboxFor("c", cellarToken);
const inbox = await mailboxFor("i", inboxToken);

/// Files by mailbox and path, with usage figures a test can raise to reach a limit.
class MemoryStorage {
  constructor() {
    this.files = new Map();
    this.extraUsage = { files: 0, bytes: 0 };
    this.relay = null; // {bytes, mailboxes, full}
    this.createError = null;
    this.exclusiveCalls = 0;
  }
  key(mailbox, path) { return `${mailbox}/${path}`; }
  async list(mailbox, prefix, after, limit) {
    const paths = [...this.files.keys()].filter((k) => k.startsWith(`${mailbox}/`)).map((k) => k.slice(mailbox.length + 1))
      .filter((p) => p.startsWith(prefix) && (!after || p > after)).sort();
    const page = paths.slice(0, limit);
    return { files: page, next: paths.length > limit ? page[page.length - 1] : null };
  }
  async get(mailbox, path) { return this.files.get(this.key(mailbox, path)) ?? null; }
  async create(mailbox, path, bytes) {
    if (this.createError) throw this.createError;
    const key = this.key(mailbox, path);
    if (this.files.has(key)) return false;
    this.files.set(key, bytes);
    return true;
  }
  async delete(mailbox, path) { this.files.delete(this.key(mailbox, path)); }
  async usage(mailbox) {
    const mine = [...this.files.entries()].filter(([k]) => k.startsWith(`${mailbox}/`));
    return { files: mine.length + this.extraUsage.files,
             bytes: mine.reduce((sum, [, v]) => sum + v.length, 0) + this.extraUsage.bytes };
  }
  async totals() { return this.relay; }
  async exclusive(_mailbox, fn) { this.exclusiveCalls++; return fn(); }
}

function request(method, path, { token, key, body, headers = {} } = {}) {
  const all = { ...headers };
  if (token) all.authorization = `Bearer ${token}`;
  if (key) all["x-relay-key"] = key;
  const init = { method, headers: all };
  if (body !== undefined) {
    init.body = body;
    if (body instanceof ReadableStream) init.duplex = "half";
  }
  return new Request(BASE + path, init);
}

async function status(storage, method, path, options = {}, relayOptions = settings()) {
  return (await handle(request(method, path, options), storage, relayOptions)).status;
}

test("health answers GET and HEAD, and refuses other methods", async () => {
  const storage = new MemoryStorage();
  const get = await handle(request("GET", "/v1/health"), storage);
  assert.equal(get.status, 200);
  assert.deepEqual(await get.json(), { service: "encave-relay", version: 1 });
  const head = await handle(request("HEAD", "/v1/health"), storage);
  assert.equal(head.status, 200);
  assert.equal(head.headers.get("content-type"), "application/json");
  assert.equal(await status(storage, "POST", "/v1/health"), 405);
  assert.equal(await status(storage, "GET", "/v2/health"), 404);
  assert.equal(await status(storage, "GET", "/"), 404);
});

test("a private relay wants its access key on every request, health included", async () => {
  const storage = new MemoryStorage();
  const options = settings({ RELAY_ACCESS_KEY: "household-key" });
  assert.equal(await status(storage, "GET", "/v1/health", {}, options), 403);
  assert.equal(await status(storage, "GET", "/v1/health", { key: "wrong" }, options), 403);
  assert.equal(await status(storage, "GET", "/v1/health", { key: "household-key" }, options), 200);
  // The key comes first: without it, even a valid token gets 403.
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}`, { token: cellarToken }, options), 403);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}`, { token: cellarToken, key: "household-key" }, options), 200);
  // A grant drop needs the key too.
  assert.equal(await status(storage, "PUT", `/v1/m/${inbox}/grant/a1`, { body: "x" }, options), 403);
});

test("only the token a mailbox ID comes from opens it", async () => {
  const storage = new MemoryStorage();
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}`), 401);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}`, { token: inboxToken }), 401);
  // Tokens shorter than 16 or longer than 200 characters are no tokens.
  const short = "short-token";
  assert.equal(await status(storage, "GET", `/v1/m/${await mailboxFor("c", short)}`, { token: short }), 401);
  const long = "x".repeat(201);
  assert.equal(await status(storage, "GET", `/v1/m/${await mailboxFor("c", long)}`, { token: long }), 401);
  const longest = "y".repeat(200);
  assert.equal(await status(storage, "GET", `/v1/m/${await mailboxFor("c", longest)}`, { token: longest }), 200);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}`, { headers: { authorization: `Basic ${cellarToken}` } }), 401);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}`, { token: cellarToken }), 200);
});

test("anyone may drop a small grant in an inbox; nothing else goes without a token", async () => {
  const storage = new MemoryStorage();
  assert.equal(await status(storage, "PUT", `/v1/m/${inbox}/grant/from-a`, { body: "sealed" }), 201);
  assert.equal(await status(storage, "PUT", `/v1/m/${inbox}/grant/from-a`, { body: "again" }), 412);
  assert.equal(await status(storage, "GET", `/v1/m/${inbox}/grant/from-a`), 401);
  assert.equal(await status(storage, "GET", `/v1/m/${inbox}`), 401);
  assert.equal(await status(storage, "DELETE", `/v1/m/${inbox}/grant/from-a`), 401);
  assert.equal(await status(storage, "PUT", `/v1/m/${inbox}/other/file`, { body: "x" }), 401);
  // In a cellar, grant/ is an ordinary path.
  assert.equal(await status(storage, "PUT", `/v1/m/${cellar}/grant/from-a`, { body: "x" }), 401);
  // The owner reads and deletes it.
  const read = await handle(request("GET", `/v1/m/${inbox}/grant/from-a`, { token: inboxToken }), storage);
  assert.equal(read.status, 200);
  assert.equal(await read.text(), "sealed");
  assert.equal(await status(storage, "DELETE", `/v1/m/${inbox}/grant/from-a`, { token: inboxToken }), 204);
  assert.equal(await status(storage, "GET", `/v1/m/${inbox}/grant/from-a`, { token: inboxToken }), 404);
});

test("mailbox IDs and paths are checked before storage is touched", async () => {
  const storage = new MemoryStorage();
  for (const id of ["x" + cellar.slice(1), cellar.toUpperCase(), cellar.slice(0, 32), cellar + "0"]) {
    assert.equal(isValidMailbox(id), false, id);
    assert.equal(await status(storage, "GET", `/v1/m/${id}/a`, { token: cellarToken }), 404, id);
  }
  for (const path of ["A", "a b", "a%2Fb", "a.b", "x".repeat(201)]) {
    assert.equal(await status(storage, "PUT", `/v1/m/${cellar}/${path}`, { token: cellarToken, body: "x" }), 400, path);
  }
  assert.equal(isValidPath("x".repeat(200)), true);
  assert.equal(isValidPath(""), false);
  assert.equal(isValidPath(null), false);
  assert.equal(isValidPath("a//b"), false);
  assert.equal(isValidPath("a/../b"), false);
  assert.equal(isValidPath("a/./b"), false);
  // The URL parser resolves dot segments first: they never leave the mailbox.
  assert.equal(await status(storage, "PUT", `/v1/m/${cellar}/../../x`, { token: cellarToken, body: "x" }), 404);
  assert.equal(isValidPath("changes/dev_1/42"), true);
  assert.equal(storage.files.size, 0);
});

test("listing pages by prefix and after, and refuses odd ones", async () => {
  const storage = new MemoryStorage();
  for (const path of ["changes/b/1", "changes/a/2", "changes/a/1", "photos/x"]) {
    assert.equal(await status(storage, "PUT", `/v1/m/${cellar}/${path}`, { token: cellarToken, body: "e" }), 201);
  }
  const list = async (query) => (await handle(request("GET", `/v1/m/${cellar}${query}`, { token: cellarToken }), storage)).json();
  assert.deepEqual((await list("")).files, ["changes/a/1", "changes/a/2", "changes/b/1", "photos/x"]);
  assert.deepEqual((await list("?prefix=changes/a/")).files, ["changes/a/1", "changes/a/2"]);
  assert.deepEqual((await list("?prefix=changes/&after=changes/a/2")).files, ["changes/b/1"]);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}?prefix=Changes`, { token: cellarToken }), 400);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}?prefix=a.b`, { token: cellarToken }), 400);
  assert.equal(await status(storage, "GET", `/v1/m/${cellar}?after=../x`, { token: cellarToken }), 400);
  assert.equal(await status(storage, "PUT", `/v1/m/${cellar}`, { token: cellarToken, body: "x" }), 405);
  assert.equal(await status(storage, "DELETE", `/v1/m/${cellar}`, { token: cellarToken }), 405);
  assert.equal(await status(storage, "POST", `/v1/m/${cellar}/a`, { token: cellarToken, body: "x" }), 405);
});

test("file sizes: the declared length and the bytes actually sent", async () => {
  const storage = new MemoryStorage();
  const put = (path, options) => status(storage, "PUT", `/v1/m/${cellar}/${path}`, { token: cellarToken, ...options });
  assert.equal(await put("big", { headers: { "content-length": String(LIMITS.maxFileBytes + 1) }, body: "x" }), 413);
  assert.equal(await put("odd", { headers: { "content-length": "abc" }, body: "x" }), 413);
  assert.equal(await put("full", { body: new Uint8Array(LIMITS.maxFileBytes) }), 201);
  assert.equal(await put("over", { body: new Uint8Array(LIMITS.maxFileBytes + 1) }), 413);
  // A stream without a length is cut off as soon as it is too large.
  let pulled = 0;
  const chunk = new Uint8Array(1024 * 1024);
  const stream = new ReadableStream({
    pull(controller) {
      pulled++;
      if (pulled > 100) controller.close(); else controller.enqueue(chunk);
    },
  });
  assert.equal(await put("stream", { body: stream }), 413);
  assert.ok(pulled <= LIMITS.maxFileBytes / chunk.length + 2, `read ${pulled} chunks`);
  assert.equal(await put("empty", {}), 201);
  assert.equal((await storage.get(cellar, "empty")).length, 0);
  // Grants are small.
  const grant = (body) => status(storage, "PUT", `/v1/m/${inbox}/grant/g${body.length}`, { body });
  assert.equal(await grant(new Uint8Array(LIMITS.maxGrantBytes)), 201);
  assert.equal(await grant(new Uint8Array(LIMITS.maxGrantBytes + 1)), 413);
  assert.equal([...storage.files.keys()].some((k) => k.endsWith("over") || k.endsWith("stream")), false);
});

test("mailbox, inbox and relay limits answer 507 without storing", async () => {
  const storage = new MemoryStorage();
  const put = (mailbox, token, path, body = "x", options = settings()) =>
    status(storage, "PUT", `/v1/m/${mailbox}/${path}`, { token, body }, options);

  storage.extraUsage = { files: LIMITS.maxMailboxFiles - 1, bytes: 0 };
  assert.equal(await put(cellar, cellarToken, "last"), 201);
  assert.equal(await put(cellar, cellarToken, "one-more"), 507);

  storage.extraUsage = { files: 0, bytes: LIMITS.maxMailboxBytes - 2 };
  assert.equal(await put(cellar, cellarToken, "fits", "y"), 201);
  assert.equal(await put(cellar, cellarToken, "too-much", "zz"), 507);

  storage.extraUsage = { files: LIMITS.maxInboxFiles, bytes: 0 };
  assert.equal(await put(inbox, null, "grant/late"), 507);
  storage.extraUsage = { files: 0, bytes: LIMITS.maxInboxBytes };
  assert.equal(await put(inbox, null, "grant/heavy"), 507);
  storage.extraUsage = { files: 0, bytes: 0 };

  // The whole relay: full, over its storage, or at its number of mailboxes.
  const options = settings({ MAX_STORAGE_MB: "1", MAX_MAILBOXES: "2" });
  storage.relay = { bytes: 0, mailboxes: 0, full: true };
  assert.equal(await put(cellar, cellarToken, "relay-full", "x", options), 507);
  storage.relay = { bytes: 1048576 - 1, mailboxes: 1, full: false };
  assert.equal(await put(cellar, cellarToken, "relay-one-byte", "x", options), 201);
  assert.equal(await put(cellar, cellarToken, "relay-two-bytes", "xx", options), 507);
  storage.relay = { bytes: 0, mailboxes: 2, full: false };
  // A mailbox that already has files may grow; a new one may not start.
  assert.equal(await put(cellar, cellarToken, "existing-mailbox", "x", options), 201);
  const other = await mailboxFor("c", "another-cellar-token-42");
  assert.equal(await put(other, "another-cellar-token-42", "first", "x", options), 507);
  assert.equal(await storage.get(other, "first"), null);
  assert.ok(storage.exclusiveCalls > 0, "limits are checked alone for the mailbox");
});

test("a path that cannot exist is a conflict; other storage errors are not hidden", async () => {
  const storage = new MemoryStorage();
  storage.createError = Object.assign(new Error("parent is a file"), { code: "conflict" });
  assert.equal(await status(storage, "PUT", `/v1/m/${cellar}/a/b`, { token: cellarToken, body: "x" }), 409);
  storage.createError = new Error("disk on fire");
  await assert.rejects(handle(request("PUT", `/v1/m/${cellar}/a/c`, { token: cellarToken, body: "x" }), storage), /disk on fire/);
});

test("without exclusive() or totals(), the handler still works", async () => {
  const storage = new MemoryStorage();
  storage.exclusive = undefined;
  storage.totals = undefined;
  assert.equal(await status(storage, "PUT", `/v1/m/${cellar}/a`, { token: cellarToken, body: "x" }), 201);
  assert.equal(await status(storage, "DELETE", `/v1/m/${cellar}/a`, { token: cellarToken }), 204);
  assert.equal(await status(storage, "DELETE", `/v1/m/${cellar}/a`, { token: cellarToken }), 204);
});

test("settings: only positive numbers replace the defaults", () => {
  const defaults = settings();
  assert.equal(defaults.accessKey, "");
  assert.equal(defaults.maxStorageBytes, LIMITS.maxStorageBytes);
  assert.equal(defaults.maxMailboxes, LIMITS.maxMailboxes);
  for (const value of ["", "0", "-5", "abc", "Infinity", undefined]) {
    const options = settings({ MAX_STORAGE_MB: value, MAX_MAILBOXES: value });
    assert.equal(options.maxStorageBytes, LIMITS.maxStorageBytes, String(value));
    assert.equal(options.maxMailboxes, LIMITS.maxMailboxes, String(value));
  }
  const set = settings({ MAX_STORAGE_MB: "5", MAX_MAILBOXES: "7", RELAY_ACCESS_KEY: "k" });
  assert.equal(set.maxStorageBytes, 5 * 1048576);
  assert.equal(set.maxMailboxes, 7);
  assert.equal(set.accessKey, "k");
});

test("rate limits count an IPv4 address, or the /64 of an IPv6 address", () => {
  const cases = {
    "203.0.113.7": "203.0.113.7",
    " 203.0.113.7 ": "203.0.113.7",
    "::ffff:203.0.113.7": "203.0.113.7",
    "2001:db8:1:2:3:4:5:6": "2001:db8:1:2::/64",
    "2001:DB8:1:2::9": "2001:db8:1:2::/64",
    "2001:db8::1": "2001:db8:0:0::/64",
    "2001:0db8:0001:0002::1%eth0": "2001:db8:1:2::/64",
    "::1": "0:0:0:0::/64",
    "fe80::1%en0": "fe80:0:0:0::/64",
    "": "",
  };
  for (const [address, key] of Object.entries(cases)) assert.equal(clientKey(address), key, address);
  assert.equal(clientKey(undefined), "");
  // Two addresses of one home share a key; two homes do not.
  assert.equal(clientKey("2001:db8:1:2::a"), clientKey("2001:db8:1:2:ffff::b"));
  assert.notEqual(clientKey("2001:db8:1:2::a"), clientKey("2001:db8:1:3::a"));
});
