// Shared by the relay tests: an in-memory R2 bucket, an in-memory storage for the
// shared handler, and a Node relay started on a free port.
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

/// Like R2: sorted keys, startAfter and delimiter, and like the Free plan, at most
/// 50 calls per invocation (reset with `invocation()`).
export class MemoryBucket {
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

/// Storage for handle() that behaves like the Node server's files: a path is a
/// file or a folder, and inboxes only others wrote to are unclaimed.
export class MemoryStorage {
  constructor() { this.files = new Map(); this.mailboxes = new Map(); }
  key(mailbox, path) { return `${mailbox}/${path}`; }
  paths(mailbox) {
    return [...this.files.keys()].filter((k) => k.startsWith(`${mailbox}/`)).map((k) => k.slice(mailbox.length + 1)).sort();
  }
  async list(mailbox, prefix, after, limit) {
    const all = this.paths(mailbox).filter((p) => p.startsWith(prefix) && (!after || p > after));
    const files = all.slice(0, limit);
    return all.length > limit ? { files, next: files[files.length - 1] } : { files };
  }
  async get(mailbox, path) { return this.files.get(this.key(mailbox, path)) ?? null; }
  async create(mailbox, path, bytes, { owner = true } = {}) {
    if (this.files.has(this.key(mailbox, path))) return false;
    const parts = path.split("/");
    const conflict = parts.some((_, i) => i > 0 && this.files.has(this.key(mailbox, parts.slice(0, i).join("/"))))
      || this.paths(mailbox).some((p) => p.startsWith(path + "/"));
    if (conflict) throw Object.assign(new Error("conflict"), { code: "conflict" });
    this.files.set(this.key(mailbox, path), bytes);
    const known = this.mailboxes.get(mailbox);
    this.mailboxes.set(mailbox, { owned: mailbox.startsWith("c") || owner || Boolean(known?.owned) });
    return true;
  }
  async delete(mailbox, path) { this.files.delete(this.key(mailbox, path)); }
  async usage(mailbox) {
    const paths = this.paths(mailbox);
    const known = this.mailboxes.get(mailbox);
    return { files: paths.length, bytes: paths.reduce((sum, p) => sum + this.files.get(this.key(mailbox, p)).length, 0),
             exists: Boolean(known), owned: Boolean(known?.owned) };
  }
  async claim(mailbox, mayClaim) {
    const known = this.mailboxes.get(mailbox);
    if (known && !known.owned && (await mayClaim())) known.owned = true;
  }
  async totals() {
    let bytes = 0, mailboxes = 0, unclaimed = 0;
    for (const value of this.files.values()) bytes += value.length;
    for (const { owned } of this.mailboxes.values()) owned ? mailboxes++ : unclaimed++;
    return { bytes, mailboxes, unclaimed };
  }
}

const relays = [];

/// Starts server.mjs on a free port (PORT=0, the port read from its first line)
/// with its own data folder. Fails if the server exits or stays silent.
export async function startRelay(env = {}, prepare = async () => {}) {
  const dataDir = await mkdtemp(join(tmpdir(), "encave-relay-"));
  await prepare(dataDir);
  const child = spawn(process.execPath, [new URL("../server.mjs", import.meta.url).pathname],
                      { env: { ...process.env, PORT: "0", DATA_DIR: dataDir, ...env }, stdio: ["ignore", "pipe", "pipe"] });
  relays.push({ child, dataDir });
  let errors = "";
  child.stderr.on("data", (chunk) => { errors += chunk; });
  const port = await new Promise((resolve, reject) => {
    let output = "";
    const timer = setTimeout(() => reject(new Error("relay did not start")), 10_000);
    child.stdout.on("data", (chunk) => {
      output += chunk;
      const match = /listening on port (\d+)/.exec(output);
      if (match) { clearTimeout(timer); resolve(Number(match[1])); }
    });
    child.on("exit", (code) => { clearTimeout(timer); reject(new Error(`relay exited (${code}): ${errors}`)); });
  });
  return { url: `http://localhost:${port}`, dataDir };
}

/// Stops every relay started so far and deletes their data.
export async function stopRelays() {
  for (const { child, dataDir } of relays.splice(0)) {
    if (child.exitCode === null) await new Promise((resolve) => { child.once("exit", resolve); child.kill(); });
    await rm(dataDir, { recursive: true, force: true });
  }
}
