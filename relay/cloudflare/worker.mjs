// Encave relay on Cloudflare Workers, with files in an R2 bucket. Deploy with
// Wrangler (see relay/README.md). Same API as the Node server: relay/protocol.mjs.
//
// Mailboxes not written to by their owner for TTL_DAYS (default 365), and inboxes
// only others wrote to after 90 days, are deleted by a scheduled run (the cron
// trigger in wrangler.toml), like the Node server does. Each run stays under the
// Free plan's 50 subrequests per invocation (R2 calls count) and carries on where
// the previous one stopped. A full pass recounts the relay's mailboxes and bytes;
// in between, each write adds to those totals, which the relay-wide limits are
// checked against. Per-client daily counters are R2 objects too (see R2Clients).
// Do not use an R2 lifecycle rule: it deletes each file a year after it was
// written, including photos that are written once and still in use.

import { LIMITS, MAILBOX, clientKey, handle, settings } from "../protocol.mjs";

// Outside the mailboxes (whose IDs start with c or i): the clean-up's position,
// the totals and the per-client counters. "_relay/" sorts before every mailbox.
const SWEEP_STATE = "_relay/sweep";
const STATS = "_relay/stats";
const CLIENTS = "_relay/clients/";
const SUBREQUESTS_PER_RUN = 45;

/// "touched" holds the time of the owner's last write (or of the first drop in an
/// unclaimed inbox), the mailbox's size then, and whether its owner uses it.
function parseTouched(text, mailbox) {
  const cellar = mailbox.startsWith("c");
  try {
    const value = JSON.parse(text);
    if (typeof value === "number") return { time: value, bytes: 0, owner: cellar };
    return { time: Number(value.time), bytes: Number(value.bytes) || 0, owner: cellar || value.owner === true };
  } catch {
    return { time: NaN, bytes: 0, owner: cellar };
  }
}

async function readJSON(object) {
  try { return object ? JSON.parse(await object.text()) : null; } catch { return null; }
}

const utcDay = (time = Date.now()) => new Date(time).toISOString().slice(0, 10);

export class R2Storage {
  constructor(bucket) {
    this.bucket = bucket;
    this.usages = new Map();
    this.marks = new Map(); // mailbox -> parsed "touched", or null: read by usage() and claim()
    this.stats = undefined; // the totals read by totals() for this request
  }
  key(mailbox, path) { return `${mailbox}/f/${path}`; }

  async mark(mailbox) {
    if (!this.marks.has(mailbox)) {
      const object = await this.bucket.get(`${mailbox}/touched`);
      this.marks.set(mailbox, object ? parseTouched(await object.text(), mailbox) : null);
    }
    return this.marks.get(mailbox);
  }

  async saveMark(mailbox, mark) {
    this.marks.set(mailbox, mark);
    await this.bucket.put(`${mailbox}/touched`, JSON.stringify(mark));
  }

  /// Adds to the running totals read by totals() in this request.
  async addToStats(change) {
    if (!this.stats) return;
    for (const [name, value] of Object.entries(change)) this.stats[name] = Math.max(0, (this.stats[name] || 0) + value);
    await this.bucket.put(STATS, JSON.stringify(this.stats));
  }

  async list(mailbox, prefix, after, limit) {
    const base = `${mailbox}/f/`;
    const listed = await this.bucket.list({
      prefix: base + prefix,
      startAfter: after ? base + after : undefined,
      limit,
    });
    const files = listed.objects.map((object) => object.key.slice(base.length));
    return listed.truncated && files.length ? { files, next: files[files.length - 1] } : { files };
  }

  async get(mailbox, path) {
    const object = await this.bucket.get(this.key(mailbox, path));
    return object ? new Uint8Array(await object.arrayBuffer()) : null;
  }

  async create(mailbox, path, bytes, { owner = true } = {}) {
    const key = this.key(mailbox, path);
    // Each path has one writer, so checking first is enough; the conditional
    // header also makes R2 refuse to replace an existing object.
    if (await this.bucket.head(key)) return false;
    // Like folders on a disk: a path is a file or a folder, never both.
    const conflict = () => Object.assign(new Error("conflict"), { code: "conflict" });
    const parts = path.split("/");
    for (let i = 1; i < parts.length; i++) {
      if (await this.bucket.head(this.key(mailbox, parts.slice(0, i).join("/")))) throw conflict();
    }
    if ((await this.bucket.list({ prefix: key + "/", limit: 1 })).objects.length) throw conflict();
    const stored = await this.bucket.put(key, bytes, { onlyIf: new Headers({ "If-None-Match": "*" }) });
    if (stored === null) return false;
    // When the owner last wrote, the mailbox's size and whether its owner uses it,
    // for the clean-up and the totals. A drop by someone else keeps the time.
    const before = await this.mark(mailbox);
    const size = (this.usages.get(mailbox)?.bytes ?? 0) + bytes.length;
    const owned = owner || before?.owner === true;
    await this.saveMark(mailbox, { time: owner || !before ? Date.now() : before.time, bytes: size, owner: owned });
    const change = { bytes: bytes.length };
    if (!before) change[owned ? "mailboxes" : "unclaimed"] = 1;
    else if (owned && !before.owner) Object.assign(change, { mailboxes: 1, unclaimed: -1 });
    await this.addToStats(change);
    return true;
  }

