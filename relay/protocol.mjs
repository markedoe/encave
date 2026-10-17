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

export const LIMITS = {
  maxFileBytes: 8 * 1024 * 1024,
  maxMailboxBytes: 400 * 1024 * 1024,
  maxMailboxFiles: 3000,
  maxInboxFiles: 200,
  maxGrantBytes: 4 * 1024,
  maxInboxBytes: 1024 * 1024,
  listPage: 500,
};

const MAILBOX = /^[ci][0-9a-f]{32}$/;
const PART = /^[a-z0-9_-]+$/;

export function isValidPath(path) {
  if (typeof path !== "string" || path.length === 0 || path.length > 200) return false;
  return path.split("/").every((part) => part !== "." && part !== ".." && PART.test(part));
}

export function isValidMailbox(id) {
  return MAILBOX.test(id);
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

/**
 * Handles one request. `storage` provides:
 *   list(mailbox, prefix, after, limit) -> {files, next},
 *   get(mailbox, path) -> Uint8Array | null,
 *   create(mailbox, path, bytes) -> boolean (false if it exists),
 *   delete(mailbox, path), usage(mailbox) -> {files, bytes}.
 */
export async function handle(request, storage) {
  const url = new URL(request.url);
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts.length === 2 && parts[0] === "v1" && parts[1] === "health" && request.method === "GET") {
    return json({ service: "encave-relay", version: 1 });
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

  switch (request.method) {
    case "GET": {
      const bytes = await storage.get(mailbox, path);
      return bytes ? new Response(bytes, { headers: { "content-type": "application/octet-stream" } }) : empty(404);
    }
    case "PUT": {
      const maxBytes = isInbox ? LIMITS.maxGrantBytes : LIMITS.maxFileBytes;
      const declared = Number(request.headers.get("content-length") || "0");
      if (declared > maxBytes) return empty(413);
      const bytes = new Uint8Array(await request.arrayBuffer());
      if (bytes.length > maxBytes) return empty(413);
      const usage = await storage.usage(mailbox);
      const maxFiles = isInbox ? LIMITS.maxInboxFiles : LIMITS.maxMailboxFiles;
      const maxTotal = isInbox ? LIMITS.maxInboxBytes : LIMITS.maxMailboxBytes;
      if (usage.files >= maxFiles || usage.bytes + bytes.length > maxTotal) return empty(507);
      return (await storage.create(mailbox, path, bytes)) ? empty(201) : empty(412);
    }
    case "DELETE":
      await storage.delete(mailbox, path);
      return empty(204);
    default:
      return empty(405);
  }
}
