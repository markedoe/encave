// Encave relay on Cloudflare Workers, with files in an R2 bucket. Deploy with
// Wrangler (see relay/README.md). Same API as the Node server: relay/protocol.mjs.
//
// Mailboxes not written to for a year should expire: add an R2 lifecycle rule
// (Object lifecycle rules → delete after 365 days) on the bucket.

import { handle } from "../protocol.mjs";

class R2Storage {
  constructor(bucket) { this.bucket = bucket; }
  key(mailbox, path) { return `${mailbox}/f/${path}`; }

  async getTokenHash(mailbox) {
    const object = await this.bucket.get(`${mailbox}/token`);
    return object ? (await object.text()).trim() : null;
  }

  async setTokenHashIfAbsent(mailbox, hash) {
    // Conditional put: stored only if no token exists yet (null otherwise).
    await this.bucket.put(`${mailbox}/token`, hash, { onlyIf: new Headers({ "If-None-Match": "*" }) });
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

  async create(mailbox, path, bytes) {
    const key = this.key(mailbox, path);
    // Each path has one writer, so checking first is enough; the conditional
    // header also makes R2 refuse to replace an existing object.
    if (await this.bucket.head(key)) return false;
    const stored = await this.bucket.put(key, bytes, { onlyIf: new Headers({ "If-None-Match": "*" }) });
    return stored !== null;
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
};