  /// The owner lists its inbox: claimed if `mayClaim()` allows, and kept (the
  /// marker refreshed at most once a day). Nothing is created for an unknown inbox.
  async claim(mailbox, mayClaim) {
    const mark = await this.mark(mailbox);
    if (!mark) return;
    if (mark.owner) {
      if (Date.now() - mark.time > 86400_000) await this.saveMark(mailbox, { ...mark, time: Date.now() });
      return;
    }
    if (!(await mayClaim())) return;
    await this.saveMark(mailbox, { ...mark, time: Date.now(), owner: true });
    await this.addToStats({ mailboxes: 1, unclaimed: -1 });
  }

  async delete(mailbox, path) {
    await this.bucket.delete(this.key(mailbox, path));
  }

  async usage(mailbox) {
    let files = 0, bytes = 0, cursor;
    do {
      const listed = await this.bucket.list({ prefix: `${mailbox}/f/`, cursor, limit: 1000 });
      for (const object of listed.objects) { files += 1; bytes += object.size; }
      cursor = listed.truncated ? listed.cursor : undefined;
    } while (cursor);
    this.usages.set(mailbox, { files, bytes });
    const mark = await this.mark(mailbox);
    return { files, bytes, exists: Boolean(mark) || files > 0, owned: Boolean(mark?.owner) };
  }

  /// Totals of the last full clean-up pass, plus every write since. Before the
  /// first pass: zero for an empty bucket, else unknown.
  async totals() {
    let value = await readJSON(await this.bucket.get(STATS));
    if (!value) {
      // Anything after "_relay/…" is a mailbox's.
      if ((await this.bucket.list({ startAfter: "_relay0", limit: 1 })).objects.length) return { unknown: true };
      value = { bytes: 0, mailboxes: 0, unclaimed: 0, time: Date.now() };
      await this.bucket.put(STATS, JSON.stringify(value));
    }
    this.stats = { bytes: Number(value.bytes) || 0, mailboxes: Number(value.mailboxes) || 0,
                   unclaimed: Number(value.unclaimed) || 0, time: value.time };
    return { ...this.stats };
  }

  /// Deletes mailboxes not written to for `ttlDays` (unclaimed inboxes: 90 days at
  /// most) and the counters of past days, at most `budget` R2 calls per run; the
  /// next run carries on from where this one stopped.
  async sweep(ttlDays, budget = SUBREQUESTS_PER_RUN) {
    const limit = Date.now() - ttlDays * 86400_000;
    const unclaimedLimit = Date.now() - Math.min(ttlDays, LIMITS.unclaimedInboxDays) * 86400_000;
    let calls = 0;
    const call = (promise) => { calls += 1; return promise; };
    let state = { after: "", bytes: 0, mailboxes: 0, unclaimed: 0 };
    try {
      const saved = await call(this.bucket.get(SWEEP_STATE));
      if (saved) state = { ...state, ...JSON.parse(await saved.text()) };
    } catch {}
    // Leaves room for saving the state and the totals.
    const room = (needed) => calls + needed <= budget - 2;
    // Counters of past days: one page a run is plenty.
    const days = await call(this.bucket.list({ prefix: CLIENTS, delimiter: "/" }));
    const old = (days.delimitedPrefixes || []).find((prefix) => prefix.slice(CLIENTS.length, -1) < utcDay());
    if (old) {
      const page = await call(this.bucket.list({ prefix: old, limit: 1000 }));
      if (page.objects.length) await call(this.bucket.delete(page.objects.map((item) => item.key)));
    }
    let stopped = false;
    while (!stopped && room(1)) {
      const listed = await call(this.bucket.list({ delimiter: "/", startAfter: state.after || undefined, limit: 100 }));
      const prefixes = listed.delimitedPrefixes || [];
      if (!prefixes.length) {
        // A full pass: publish the totals and start again next time.
        await call(this.bucket.put(STATS, JSON.stringify({ bytes: state.bytes, mailboxes: state.mailboxes,
                                                          unclaimed: state.unclaimed, time: Date.now() })));
        state = { after: "", bytes: 0, mailboxes: 0, unclaimed: 0 };
        break;
      }
      for (const prefix of prefixes) {
        const mailbox = prefix.slice(0, -1);
        // Every key of this mailbox sorts before mailbox + "0" ("/" < "0"), and
        // every key of the next mailbox after it.
        const next = mailbox + "0";
        if (!MAILBOX.test(mailbox)) { state.after = next; continue; }
        if (!room(2)) { stopped = true; break; }
        const object = await call(this.bucket.get(`${mailbox}/touched`));
        const pool = (owner) => (owner ? "mailboxes" : "unclaimed");
        if (!object) {
          // No marker yet (or lost): one now, so it is deleted a year from now at the latest.
          const owner = mailbox.startsWith("c");
          await call(this.bucket.put(`${mailbox}/touched`, JSON.stringify({ time: Date.now(), bytes: 0, owner })));
          state = { ...state, after: next, [pool(owner)]: state[pool(owner)] + 1 };
          continue;
        }
        const touched = parseTouched(await object.text(), mailbox);
        if (!(Number.isFinite(touched.time) && touched.time > 0) || touched.time >= (touched.owner ? limit : unclaimedLimit)) {
          state = { ...state, after: next, [pool(touched.owner)]: state[pool(touched.owner)] + 1, bytes: state.bytes + touched.bytes };
          continue;
        }
        // Expired: delete its files 1,000 at a time. The marker sorts last, so a
        // run that stops half way leaves it, and the next run finishes the job.
        let done = false;
        while (room(2)) {
          const files = await call(this.bucket.list({ prefix, limit: 1000 }));
          if (files.objects.length) await call(this.bucket.delete(files.objects.map((item) => item.key)));
          if (!files.truncated) { done = true; break; }
        }
        if (!done) { stopped = true; break; }
        state.after = next;
      }
    }
    await call(this.bucket.put(SWEEP_STATE, JSON.stringify(state)));
    return calls;
  }
}

