#!/usr/bin/env python3
"""Copy one new image to a paired phone and ask Android to scan it.

Uses the installed phone-for-ai package and its usual environment variables.
Does not publish a post or select an image in an app.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import shlex
import sys
import uuid

from phone_for_ai.client import ApiClient
from phone_for_ai.config import Settings, read_operator_token


def completed(client: ApiClient, kind: str, args: dict) -> dict:
    result = client.run(kind, args, 80)
    command = result.get("command") or {}
    data = command.get("result") or {}
    if not result.get("ok") or command.get("status") != "completed" or command.get("ok") is not True:
        # The id is a receipt to inspect, never an invitation to submit again.
        raise RuntimeError(json.dumps({"error": "command_not_confirmed", "command_id": command.get("id"),
            "status": command.get("status"), "detail": result.get("error") or command.get("error")}, ensure_ascii=False))
    if data.get("timed_out") or data.get("exit_code", 0) != 0:
        raise RuntimeError(json.dumps({"error": "phone_execution_failed", "command_id": command.get("id"),
            "exit_code": data.get("exit_code"), "timed_out": data.get("timed_out")}, ensure_ascii=False))
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--remote", help="New /sdcard/Pictures/... path; existing files are never replaced")
    args = parser.parse_args()
    source = args.image.expanduser().resolve()
    if not source.is_file() or source.suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp"}:
        parser.error("choose a PNG, JPEG or WebP file")
    if not 0 < source.stat().st_size <= 25 * 1024 * 1024:
        parser.error("image must be nonempty and at most 25 MiB")
    nonce = uuid.uuid4().hex
    target = PurePosixPath(args.remote or f"/sdcard/Pictures/PhoneForAI/{nonce}{source.suffix.lower()}")
    if not str(target).startswith("/sdcard/Pictures/") or ".." in target.parts or "\n" in str(target):
        parser.error("remote must be a new path inside /sdcard/Pictures/")
    staging = target.parent / f".phone-for-ai-{nonce}.part"
    settings = Settings.from_env()
    client = ApiClient(settings.api_base, read_operator_token(settings))
    shell = lambda text: completed(client, "root.exec", {"command": text, "root": True, "timeout_seconds": 15, "max_bytes": 4000})
    q = shlex.quote
    try:
        shell(f"test ! -e {q(str(target))} && mkdir -p {q(str(target.parent))}")
        digest = hashlib.sha256()
        size = 0
        with source.open("rb") as handle:
            while chunk := handle.read(49152):
                completed(client, "files.write", {"path": str(staging),
                    "data_base64": base64.b64encode(chunk).decode("ascii"), "append": size > 0})
                digest.update(chunk); size += len(chunk)
                print(f"Copied {size}/{source.stat().st_size} bytes", file=sys.stderr)
        # Android emulated storage may not support hard links. Perform a no-clobber
        # move and require the unique staging file to disappear before reporting success.
        verify = (
            f"test \"$(sha256sum {q(str(staging))} | cut -d ' ' -f1)\" = {q(digest.hexdigest())}"
            f" && test ! -e {q(str(target))}"
            f" && mv -n {q(str(staging))} {q(str(target))}"
            f" && test ! -e {q(str(staging))}"
            f" && am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d {q('file://' + str(target))}"
        )
        shell(verify)
        print(json.dumps({"ok": True, "path": str(target), "bytes": size, "sha256": digest.hexdigest(),
            "next": "Open the app album picker and confirm the image is visible."}, ensure_ascii=False, indent=2))
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Upload stopped: {error}\nStaging path: {staging}\nInspect the original command before retrying; a timeout does not undo a write.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
