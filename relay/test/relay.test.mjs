// Tests the relay through HTTP: node --test relay/test
import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { mkdir, readFile, readdir, utimes, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { clientKey, mailboxFor } from "../protocol.mjs";
import { startRelay, stopRelays } from "./support.mjs";
import { checkStored } from "./check-stored.mjs";

let base, dataDir;
before(async () => { ({ url: base, dataDir } = await startRelay()); });
after(stopRelays);

// Every test has its own cellar and inbox, so none depends on another.
let serial = 0;
let token, inboxToken, mailbox, inbox;
beforeEach(async () => {
  serial += 1;
  token = `token-of-the-cellar-${serial}-0123456789`;
  inboxToken = `inbox-owner-token-${serial}-0123456789`;
  mailbox = await mailboxFor("c", token);
  inbox = await mailboxFor("i", inboxToken);
});
const auth = (t = token) => ({ authorization: `Bearer ${t}` });

/// A raw request that sends `chunks` and then waits: resolves with the status as
/// soon as the relay answers, even though the body never ends.
function answerBeforeBody(url, headers, ...chunks) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { reject(new Error("no answer before the end of the body")); req.destroy(); }, 3000);
    const req = httpRequest(url, { method: "PUT", headers }, (res) => {
      clearTimeout(timer);
      resolve(res.statusCode);
      req.destroy();
    });
    req.on("error", () => {});
    for (const chunk of chunks) req.write(chunk);
  });
}

test("health", async () => {
  const body = await (await fetch(`${base}/v1/health`)).json();
  assert.equal(body.service, "encave-relay");
  assert.equal((await fetch(`${base}/v1/health`, { method: "HEAD" })).status, 200);
});

test("a mailbox opens only with the token it is derived from", async () => {
  assert.equal((await fetch(`${base}/v1/m/${mailbox}?prefix=`)).status, 401);
  // Another token cannot claim the mailbox first.
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/a/1`, { method: "PUT", headers: auth("another-token-0123456789"), body: "x" })).status, 401);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/a/1`, { method: "PUT", headers: auth(), body: "x" })).status, 201);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/a/1`, { headers: auth("another-token-0123456789") })).status, 401);
  assert.equal(await (await fetch(`${base}/v1/m/${mailbox}/snap/a/1`, { headers: auth() })).text(), "x");
});

test("reading with a wrong token stores nothing", async () => {
  const stranger = await mailboxFor("c", "a-stranger-token-0123456789");
  assert.equal((await fetch(`${base}/v1/m/${stranger}/x`, { headers: auth("junk-token-0123456789abc") })).status, 401);
  assert.ok(!(await readdir(dataDir)).includes(stranger));
});

test("a wrong token is refused before the body is received", async () => {
  const status = await answerBeforeBody(`${base}/v1/m/${mailbox}/snap/z/1`,
    { ...auth("another-token-0123456789"), "content-length": String(8 * 1024 * 1024) }, "x".repeat(1024));
  assert.equal(status, 401);
});

test("files are created once and never replaced", async () => {
  const put = () => fetch(`${base}/v1/m/${mailbox}/photo/p`, { method: "PUT", headers: { ...auth(), "if-none-match": "*" }, body: "one" });
  assert.equal((await put()).status, 201);
  assert.equal((await put()).status, 412);
});

test("a path under an existing file is a conflict, not an error", async () => {
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/c`, { method: "PUT", headers: auth(), body: "f" })).status, 201);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/c/1`, { method: "PUT", headers: auth(), body: "g" })).status, 409);
  // Deleting a folder deletes nothing.
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap`, { method: "DELETE", headers: auth() })).status, 204);
  const leftovers = (await readdir(join(dataDir, mailbox, "files", "snap"))).filter((name) => name.endsWith(".tmp"));
  assert.deepEqual(leftovers, []);
});

test("listing pages through files with a prefix, and deleting a file", async () => {
  for (let i = 0; i < 3; i++) {
    await fetch(`${base}/v1/m/${mailbox}/snap/b/${i}`, { method: "PUT", headers: auth(), body: "y" });
  }
  const page = await (await fetch(`${base}/v1/m/${mailbox}?prefix=snap/b/`, { headers: auth() })).json();
  assert.deepEqual(page.files, ["snap/b/0", "snap/b/1", "snap/b/2"]);
  const later = await (await fetch(`${base}/v1/m/${mailbox}?prefix=snap/b/&after=snap/b/0`, { headers: auth() })).json();
  assert.deepEqual(later.files, ["snap/b/1", "snap/b/2"]);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/b/0`, { method: "DELETE", headers: auth() })).status, 204);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/snap/b/0`, { headers: auth() })).status, 404);
});

