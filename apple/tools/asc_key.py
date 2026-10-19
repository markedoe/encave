#!/usr/bin/env python3
"""Writes the App Store Connect API key from ASC_PRIVATE_KEY to a clean .p8 file.

Rebuilds a clean PEM whatever the paste looks like: lost line breaks, CRLF,
literal "\\n", quotes, missing BEGIN/END lines, or base64 of the file.
Usage: asc_key.py <output path>
"""

import base64
import os
import re
import sys

raw = os.environ["ASC_PRIVATE_KEY"].replace("\r", "").strip().strip('"').strip("'")
if "BEGIN" not in raw:
    try:
        decoded = base64.b64decode(re.sub(r"\s", "", raw), validate=True).decode()
        if "BEGIN" in decoded:
            raw = decoded
    except Exception:
        pass
body = re.sub(r"-----(BEGIN|END)[A-Z ]*-----", "", raw.replace("\\n", "\n"))
body = re.sub(r"\s", "", body)
try:
    base64.b64decode(body, validate=True)
except Exception:
    sys.exit("ASC_PRIVATE_KEY is not a valid .p8 key: paste the full content of AuthKey_XXXX.p8")
lines = [body[i:i + 64] for i in range(0, len(body), 64)]
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    f.write("-----BEGIN PRIVATE KEY-----\n" + "\n".join(lines) + "\n-----END PRIVATE KEY-----\n")
