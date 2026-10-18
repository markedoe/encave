// Encave relay: stores encrypted files for devices that are apart, and nothing
// else. It cannot read them: every file is encrypted on the devices (see
// docs/SYNC.md). The same request handler runs on Node (server.mjs) and on
// Cloudflare Workers (cloudflare/worker.mjs); only the storage differs.
//
//   GET    /v1/health                          {"service":"encave-relay","version":1}
//   GET    /v1/m/{mailbox}?prefix=&after=      {"files":[...],"next":"..."}
//   GET    /v1/m/{mailbox}/{path}              the bytes, or 404
//   PUT    /v1/m/{mailbox}/{path}              create only: 201, or 412 if it exists
//   DELETE /v1/m/{mailbox}/{path}              204
//
// Access: "Authorization: Bearer <token>". A mailbox ID is its kind ("c" for a
// cellar, "i" for a device's inbox) followed by the first 32 hex characters of
// the SHA-256 of its token, so the relay checks a token without storing anything,
// and nobody can claim a mailbox before its owner. In an inbox, anyone may create
// small grant/… files without a token; reading, listing and deleting need the
// owner's token.
//
// A private relay can also require an access key (RELAY_ACCESS_KEY) in the
// "X-Relay-Key" header of every request, health included; without it: 403.

export const LIMITS = {
  maxFileBytes: 8 * 1024 * 1024,
  maxMailboxBytes: 400 * 1024 * 1024,
  maxMailboxFiles: 3000,
  maxInboxFiles: 200,
  maxGrantBytes: 4 * 1024,
  maxInboxBytes: 1024 * 1024,
  listPage: 500,
  // Whole relay, unless the operator sets MAX_STORAGE_MB / MAX_MAILBOXES.
  maxStorageBytes: 10 * 1024 * 1024 * 1024,
  maxMailboxes: 1000,
};

export const MAILBOX = /^[ci][0-9a-f]{32}$/;
const PART = /^[a-z0-9_-]+$/;

export function isValidPath(path) {
  if (typeof path !== "string" || path.length === 0 || path.length > 200) return false;
  return path.split("/").every((part) => part !== "." && part !== ".." && PART.test(part));
}

export function isValidMailbox(id) {
  return MAILBOX.test(id);
}

/// Relay-wide options from environment variables (Node) or Worker vars and secrets.
export function settings(env = {}) {
  const positive = (value, fallback) => {
    const number = Number(value);
    return value !== undefined && value !== "" && Number.isFinite(number) && number > 0 ? number : fallback;
  };
  return {
    accessKey: env.RELAY_ACCESS_KEY || "",
    maxStorageBytes: positive(env.MAX_STORAGE_MB, LIMITS.maxStorageBytes / 1048576) * 1048576,
    maxMailboxes: positive(env.MAX_MAILBOXES, LIMITS.maxMailboxes),
  };
}

