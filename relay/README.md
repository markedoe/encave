# Encave relay

A small HTTP service that lets Encave devices sync when they are not on the same
network. It stores encrypted files and nothing else: devices encrypt everything
before sending it, and the relay never has a key (see [docs/SYNC.md](../docs/SYNC.md)
and [docs/REMOTE_SYNC.md](../docs/REMOTE_SYNC.md)).

**You do not need a relay** to sync your own devices on the same Wi-Fi. A relay
is for devices that are apart, or for households with both iPhones and Android
phones. Anyone can run one; point the apps at it in **Settings › Devices ›
Sync away from home**.

## What the relay sees

- Random mailbox IDs, which change when a device is removed.
- Encrypted files: their number, sizes and when they are written, and their
  names: random device IDs and change counters, and hashes of photos (see
  [docs/SYNC.md](../docs/SYNC.md) §9).
- The IP addresses of the devices that connect. The Node server keeps no logs;
  on Cloudflare, check your account's logging settings.

It never sees cellar contents, device names, photos or keys.

## API

| Request | Result |
|---|---|
| `GET /v1/health` | `{"service":"encave-relay","version":1}` |
| `GET /v1/m/{mailbox}?prefix=…&after=…` | `{"files":[…],"next":"…"}` (pages of 500) |
| `GET /v1/m/{mailbox}/{path}` | the file, or 404 |
| `PUT /v1/m/{mailbox}/{path}` | 201, or 412 if the file exists (files are never replaced) |
| `DELETE /v1/m/{mailbox}/{path}` | 204 |

- Mailbox IDs are `c` (a cellar) or `i` (a device's inbox) followed by 32
  lowercase hex characters.
- Paths use lowercase letters, digits, `-`, `_` and `/`.
- Requests carry `Authorization: Bearer <token>`. A mailbox ID is its kind
  followed by the first 32 hex characters of the SHA-256 of its token, so the
  relay checks tokens without storing any, and nobody can claim a mailbox
  before its owner. Requests with a wrong token create nothing.
- In an inbox, anyone may create `grant/…` files of up to 4 KB (that is how a
  device receives a key); only the owner may list, read or delete.

**Limits:**
- 8 MB per file, 4 KB per grant;
- 400 MB and 3,000 files per mailbox, 1 MB and 200 files per inbox;
- 300 requests per minute per client on the Node server.

Mailboxes not written to for a year are deleted, so a relay is a mailbox, not a
backup. Keep backups with **Settings › Back Up**.

## Run it on your own server (Node)

Node 18 or later, no dependencies:

```sh
PORT=8787 DATA_DIR=/var/lib/encave-relay node relay/server.mjs
```

Put it behind HTTPS, for example with Caddy (and set `TRUST_PROXY=1`):

```
relay.example.org {
    reverse_proxy localhost:8787
}
```

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8787 | Port to listen on |
| `DATA_DIR` | `./data` | Where files are kept |
| `TTL_DAYS` | 365 | Mailboxes not written to for this long are deleted |
| `RATE_PER_MINUTE` | 300 | Requests per minute per client |
| `TRUST_PROXY` | unset | Set to `1` behind a reverse proxy, so limits apply per client (from `X-Forwarded-For`) |

### With Docker

```sh
docker build -t encave-relay relay
docker run -d --restart unless-stopped -p 8787:8787 -v encave-relay:/data encave-relay
```

## Run it on Cloudflare (free tier)

1. Install Wrangler and sign in: `npx wrangler login`.
2. Create the bucket: `npx wrangler r2 bucket create encave-relay`.
3. Deploy: `npx wrangler deploy --config relay/cloudflare/wrangler.toml`.
4. In the Cloudflare dashboard, add a lifecycle rule to the bucket that deletes
   objects after 365 days.
5. Optionally enable the rate limiter in `wrangler.toml`.

The free tier covers a household many times over: as of October 2026 it includes
100,000 requests a day and 10 GB of R2 storage. See the cost analysis in
[docs/REMOTE_SYNC.md](../docs/REMOTE_SYNC.md).

## Tests

```sh
node --test relay/test/relay.test.mjs
```

The CI also syncs two simulated devices through a running relay
(`.github/workflows/relay.yml`).