/// Bytes stored and mailboxes created per client and UTC day, in one small R2
/// object per client: "_relay/clients/<day>/<hash>". The hash is of the client
/// key and a random salt kept for that day only, so the bucket holds no address;
/// the clean-up deletes past days. Concurrent writes may both pass the last
/// check: the limits are approximate.
const salts = new WeakMap(); // bucket -> {day, salt}, per Worker instance

export class R2Clients {
  constructor(bucket, key, options) {
    this.bucket = bucket;
    this.client = key;
    this.options = options;
    this.counter = null;
  }

  async salt(day) {
    const cached = salts.get(this.bucket);
    if (cached?.day === day) return cached.salt;
    const key = `${CLIENTS}${day}/salt`;
    let salt = (await readJSON(await this.bucket.get(key)))?.salt;
    if (!salt) {
      salt = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
      // The first salt of the day wins; others read it back.
      if ((await this.bucket.put(key, JSON.stringify({ salt }), { onlyIf: new Headers({ "If-None-Match": "*" }) })) === null) {
        salt = (await readJSON(await this.bucket.get(key)))?.salt || salt;
      }
    }
    salts.set(this.bucket, { day, salt });
    return salt;
  }

  async load() {
    if (this.counter) return this.counter;
    const day = utcDay();
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`${await this.salt(day)}/${this.client}`));
    const key = `${CLIENTS}${day}/${[...new Uint8Array(digest)].slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("")}`;
    const value = await readJSON(await this.bucket.get(key));
    this.counter = { key, bytes: Number(value?.bytes) || 0, mailboxes: Number(value?.mailboxes) || 0 };
    return this.counter;
  }

  async mayUpload(bytes) { return (await this.load()).bytes + bytes <= this.options.dailyUploadBytes; }
  async mayCreateMailbox() { return (await this.load()).mailboxes < this.options.newMailboxesPerDay; }
  async created({ bytes, mailbox }) {
    const counter = await this.load();
    counter.bytes += bytes;
    if (mailbox) counter.mailboxes += 1;
    await this.bucket.put(counter.key, JSON.stringify({ bytes: counter.bytes, mailboxes: counter.mailboxes }));
  }
}

export default {
  async fetch(request, env) {
    try {
      const key = clientKey(request.headers.get("cf-connecting-ip") || "");
      // Requests per minute per client: the "ratelimits" binding in wrangler.toml.
      if (env.RATE_LIMITER) {
        const { success } = await env.RATE_LIMITER.limit({ key });
        if (!success) return new Response(null, { status: 429 });
      }
      const options = settings(env);
      return await handle(request, new R2Storage(env.FILES), options, new R2Clients(env.FILES, key, options));
    } catch {
      return new Response(null, { status: 500 });
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(new R2Storage(env.FILES).sweep(Number(env.TTL_DAYS || 365)));
  },
};
