// Encave relay on Cloudflare Workers, with files in an R2 bucket. Deploy with
// Wrangler (see relay/README.md). Same API as the Node server: relay/protocol.mjs.
//
// Mailboxes not written to for TTL_DAYS (default 365) are deleted by a daily
// scheduled run (the cron trigger in wrangler.toml), like the Node server does.
// Do not use an R2 lifecycle rule: it deletes each file a year after it was
// written, including photos that are written once and still in use.

import { handle } from "../protocol.mjs";

class R2Storage {
  constructor(bucket) { this.bucket = bucket; }
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
    // When the mailbox was last written to, for the daily clean-up.
    await this.bucket.put(`${mailbox}/touched`, String(Date.now()));
    return true;
  }

  /// Deletes every mailbox not written to for `ttlDays`.
  async sweep(ttlDays) {
    const limit = Date.now() - ttlDays * 86400_000;
    let cursor;
    do {
      const listed = await this.bucket.list({ delimiter: "/", cursor, limit: 1000 });
      for (const prefix of listed.delimitedPrefixes) {
        const mailbox = prefix.slice(0, -1);
        const touched = await this.bucket.get(`${mailbox}/touched`);
        // Mailboxes from before this marker existed get one now, and a year from now.
        if (!touched) { await this.bucket.put(`${mailbox}/touched`, String(Date.now())); continue; }
        if (Number(await touched.text()) >= limit) continue;
        let inner;
        do {
          const files = await this.bucket.list({ prefix, cursor: inner, limit: 1000 });
          if (files.objects.length) await this.bucket.delete(files.objects.map((object) => object.key));
          inner = files.truncated ? files.cursor : undefined;
        } while (inner);
      }
      cursor = listed.truncated ? listed.cursor : undefined;
    } while (cursor);
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
    return { files, bytes };
  }
}

export default {
  async fetch(request, env) {
    // Optional rate limiting: add a "ratelimits" binding named RATE_LIMITER in wrangler.toml.
    if (env.RATE_LIMITER) {
      const client = request.headers.get("cf-connecting-ip") || "";
      const { success } = await env.RATE_LIMITER.limit({ key: client });
      if (!success) return new Response(null, { status: 429 });
    }
    try {
      return await handle(request, new R2Storage(env.FILES));
    } catch {
      return new Response(null, { status: 500 });
    }
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(new R2Storage(env.FILES).sweep(Number(env.TTL_DAYS || 365)));
  },
};
