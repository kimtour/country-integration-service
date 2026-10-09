#!/usr/bin/env python3
"""Derive a tag from exactly the source files used by the Docker build."""
from hashlib import sha256
from pathlib import Path
root = Path(__file__).resolve().parent.parent
paths = [root / name for name in ("Dockerfile", ".dockerignore", "pom.xml", "mvnw")]
paths += [p for directory in (".mvn", "src/main") for p in (root / directory).rglob("*") if p.is_file()]
digest = sha256()
for path in sorted(paths):
    digest.update(str(path.relative_to(root)).encode())
    digest.update(b"\0")
    digest.update(path.read_bytes())
    digest.update(b"\0")
print("build-" + digest.hexdigest()[:16])