test("anyone can drop a grant in an inbox, only the owner reads it", async () => {
  assert.equal((await fetch(`${base}/v1/m/${inbox}/grant/x-1`, { method: "PUT", body: "g" })).status, 201);
  assert.equal((await fetch(`${base}/v1/m/${inbox}/other`, { method: "PUT", body: "g" })).status, 401);
  assert.equal((await fetch(`${base}/v1/m/${inbox}?prefix=grant/`)).status, 401);
  // Someone else listing first cannot lock the owner out.
  assert.equal((await fetch(`${base}/v1/m/${inbox}?prefix=grant/`, { headers: auth("squatter-token-0123456789") })).status, 401);
  const page = await (await fetch(`${base}/v1/m/${inbox}?prefix=grant/`, { headers: auth(inboxToken) })).json();
  assert.deepEqual(page.files, ["grant/x-1"]);
});

test("grants are small, even without a declared length", async () => {
  const big = new Uint8Array(4 * 1024 + 1);
  assert.equal((await fetch(`${base}/v1/m/${inbox}/grant/big`, { method: "PUT", body: big })).status, 413);
  // Chunked, no Content-Length: refused once 4 KB have arrived.
  const status = await answerBeforeBody(`${base}/v1/m/${inbox}/grant/chunked`, {}, "x".repeat(5000));
  assert.equal(status, 413);
});

test("bad names and big files are refused", async () => {
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/a`, { method: "PUT", headers: auth(), body: "z" })).status, 201);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/..%2Fescape`, { method: "PUT", headers: auth(), body: "z" })).status, 400);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/UPPER`, { method: "PUT", headers: auth(), body: "z" })).status, 400);
  assert.equal((await fetch(`${base}/v1/m/not-a-mailbox/x`, { headers: auth() })).status, 404);
  const big = new Uint8Array(8 * 1024 * 1024 + 1);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/big`, { method: "PUT", headers: auth(), body: big })).status, 413);
});

test("clients are counted by IPv4 address or IPv6 /64", () => {
  assert.equal(clientKey("203.0.113.7"), "203.0.113.7");
  assert.equal(clientKey("::ffff:127.0.0.1"), "127.0.0.1");
  assert.equal(clientKey("2001:db8:1:2:aaaa::1"), "2001:db8:1:2::/64");
  assert.equal(clientKey("2001:db8:1:2:bbbb:cccc:dddd:eeee"), "2001:db8:1:2::/64");
  assert.equal(clientKey("::1"), "0:0:0:0::/64");
});

test("with an access key, every request must carry it", async () => {
  const key = "a-private-relay-key-for-tests";
  const { url } = await startRelay({ RELAY_ACCESS_KEY: key });
  assert.equal((await fetch(`${url}/v1/health`)).status, 403);
  assert.equal((await fetch(`${url}/v1/health`, { headers: { "x-relay-key": "wrong" } })).status, 403);
  assert.equal((await fetch(`${url}/v1/m/${inbox}/grant/x-1`, { method: "PUT", body: "g" })).status, 403);
  assert.equal((await fetch(`${url}/v1/m/${inbox}/grant/x-1`, { method: "PUT", body: "g", headers: { "x-relay-key": key } })).status, 201);
});

test("the relay as a whole has a mailbox and storage limit", async () => {
  const { url } = await startRelay({ MAX_MAILBOXES: "1", MAX_STORAGE_MB: "0.001" });
  const other = await mailboxFor("i", "second-inbox-token-0123456789");
  assert.equal((await fetch(`${url}/v1/m/${inbox}/grant/a`, { method: "PUT", body: "g" })).status, 201);
  // A second unclaimed inbox is refused, files in the first still fit until the storage limit.
  assert.equal((await fetch(`${url}/v1/m/${other}/grant/a`, { method: "PUT", body: "g" })).status, 507);
  assert.equal((await fetch(`${url}/v1/m/${inbox}/grant/b`, { method: "PUT", body: "g" })).status, 201);
  assert.equal((await fetch(`${url}/v1/m/${inbox}/grant/c`, { method: "PUT", body: new Uint8Array(1100) })).status, 507);
});

test("each client has a daily upload budget, counted behind a proxy by the last forwarded address", async () => {
  const { url } = await startRelay({ DAILY_UPLOAD_MB: "0.004", TRUST_PROXY: "1" });
  const put = (name, from) => fetch(`${url}/v1/m/${mailbox}/b/${name}`, {
    method: "PUT", headers: { ...auth(), "x-forwarded-for": from }, body: new Uint8Array(2000),
  });
  assert.equal((await put("1", "198.51.100.1, 203.0.113.9")).status, 201);
  assert.equal((await put("2", "198.51.100.2, 203.0.113.9")).status, 201);
  // A third file would pass 4 KB today for 203.0.113.9, whatever the first entry says.
  assert.equal((await put("3", "198.51.100.3, 203.0.113.9")).status, 429);
  assert.equal((await put("3", "203.0.113.10")).status, 201);
});

