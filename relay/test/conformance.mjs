// One suite, three relays: the shared handler with memory storage, the Worker with
// a memory bucket, and the Node server. They must give the same answers:
// node --test relay/test/conformance.mjs
import { after, describe, test } from "node:test";
import assert from "node:assert/strict";
import worker from "../cloudflare/worker.mjs";
import { LIMITS, MemoryClients, handle, mailboxFor, settings } from "../protocol.mjs";
import { MemoryBucket, MemoryStorage, startRelay, stopRelays } from "./support.mjs";

/// Each variant starts a fresh relay with `env` (relay settings) and returns
/// call(path, init) -> Response.
const variants = {
  "handler and memory storage": async (env) => {
    const storage = new MemoryStorage();
    const options = settings(env);
    const clients = new MemoryClients(options);
    return (path, init) => handle(new Request(`http://relay${path}`, init), storage, options, clients.for("test"));
  },
  "worker and memory bucket": async (env) => {
    const bucket = new MemoryBucket();
    bucket.maxCalls = Infinity;
    return (path, init) => worker.fetch(new Request(`http://relay${path}`, init), { FILES: bucket, ...env });
  },
  "node server": async (env) => {
    const { url } = await startRelay({ RATE_PER_MINUTE: "100000", ...env });
    return (path, init) => fetch(url + path, init);
  },
};

after(stopRelays);

let serial = 0;
/// A mailbox of its own for each use, with its token.
async function own(kind) {
  const token = `conformance-${kind}-${process.pid}-${++serial}-0123456789`;
  return { id: await mailboxFor(kind, token), token, auth: { authorization: `Bearer ${token}` } };
}