/// The key that rate limits count against: the IPv4 address, or the /64 of an
/// IPv6 address (one home or phone usually has a whole /64).
export function clientKey(address) {
  const text = String(address || "").trim().toLowerCase().split("%")[0];
  const mapped = /^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/.exec(text);
  if (mapped) return mapped[1];
  if (!text.includes(":")) return text;
  const [head, tail] = text.split("::");
  const front = head ? head.split(":") : [];
  const back = tail === undefined ? [] : tail ? tail.split(":") : [];
  const groups = tail === undefined ? front : [...front, ...Array(Math.max(0, 8 - front.length - back.length)).fill("0"), ...back];
  return groups.slice(0, 4).map((group) => (parseInt(group, 16) || 0).toString(16)).join(":") + "::/64";
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function empty(status) {
  return new Response(null, { status });
}

function bearer(request) {
  const header = request.headers.get("authorization") || "";
  const match = /^Bearer (.{16,200})$/.exec(header);
  return match ? match[1] : null;
}

/// The mailbox ID that a token opens: kind + first 32 hex characters of SHA-256(token).
export async function mailboxFor(kind, token) {
  return kind + (await sha256(token)).slice(0, 32);
}

async function authorized(mailbox, token) {
  if (!token) return false;
  return timingSafeEqual(await mailboxFor(mailbox[0], token), mailbox);
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

async function hasAccessKey(request, accessKey) {
  const given = request.headers.get("x-relay-key");
  if (!given) return false;
  // Hashed first so the comparison takes the same time whatever the lengths.
  return timingSafeEqual(await sha256(given), await sha256(accessKey));
}

/// Reads a request body of at most `maxBytes`, or returns null as soon as it is
/// larger, without reading the rest.
async function readBody(request, maxBytes) {
  if (!request.body) return new Uint8Array(0);
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > maxBytes) {
      reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return bytes;
}

/**
 * Handles one request. `storage` provides:
 *   list(mailbox, prefix, after, limit) -> {files, next},
 *   get(mailbox, path) -> Uint8Array | null,
 *   create(mailbox, path, bytes) -> boolean (false if it exists; throws an
 *     error with code "conflict" if the path cannot exist, e.g. a parent is a file),
 *   delete(mailbox, path), usage(mailbox) -> {files, bytes},
 * and optionally totals() -> {bytes, mailboxes, full?} for the whole relay and
 * exclusive(mailbox, fn) to run fn alone for that mailbox.
 * `options` comes from settings().
 *
 * Everything that can refuse a request (access key, token, declared size) is
 * checked before the body is read.
 */
export async function handle(request, storage, options = settings()) {
  if (options.accessKey && !(await hasAccessKey(request, options.accessKey))) return empty(403);
  const url = new URL(request.url);
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts.length === 2 && parts[0] === "v1" && parts[1] === "health") {
    if (request.method === "HEAD") return new Response(null, { headers: { "content-type": "application/json" } });
    if (request.method === "GET") return json({ service: "encave-relay", version: 1 });
    return empty(405);
  }
  if (parts.length < 3 || parts[0] !== "v1" || parts[1] !== "m") return empty(404);
  const mailbox = parts[2];
  if (!isValidMailbox(mailbox)) return empty(404);
  const path = parts.slice(3).join("/");
  const isInbox = mailbox.startsWith("i");
  const token = bearer(request);

  // Dropping a key grant in someone's inbox needs no token.
  const openDrop = isInbox && request.method === "PUT" && path.startsWith("grant/");
  if (!openDrop && !(await authorized(mailbox, token))) return empty(401);

  if (path === "") {
    if (request.method !== "GET") return empty(405);
    const prefix = url.searchParams.get("prefix") || "";
    const after = url.searchParams.get("after") || "";
    if ((prefix && !/^[a-z0-9_/-]*$/.test(prefix)) || (after && !isValidPath(after))) return empty(400);
    return json(await storage.list(mailbox, prefix, after, LIMITS.listPage));
  }
  if (!isValidPath(path)) return empty(400);
  const alone = (fn) => (storage.exclusive ? storage.exclusive(mailbox, fn) : fn());

  switch (request.method) {
    case "GET": {
      const bytes = await storage.get(mailbox, path);
      return bytes ? new Response(bytes, { headers: { "content-type": "application/octet-stream" } }) : empty(404);
    }
    case "PUT": {
      const maxBytes = isInbox ? LIMITS.maxGrantBytes : LIMITS.maxFileBytes;
      const declared = Number(request.headers.get("content-length") || "0");
      if (!(declared <= maxBytes)) return empty(413);
      const bytes = await readBody(request, maxBytes);
      if (!bytes) return empty(413);
      return alone(async () => {
        const usage = await storage.usage(mailbox);
        const maxFiles = isInbox ? LIMITS.maxInboxFiles : LIMITS.maxMailboxFiles;
        const maxTotal = isInbox ? LIMITS.maxInboxBytes : LIMITS.maxMailboxBytes;
        if (usage.files >= maxFiles || usage.bytes + bytes.length > maxTotal) return empty(507);
        const totals = storage.totals ? await storage.totals() : null;
        if (totals) {
          if (totals.full || totals.bytes + bytes.length > options.maxStorageBytes) return empty(507);
          if (usage.files === 0 && totals.mailboxes >= options.maxMailboxes) return empty(507);
        }
        try {
          return (await storage.create(mailbox, path, bytes)) ? empty(201) : empty(412);
        } catch (error) {
          if (error?.code === "conflict") return empty(409);
          throw error;
        }
      });
    }
    case "DELETE":
      await alone(() => storage.delete(mailbox, path));
      return empty(204);
    default:
      return empty(405);
  }
}
