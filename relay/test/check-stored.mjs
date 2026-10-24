// Checks what a Node relay stored, after the apps synced through it (the CI runs
// it after CaveKit's syncThroughARelay): node relay/test/check-stored.mjs DATA_DIR
//
// Every file is an envelope ("ENCV" 1 …) or a grant of at most 4 KiB (a 32-byte
// public key, then an envelope); the markers hold a time or nothing; and nothing
// holds the test's wine or label, an address, or a token (43 base64url characters).
import { readFile, readdir } from "node:fs/promises";
import { join } from "node:path";
import { LIMITS, MAILBOX } from "../protocol.mjs";

const MAGIC = Buffer.from("ENCV\x01", "latin1");
const FORBIDDEN = ["Through the relay", "relay label", "127.0.0.1", "localhost", "Bearer"];
const TOKEN_LIKE = /[A-Za-z0-9_-]{40,}/;

export async function checkStored(dataDir) {
  const problems = [];
  let files = 0;
  const inspect = (name, bytes) => {
    const text = bytes.toString("latin1");
    for (const word of FORBIDDEN) if (text.includes(word)) problems.push(`${name}: contains "${word}"`);
    if (TOKEN_LIKE.test(text)) problems.push(`${name}: contains a token-like string`);
  };
  async function visit(mailbox, dir, prefix) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      const path = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) { await visit(mailbox, join(dir, entry.name), path); continue; }
      const bytes = await readFile(join(dir, entry.name));
      const name = `${mailbox}/${path}`;
      files += 1;
      inspect(name, bytes);
      const envelope = bytes.subarray(0, MAGIC.length).equals(MAGIC);
      const grant = mailbox.startsWith("i") && path.startsWith("grant/") && bytes.length <= LIMITS.maxGrantBytes
        && bytes.subarray(32, 32 + MAGIC.length).equals(MAGIC);
      if (!envelope && !grant) problems.push(`${name}: neither an envelope nor a grant`);
    }
  }
  for (const entry of await readdir(dataDir, { withFileTypes: true })) {
    if (!entry.isDirectory() || !MAILBOX.test(entry.name)) { problems.push(`${entry.name}: not a mailbox`); continue; }
    for (const item of await readdir(join(dataDir, entry.name), { withFileTypes: true })) {
      const where = join(dataDir, entry.name, item.name);
      if (item.name === "files" && item.isDirectory()) await visit(entry.name, where, "");
      else if (item.name === "touched" && /^\d+$/.test(await readFile(where, "utf8"))) continue;
      else if (item.name === "owner" && (await readFile(where)).length === 0) continue;
      else problems.push(`${entry.name}/${item.name}: unexpected`);
    }
  }
  return { files, problems };
}

if (process.argv[1] === new URL(import.meta.url).pathname) {
  const { files, problems } = await checkStored(process.argv[2] || "./data");
  for (const problem of problems) console.error(problem);
  if (problems.length || files === 0) {
    console.error(files === 0 ? "No files stored." : `${problems.length} problem(s).`);
    process.exit(1);
  }
  console.log(`${files} stored files: envelopes and grants only.`);
}