test("each client has a rate limit per minute", async () => {
  const { url } = await startRelay({ RATE_PER_MINUTE: "3" });
  const statuses = [];
  for (let i = 0; i < 4; i++) statuses.push((await fetch(`${url}/v1/health`)).status);
  assert.deepEqual(statuses, [200, 200, 200, 429]);
});

test("a chunked upload is cut off when the daily budget runs out, and nothing is stored", async () => {
  const { url, dataDir } = await startRelay({ DAILY_UPLOAD_MB: "0.004" });
  // No Content-Length: the budget is counted as the chunks arrive.
  const chunk = "x".repeat(2000);
  assert.equal(await answerBeforeBody(`${url}/v1/m/${mailbox}/b/1`, auth(), chunk, chunk, chunk), 429);
  assert.equal((await fetch(`${url}/v1/m/${mailbox}/b/1`, { headers: auth() })).status, 404);
  assert.ok(!(await readdir(dataDir)).includes(mailbox), "no mailbox created");
});

test("the clean-up deletes only old mailboxes, and never on an empty marker", async () => {
  const old = new Date(Date.now() - 400 * 86400_000);
  const fresh = await mailboxFor("c", "fresh-mailbox-token-0123456789");
  const stale = await mailboxFor("c", "stale-mailbox-token-0123456789");
  const blank = await mailboxFor("c", "blank-marker-token-0123456789");
  const { dataDir } = await startRelay({}, async (dir) => {
    for (const id of [fresh, stale, blank]) await mkdir(join(dir, id, "files"), { recursive: true });
    await writeFile(join(dir, fresh, "touched"), String(Date.now()));
    await writeFile(join(dir, stale, "touched"), String(old.getTime()));
    // Empty after a crash, but written to just now.
    await writeFile(join(dir, blank, "touched"), "");
    // Not a mailbox: never touched, however old.
    await mkdir(join(dir, "lost+found"));
    await utimes(join(dir, "lost+found"), old, old);
  });
  const left = (await readdir(dataDir)).sort();
  assert.deepEqual(left, [blank, fresh, "lost+found"].sort());
});

test("inboxes only others wrote to go after 90 days; listed ones are kept like cellars", async () => {
  const old = Date.now() - 100 * 86400_000;
  const unclaimed = await mailboxFor("i", "unclaimed-inbox-token-0123456789");
  const claimed = await mailboxFor("i", "claimed-inbox-token-0123456789");
  const { url, dataDir } = await startRelay({}, async (dir) => {
    for (const id of [unclaimed, claimed]) {
      await mkdir(join(dir, id, "files", "grant"), { recursive: true });
      await writeFile(join(dir, id, "files", "grant", "g"), "g");
      await writeFile(join(dir, id, "touched"), String(old));
    }
    await writeFile(join(dir, claimed, "owner"), "");
  });
  assert.deepEqual((await readdir(dataDir)).sort(), [claimed]);
  // A drop does not keep an inbox: the marker keeps the time of the first one.
  const fresh = await mailboxFor("i", "fresh-inbox-token-0123456789");
  assert.equal((await fetch(`${url}/v1/m/${fresh}/grant/a`, { method: "PUT", body: "g" })).status, 201);
  const first = await readFile(join(dataDir, fresh, "touched"), "utf8");
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal((await fetch(`${url}/v1/m/${fresh}/grant/b`, { method: "PUT", body: "g" })).status, 201);
  assert.equal(await readFile(join(dataDir, fresh, "touched"), "utf8"), first);
  // Its owner listing it claims it.
  assert.equal((await fetch(`${url}/v1/m/${fresh}?prefix=grant/`, { headers: auth("fresh-inbox-token-0123456789") })).status, 200);
  assert.ok((await readdir(join(dataDir, fresh))).includes("owner"));
});

test("the stored-data check accepts envelopes and grants only", async () => {
  const { url, dataDir } = await startRelay();
  const envelope = Buffer.concat([Buffer.from("ENCV\x01\x02", "latin1"), Buffer.alloc(40, 7)]);
  const grant = Buffer.concat([Buffer.alloc(32, 9), envelope]);
  assert.equal((await fetch(`${url}/v1/m/${mailbox}/snap/a/1`, { method: "PUT", headers: auth(), body: envelope })).status, 201);
  assert.equal((await fetch(`${url}/v1/m/${inbox}/grant/a`, { method: "PUT", body: grant })).status, 201);
  assert.deepEqual(await checkStored(dataDir), { files: 2, problems: [] });
  assert.equal((await fetch(`${url}/v1/m/${mailbox}/snap/a/2`, { method: "PUT", headers: auth(), body: "Through the relay" })).status, 201);
  const { problems } = await checkStored(dataDir);
  assert.equal(problems.length, 2, problems.join("; "));
});
