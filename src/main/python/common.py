# common.py
"""Spoločné utility pre REST a SSE zberače."""

import json
import os
import sys
from pathlib import Path

# Fix pre CERTIFICATE_VERIFY_FAILED na Windows
try:
    import truststore
    truststore.inject_into_ssl()
except ImportError:
    print("[WARN] truststore nie je nainštalovaný – SSL môže zlyhať na Windows",
          file=sys.stderr)


BASE_URL = "https://api-production-14512.up.railway.app"


def atomic_write_json(path: Path, data) -> None:
    """Atomický zápis JSON (cez dočasný súbor + os.replace)."""
    tmp = path.with_suffix(path.suffix + ".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(data, f)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def read_json(path: Path, default=None):
    if not path.exists():
        return default
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def append_jsonl(path: Path, obj) -> None:
    """Pripojí jeden JSON objekt na koniec JSONL súboru."""
    with path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")
        f.flush()
        os.fsync(f.fileno())