// Tests the relay through HTTP: node --test relay/test
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mailboxFor } from "../protocol.mjs";

const port = 18000 + Math.floor(Math.random() * 1000);
const base = `http://localhost:${port}`;
let server, dataDir;

before(async () => {
  dataDir = await mkdtemp(join(tmpdir(), "encave-relay-"));
  server = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname],
                 { env: { ...process.env, PORT: String(port), DATA_DIR: dataDir }, stdio: "pipe" });
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`${base}/v1/health`)).ok) return; } catch {}
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error("relay did not start");
});

after(async () => {
  server.kill();
  await rm(dataDir, { recursive: true, force: true });
});

const token = "token-of-the-cellar-0123456789";
const inboxToken = "inbox-owner-token-0123456789";
const mailbox = await mailboxFor("c", token);
const inbox = await mailboxFor("i", inboxToken);
const auth = (t = token) => ({ authorization: `Bearer ${t}` });

test("health", async () => {
  const body = await (await fetch(`${base}/v1/health`)).json();
  assert.equal(body.service, "encave-relay");
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
  const { readdir } = await import("node:fs/promises");
  assert.ok(!(await readdir(dataDir)).includes(stranger));
});

test("files are created once and never replaced", async () => {
  const put = () => fetch(`${base}/v1/m/${mailbox}/photo/p`, { method: "PUT", headers: { ...auth(), "if-none-match": "*" }, body: "one" });
  assert.equal((await put()).status, 201);
  assert.equal((await put()).status, 412);
});

test("listing pages through files with a prefix", async () => {
  for (let i = 0; i < 3; i++) {
    await fetch(`${base}/v1/m/${mailbox}/snap/b/${i}`, { method: "PUT", headers: auth(), body: "y" });
  }
  const page = await (await fetch(`${base}/v1/m/${mailbox}?prefix=snap/b/`, { headers: auth() })).json();
  assert.deepEqual(page.files, ["snap/b/0", "snap/b/1", "snap/b/2"]);
  const later = await (await fetch(`${base}/v1/m/${mailbox}?prefix=snap/b/&after=snap/b/0`, { headers: auth() })).json();
  assert.deepEqual(later.files, ["snap/b/1", "snap/b/2"]);
});

test("deleting a file", async () => {
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

test("grants are small", async () => {
  const big = new Uint8Array(4 * 1024 + 1);
  assert.equal((await fetch(`${base}/v1/m/${inbox}/grant/big`, { method: "PUT", body: big })).status, 413);
});

test("bad names and big files are refused", async () => {
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/..%2Fescape`, { method: "PUT", headers: auth(), body: "z" })).status, 400);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/UPPER`, { method: "PUT", headers: auth(), body: "z" })).status, 400);
  assert.equal((await fetch(`${base}/v1/m/not-a-mailbox/x`, { headers: auth() })).status, 404);
  const big = new Uint8Array(8 * 1024 * 1024 + 1);
  assert.equal((await fetch(`${base}/v1/m/${mailbox}/big`, { method: "PUT", headers: auth(), body: big })).status, 413);
});
