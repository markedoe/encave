#!/usr/bin/env node
// Self-hosted Encave relay: Node 20 or later, no dependencies. Files are kept in
// DATA_DIR (default ./data). Put it behind HTTPS (a reverse proxy such as Caddy).
//
//   PORT=8787 DATA_DIR=/var/lib/encave-relay node relay/server.mjs
//
// It keeps no logs of requests or IP addresses. Mailboxes not written to for
// TTL_DAYS (default 365) are deleted.

import { createServer } from "node:http";
import { link, mkdir, readFile, readdir, rm, stat, unlink, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { handle } from "./protocol.mjs";

const port = Number(process.env.PORT || 8787);
const dataDir = process.env.DATA_DIR || "./data";
const ttlDays = Number(process.env.TTL_DAYS || 365);
const ratePerMinute = Number(process.env.RATE_PER_MINUTE || 300);
// Behind a reverse proxy, the client's address is in X-Forwarded-For (set TRUST_PROXY=1).
const trustProxy = process.env.TRUST_PROXY === "1";

class FileStorage {
  constructor(root) { this.root = root; }
  dir(mailbox) { return join(this.root, mailbox); }
  file(mailbox, path) { return join(this.root, mailbox, "files", ...path.split("/")); }

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
    await mkdir(join(target, ".."), { recursive: true });
    // Written in full to a temporary file, then linked into place: readers never
    // see a partial file, and link fails if the file exists, so none is replaced.
    const temporary = `${target}.${randomUUID()}.tmp`;
    await writeFile(temporary, bytes);
    try {
      await link(temporary, target);
    } catch (error) {
      if (error.code === "EEXIST") return false;
      throw error;
    } finally {
      await unlink(temporary).catch(() => {});
    }
    await writeFile(join(this.dir(mailbox), "touched"), String(Date.now()));
    return true;
  }

  async delete(mailbox, path) {
    await rm(this.file(mailbox, path), { force: true });
  }

  async usage(mailbox) {
    let files = 0, bytes = 0;
    for (const path of await this.walk(mailbox)) {
      files += 1;
      try { bytes += (await stat(this.file(mailbox, path))).size; } catch {}
    }
    return { files, bytes };
  }

  async sweep() {
    let mailboxes;
    try { mailboxes = await readdir(this.root); } catch { return; }
    const limit = Date.now() - ttlDays * 86400_000;
    for (const mailbox of mailboxes) {
      let touched = 0;
      try { touched = Number(await readFile(join(this.dir(mailbox), "touched"), "utf8")); } catch {
        try { touched = (await stat(this.dir(mailbox))).mtimeMs; } catch {}
      }
      if (touched < limit) await rm(this.dir(mailbox), { recursive: true, force: true });
    }
  }
}

// Requests per minute per client, in memory only.
const buckets = new Map();
function allowed(client) {
  const minute = Math.floor(Date.now() / 60_000);
  const bucket = buckets.get(client);
  if (!bucket || bucket.minute !== minute) { buckets.set(client, { minute, count: 1 }); return true; }
  bucket.count += 1;
  return bucket.count <= ratePerMinute;
}
setInterval(() => buckets.clear(), 10 * 60_000).unref();

const storage = new FileStorage(dataDir);
await mkdir(dataDir, { recursive: true });
setInterval(() => storage.sweep().catch(() => {}), 3600_000).unref();

const server = createServer(async (req, res) => {
  try {
    const forwarded = trustProxy ? String(req.headers["x-forwarded-for"] || "").split(",")[0].trim() : "";
    const client = forwarded || req.socket.remoteAddress || "";
    if (!allowed(client)) { res.writeHead(429).end(); return; }
    const chunks = [];
    let size = 0;
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 9 * 1024 * 1024) { res.writeHead(413).end(); return; }
      chunks.push(chunk);
    }
    const body = ["GET", "HEAD", "DELETE"].includes(req.method) ? undefined : Buffer.concat(chunks);
    const request = new Request(`http://relay${req.url}`, { method: req.method, headers: req.headers, body });
    const response = await handle(request, storage);
    const headers = Object.fromEntries(response.headers);
    res.writeHead(response.status, headers);
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch {
    if (!res.headersSent) res.writeHead(500);
    res.end();
  }
});

server.listen(port, () => {
  console.log(`Encave relay listening on port ${port}`);
});