for (const [name, start] of Object.entries(variants)) {
  describe(name, () => {
    const put = (call, box, path, body = "x", headers = box.auth) =>
      call(`/v1/m/${box.id}/${path}`, { method: "PUT", headers: { ...headers, "if-none-match": "*" }, body });
    const status = async (promise) => (await promise).status;

    test("a file is created once: a second create gives 412 and changes nothing", async () => {
      const call = await start({});
      const cellar = await own("c");
      assert.equal(await status(put(call, cellar, "photo/p", "one")), 201);
      assert.equal(await status(put(call, cellar, "photo/p", "two")), 412);
      assert.equal(await (await call(`/v1/m/${cellar.id}/photo/p`, { headers: cellar.auth })).text(), "one");
    });

    test("a path is a file or a folder, never both: 409 either way", async () => {
      const call = await start({});
      const cellar = await own("c");
      assert.equal(await status(put(call, cellar, "snap/c")), 201);
      assert.equal(await status(put(call, cellar, "snap/c/1")), 409);
      assert.equal(await status(put(call, cellar, "a/b/1")), 201);
      assert.equal(await status(put(call, cellar, "a/b")), 409);
      assert.equal(await status(call(`/v1/m/${cellar.id}/a/b`, { headers: cellar.auth })), 404);
      const page = await (await call(`/v1/m/${cellar.id}?prefix=`, { headers: cellar.auth })).json();
      assert.deepEqual(page.files, ["a/b/1", "snap/c"]);
    });

    test("more than 500 files are listed in pages, with a next cursor", async () => {
      const call = await start({});
      const cellar = await own("c");
      const names = Array.from({ length: LIMITS.listPage + 1 }, (_, i) => `snap/d/${String(i).padStart(4, "0")}`);
      for (const path of names) assert.equal(await status(put(call, cellar, path)), 201, path);
      const first = await (await call(`/v1/m/${cellar.id}?prefix=snap/`, { headers: cellar.auth })).json();
      assert.equal(first.files.length, LIMITS.listPage);
      assert.equal(first.next, names[LIMITS.listPage - 1]);
      const second = await (await call(`/v1/m/${cellar.id}?prefix=snap/&after=${first.next}`, { headers: cellar.auth })).json();
      assert.deepEqual(second.files, [names[LIMITS.listPage]]);
      assert.equal(second.next ?? undefined, undefined);
    });

    test("grants: anyone may drop up to 4 KiB, only the owner reads; DELETE of a missing file is 204", async () => {
      const call = await start({});
      const inbox = await own("i");
      assert.equal(await status(put(call, inbox, "grant/big", new Uint8Array(LIMITS.maxGrantBytes + 1), {})), 413);
      assert.equal(await status(put(call, inbox, "grant/ok", new Uint8Array(LIMITS.maxGrantBytes), {})), 201);
      assert.equal(await status(put(call, inbox, "other", "x", {})), 401);
      assert.equal(await status(call(`/v1/m/${inbox.id}?prefix=grant/`)), 401);
      const page = await (await call(`/v1/m/${inbox.id}?prefix=grant/`, { headers: inbox.auth })).json();
      assert.deepEqual(page.files, ["grant/ok"]);
      assert.equal(await status(call(`/v1/m/${inbox.id}/grant/big`, { headers: inbox.auth })), 404);
      assert.equal(await status(call(`/v1/m/${inbox.id}/grant/missing`, { method: "DELETE", headers: inbox.auth })), 204);
      assert.equal(await status(call(`/v1/m/${inbox.id}/grant/ok`, { method: "DELETE" })), 401);
    });

    test("anonymous drops cannot take the room of cellars (inboxes are unclaimed until listed)", async () => {
      const call = await start({ MAX_MAILBOXES: "2" });
      const [one, two, three] = [await own("i"), await own("i"), await own("i")];
      // Inboxes nobody has listed yet have a cap of their own.
      assert.equal(await status(put(call, one, "grant/a", "g", {})), 201);
      assert.equal(await status(put(call, two, "grant/a", "g", {})), 201);
      assert.equal(await status(put(call, three, "grant/a", "g", {})), 507);
      // A real cellar's first write still succeeds.
      const cellar = await own("c");
      assert.equal(await status(put(call, cellar, "snap/x/1")), 201);
      // The owner listing its inbox claims it: it moves to the mailboxes' cap.
      assert.equal(await status(call(`/v1/m/${one.id}?prefix=grant/`, { headers: one.auth })), 200);
      assert.equal(await status(put(call, three, "grant/a", "g", {})), 201);
      // Now a cellar and a claimed inbox: the relay is at MAX_MAILBOXES.
      assert.equal(await status(put(call, await own("c"), "snap/x/1")), 507);
      assert.equal(await status(put(call, cellar, "snap/x/2")), 201);
      assert.equal(await status(put(call, one, "grant/b", "g", {})), 201);
      // Listing an inbox that holds nothing creates nothing.
      const empty = await own("i");
      assert.deepEqual((await (await call(`/v1/m/${empty.id}?prefix=grant/`, { headers: empty.auth })).json()).files, []);
      assert.equal(await status(put(call, await own("i"), "grant/a", "g", {})), 507);
    });

    test("each client may create a few new mailboxes a day: then 429", async () => {
      const call = await start({ NEW_MAILBOXES_PER_DAY: "2" });
      const cellar = await own("c");
      assert.equal(await status(put(call, cellar, "snap/x/1")), 201);
      assert.equal(await status(put(call, await own("i"), "grant/a", "g", {})), 201);
      assert.equal(await status(put(call, await own("c"), "snap/x/1")), 429);
      assert.equal(await status(put(call, await own("i"), "grant/a", "g", {})), 429);
      // Mailboxes already there are not new.
      assert.equal(await status(put(call, cellar, "snap/x/2")), 201);
    });

    test("each client has a daily upload budget: then 429, and nothing is stored", async () => {
      const call = await start({ DAILY_UPLOAD_MB: "0.004" });
      const cellar = await own("c");
      assert.equal(await status(put(call, cellar, "b/1", new Uint8Array(2000))), 201);
      assert.equal(await status(put(call, cellar, "b/2", new Uint8Array(2000))), 201);
      assert.equal(await status(put(call, cellar, "b/3", new Uint8Array(2000))), 429);
      assert.equal(await status(call(`/v1/m/${cellar.id}/b/3`, { headers: cellar.auth })), 404);
    });

    test("writes beyond MAX_STORAGE_MB are refused with 507", async () => {
      const call = await start({ MAX_STORAGE_MB: "0.001" });
      const cellar = await own("c");
      assert.equal(await status(put(call, cellar, "a", new Uint8Array(1000))), 201);
      assert.equal(await status(put(call, cellar, "b", new Uint8Array(100))), 507);
      assert.equal(await status(put(call, await own("i"), "grant/a", new Uint8Array(100), {})), 507);
      assert.equal(await status(put(call, cellar, "c", new Uint8Array(40))), 201);
    });
  });
}
