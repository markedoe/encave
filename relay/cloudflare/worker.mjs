// Encave relay on Cloudflare Workers, with files in an R2 bucket. Deploy with
// Wrangler (see relay/README.md). Same API as the Node server: relay/protocol.mjs.
//
// Mailboxes not written to for TTL_DAYS (default 365) are deleted by a scheduled
// run (the cron trigger in wrangler.toml), like the Node server does. Each run
// stays under the Free plan's 50 subrequests per invocation (R2 calls count) and
// carries on where the previous one stopped. A full pass also counts the relay's
// mailboxes and bytes, which the relay-wide limits are checked against.
// Do not use an R2 lifecycle rule: it deletes each file a year after it was
// written, including photos that are written once and still in use.

import { MAILBOX, clientKey, handle, settings } from "../protocol.mjs";

// Outside the mailboxes (whose IDs start with c or i): the clean-up's position and totals.
const SWEEP_STATE = "_relay/sweep";
const STATS = "_relay/stats";
const SUBREQUESTS_PER_RUN = 45;

/// "touched" holds the time of the last write and the mailbox's size then.
function parseTouched(text) {
  try {
    const value = JSON.parse(text);
    if (typeof value === "number") return { time: value, bytes: 0 };
    return { time: Number(value.time), bytes: Number(value.bytes) || 0 };
  } catch {
    return { time: NaN, bytes: 0 };
  }
}

// Relay totals per bucket, read at most every 5 minutes per Worker instance.
const cachedStats = new WeakMap();

export class R2Storage {
  constructor(bucket) {
    this.bucket = bucket;
    this.usages = new Map();
  }
  key(mailbox, path) { return `${mailbox}/f/${path}`; }

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

  async create(mailbox, path, bytes) {
    const key = this.key(mailbox, path);
    // Each path has one writer, so checking first is enough; the conditional
    // header also makes R2 refuse to replace an existing object.
    if (await this.bucket.head(key)) return false;
    const stored = await this.bucket.put(key, bytes, { onlyIf: new Headers({ "If-None-Match": "*" }) });
    if (stored === null) return false;
    // When the mailbox was last written to and its size, for the clean-up and the totals.
    const size = (this.usages.get(mailbox)?.bytes ?? 0) + bytes.length;
    await this.bucket.put(`${mailbox}/touched`, JSON.stringify({ time: Date.now(), bytes: size }));
    return true;
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
    return { files, bytes };
  }

  /// Totals as of the last full clean-up pass; none before the first one.
  async totals() {
    let cached = cachedStats.get(this.bucket);
    if (!cached || Date.now() - cached.at > 5 * 60_000) {
      const object = await this.bucket.get(STATS);
      let value = null;
      try { value = object ? JSON.parse(await object.text()) : null; } catch {}
      cached = { at: Date.now(), value };
      cachedStats.set(this.bucket, cached);
    }
    const value = cached.value;
    return value ? { bytes: Number(value.bytes) || 0, mailboxes: Number(value.mailboxes) || 0 } : null;
  }

  /// Deletes mailboxes not written to for `ttlDays`, at most `budget` R2 calls per
  /// run; the next run carries on from where this one stopped.
  async sweep(ttlDays, budget = SUBREQUESTS_PER_RUN) {
    const limit = Date.now() - ttlDays * 86400_000;
    let calls = 0;
    const call = (promise) => { calls += 1; return promise; };
    let state = { after: "", bytes: 0, mailboxes: 0 };
    try {
      const saved = await call(this.bucket.get(SWEEP_STATE));
      if (saved) state = { ...state, ...JSON.parse(await saved.text()) };
    } catch {}
    // Leaves room for saving the state and the totals.
    const room = (needed) => calls + needed <= budget - 2;
    let stopped = false;
    while (!stopped && room(1)) {
      const listed = await call(this.bucket.list({ delimiter: "/", startAfter: state.after || undefined, limit: 100 }));
      const prefixes = listed.delimitedPrefixes || [];
      if (!prefixes.length) {
        // A full pass: publish the totals and start again next time.
        await call(this.bucket.put(STATS, JSON.stringify({ bytes: state.bytes, mailboxes: state.mailboxes, time: Date.now() })));
        cachedStats.delete(this.bucket);
        state = { after: "", bytes: 0, mailboxes: 0 };
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
        if (!object) {
          // No marker yet (or lost): one now, so it is deleted a year from now at the latest.
          await call(this.bucket.put(`${mailbox}/touched`, JSON.stringify({ time: Date.now(), bytes: 0 })));
          state = { ...state, after: next, mailboxes: state.mailboxes + 1 };
          continue;
        }
        const touched = parseTouched(await object.text());
        if (!(Number.isFinite(touched.time) && touched.time > 0) || touched.time >= limit) {
          state = { ...state, after: next, mailboxes: state.mailboxes + 1, bytes: state.bytes + touched.bytes };
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

export default {
  async fetch(request, env) {
    try {
      // Requests per minute per client: the "ratelimits" binding in wrangler.toml.
      if (env.RATE_LIMITER) {
        const key = clientKey(request.headers.get("cf-connecting-ip") || "");
        const { success } = await env.RATE_LIMITER.limit({ key });
        if (!success) return new Response(null, { status: 429 });
      }
      return await handle(request, new R2Storage(env.FILES), settings(env));
    } catch {
      return new Response(null, { status: 500 });
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(new R2Storage(env.FILES).sweep(Number(env.TTL_DAYS || 365)));
  },
};
