#!/usr/bin/env node
// Self-hosted Encave relay: Node 20 or later, no dependencies. Files are kept in
// DATA_DIR (default ./data). Put it behind HTTPS (a reverse proxy such as Caddy).
//
//   PORT=8787 DATA_DIR=/var/lib/encave-relay node relay/server.mjs
//
// It keeps no logs of requests or IP addresses. Mailboxes not written to for
// TTL_DAYS (default 365) are deleted. See relay/README.md for the other settings.

import { createServer } from "node:http";
import { link, mkdir, readFile, readdir, rename, rm, stat, statfs, unlink, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { LIMITS, MAILBOX, clientKey, handle, settings } from "./protocol.mjs";

const port = Number(process.env.PORT || 8787);
const dataDir = process.env.DATA_DIR || "./data";
const ttlDays = Number(process.env.TTL_DAYS || 365);
const ratePerMinute = Number(process.env.RATE_PER_MINUTE || 300);
const dailyUploadBytes = Number(process.env.DAILY_UPLOAD_MB || 2048) * 1048576;
const minFreeBytes = Number(process.env.MIN_FREE_MB || 1024) * 1048576;
// Behind a reverse proxy, the client's address is in X-Forwarded-For (set TRUST_PROXY=1).
const trustProxy = process.env.TRUST_PROXY === "1";
const options = settings(process.env);
const drainBytes = LIMITS.maxFileBytes + 1024 * 1024;

class FileStorage {
  constructor(root) {
    this.root = root;
    this.usages = new Map(); // mailbox -> {files, bytes}: counted by each sweep, kept up to date
    this.locks = new Map();
    this.free = { at: 0, bytes: Infinity };
  }
  dir(mailbox) { return join(this.root, mailbox); }
  file(mailbox, path) { return join(this.root, mailbox, "files", ...path.split("/")); }

  async mailboxes() {
    let entries;
    try { entries = await readdir(this.root); } catch { return []; }
    return entries.filter((name) => MAILBOX.test(name));
  }

  /// Runs fn after every earlier call for the same mailbox has finished.
  exclusive(mailbox, fn) {
    const previous = this.locks.get(mailbox) || Promise.resolve();
    const run = previous.then(fn);
    const tail = run.catch(() => {});
    this.locks.set(mailbox, tail);
    tail.then(() => { if (this.locks.get(mailbox) === tail) this.locks.delete(mailbox); });
    return run;
  }

  async walk(mailbox) {
    const base = join(this.dir(mailbox), "files");
    const out = [];
    async function visit(dir, prefix) {
      let entries;
      try { entries = await readdir(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        const name = prefix ? `${prefix}/${entry.name}` : entry.name;
        if (entry.isDirectory()) await visit(join(dir, entry.name), name);
        else if (!entry.name.endsWith(".tmp")) out.push(name);
      }
    }
    await visit(base, "");
    return out.sort();
  }

  async count(mailbox) {
    let files = 0, bytes = 0;
    for (const path of await this.walk(mailbox)) {
      files += 1;
      try { bytes += (await stat(this.file(mailbox, path))).size; } catch {}
    }
    return { files, bytes };
  }

  async list(mailbox, prefix, after, limit) {
    const all = (await this.walk(mailbox)).filter((path) => path.startsWith(prefix) && (!after || path > after));
    const files = all.slice(0, limit);
    return all.length > limit ? { files, next: files[files.length - 1] } : { files };
  }

  async get(mailbox, path) {
    try { return await readFile(this.file(mailbox, path)); } catch { return null; }
  }

  async create(mailbox, path, bytes) {
    const target = this.file(mailbox, path);
    try {
      await mkdir(join(target, ".."), { recursive: true });
    } catch (error) {
      // A folder on the way is a file: this path cannot exist next to it.
      if (error.code === "ENOTDIR" || error.code === "EEXIST") throw Object.assign(new Error("conflict"), { code: "conflict" });
      throw error;
    }
    // Written in full to a temporary file, then linked into place: readers never
    // see a partial file, and link fails if the file exists, so none is replaced.
    const temporary = `${target}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporary, bytes);
      await link(temporary, target);
    } catch (error) {
      if (error.code === "EEXIST") return false;
      throw error;
    } finally {
      await unlink(temporary).catch(() => {});
    }
    const usage = this.usages.get(mailbox) || { files: 0, bytes: 0 };
    this.usages.set(mailbox, { files: usage.files + 1, bytes: usage.bytes + bytes.length });
    // Written to a temporary file and renamed, so it is never seen empty.
    const touched = join(this.dir(mailbox), `touched.${randomUUID()}.tmp`);
    try {
      await writeFile(touched, String(Date.now()));
      await rename(touched, join(this.dir(mailbox), "touched"));
    } finally {
      await unlink(touched).catch(() => {});
    }
    return true;
  }

  async delete(mailbox, path) {
    const file = this.file(mailbox, path);
    let size;
    try {
      const info = await stat(file);
      if (!info.isFile()) return;
      size = info.size;
      await unlink(file);
    } catch (error) {
      if (error.code === "ENOENT" || error.code === "ENOTDIR") return;
      throw error;
    }
    const usage = this.usages.get(mailbox);
    if (usage) this.usages.set(mailbox, { files: Math.max(0, usage.files - 1), bytes: Math.max(0, usage.bytes - size) });
  }

  async usage(mailbox) {
    return this.usages.get(mailbox) || { files: 0, bytes: 0 };
  }

  async totals() {
    let bytes = 0;
    for (const usage of this.usages.values()) bytes += usage.bytes;
    // Free disk space, checked at most once a minute.
    if (Date.now() - this.free.at > 60_000) {
      try {
        const info = await statfs(this.root);
        this.free = { at: Date.now(), bytes: info.bavail * info.bsize };
      } catch {
        this.free = { at: Date.now(), bytes: Infinity };
      }
    }
    return { bytes, mailboxes: this.usages.size, full: this.free.bytes < minFreeBytes };
  }

  /// When the mailbox was last written to; the current time when unknown, so a
  /// mailbox is never deleted on a guess.
  async lastWrite(mailbox) {
    const marker = join(this.dir(mailbox), "touched");
    try {
      const value = Number(await readFile(marker, "utf8"));
      if (Number.isFinite(value) && value > 0) return value;
    } catch {}
    for (const candidate of [marker, this.dir(mailbox)]) {
      try { return (await stat(candidate)).mtimeMs; } catch {}
    }
    return Date.now();
  }

  /// Deletes mailboxes not written to for `ttlDays`, and recounts the others.
  async sweep() {
    const limit = Date.now() - ttlDays * 86400_000;
    for (const mailbox of await this.mailboxes()) {
      try {
        await this.exclusive(mailbox, async () => {
          if ((await this.lastWrite(mailbox)) < limit) {
            await rm(this.dir(mailbox), { recursive: true, force: true });
            this.usages.delete(mailbox);
          } else {
            this.usages.set(mailbox, await this.count(mailbox));
          }
        });
      } catch {
        // Try this one again next time; carry on with the others.
      }
    }
  }
}

// Requests per minute and bytes uploaded per day, per client, in memory only.
const buckets = new Map();
function allowed(client) {
  const minute = Math.floor(Date.now() / 60_000);
  const bucket = buckets.get(client);
  if (!bucket || bucket.minute !== minute) { buckets.set(client, { minute, count: 1 }); return true; }
  bucket.count += 1;
  return bucket.count <= ratePerMinute;
}
setInterval(() => buckets.clear(), 10 * 60_000).unref();

const uploads = new Map();
function uploaded(client) {
  const day = Math.floor(Date.now() / 86400_000);
  let entry = uploads.get(client);
  if (!entry || entry.day !== day) { entry = { day, bytes: 0 }; uploads.set(client, entry); }
  return entry;
}
setInterval(() => {
  const day = Math.floor(Date.now() / 86400_000);
  for (const [client, entry] of uploads) if (entry.day !== day) uploads.delete(client);
}, 3600_000).unref();

function clientOf(req) {
  if (trustProxy) {
    // The proxy appends the address it saw: the last entry is the only one the
    // client cannot choose.
    const forwarded = String(req.headers["x-forwarded-for"] || "").split(",").pop().trim();
    if (forwarded) return clientKey(forwarded);
  }
  return clientKey(req.socket.remoteAddress);
}

/// The request body as a stream, read only when the handler asks for it, and
/// counted against the client's daily upload budget.
function bodyOf(req, budget) {
  const chunks = req[Symbol.asyncIterator]();
  return new ReadableStream({
    async pull(controller) {
      const { value, done } = await chunks.next();
      if (done) { controller.close(); return; }
      budget.bytes += value.length;
      if (budget.bytes > dailyUploadBytes) {
        controller.error(Object.assign(new Error("daily upload budget"), { status: 429 }));
        return;
      }
      controller.enqueue(new Uint8Array(value.buffer, value.byteOffset, value.length));
    },
  });
}

const storage = new FileStorage(dataDir);
await mkdir(dataDir, { recursive: true });
// Also counts every mailbox kept, before the first request.
await storage.sweep();
setInterval(() => storage.sweep().catch(() => {}), 3600_000).unref();

const server = createServer(async (req, res) => {
  // Answered before the whole body arrived: the rest of a body of declared,
  // reasonable length is received and dropped, so the client reads the answer;
  // for anything larger or of unknown length, the connection is closed.
  const finish = (status, headers = {}, body) => {
    if (!req.complete) {
      const declared = Number(req.headers["content-length"]);
      if (Number.isFinite(declared) && declared <= drainBytes) res.on("finish", () => req.resume());
      else headers = { ...headers, connection: "close" };
    }
    res.writeHead(status, headers);
    res.end(body);
  };
  try {
    const client = clientOf(req);
    if (!allowed(client)) { finish(429); return; }
    const hasBody = !["GET", "HEAD", "DELETE"].includes(req.method);
    let body;
    if (hasBody) {
      const budget = uploaded(client);
      const declared = Number(req.headers["content-length"] || "0");
      if (budget.bytes + (Number.isFinite(declared) ? declared : 0) > dailyUploadBytes) { finish(429); return; }
      body = bodyOf(req, budget);
    }
    const request = new Request(`http://relay${req.url}`, {
      method: req.method, headers: req.headers, body, duplex: hasBody ? "half" : undefined,
    });
    const response = await handle(request, storage, options);
    finish(response.status, Object.fromEntries(response.headers), Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    if (!res.headersSent) finish(error?.status || 500);
    else res.end();
  }
});

server.listen(port, () => {
  console.log(`Encave relay listening on port ${port}`);
});
