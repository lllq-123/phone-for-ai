from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import hmac
import json
import os
import re
import secrets
import threading
import time
from pathlib import Path
from typing import Any

from .config import ensure_private_dir


STATE_VERSION = 1
ONLINE_WINDOW_SECONDS = 45
HEARTBEAT_INTERVAL_SECONDS = 2
MAX_LONG_POLL_SECONDS = 20
SCREEN_BASIS_MAX_AGE_SECONDS = 180
DEFAULT_COMMAND_TTL_SECONDS = 600
MAX_COMMAND_TTL_SECONDS = 900
ARTIFACT_RETENTION_SECONDS = 24 * 60 * 60
MAX_BODY_BYTES = 128 * 1024
MAX_RESULT_BYTES = 96 * 1024
MAX_ARTIFACT_BYTES = 3 * 1024 * 1024
MAX_FILE_CHUNK_BYTES = 49152
MAX_PATH_CHARS = 4096
MAX_COMMAND_HISTORY = 80
MAX_TYPE_TEXT_CHARS = 2000
MAX_CLIPBOARD_TEXT_CHARS = 4000

COMMAND_ID_RE = re.compile(r"^[a-f0-9]{24}$")
PACKAGE_RE = re.compile(r"^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$")
ERROR_RE = re.compile(r"^[a-z0-9_]{1,96}$")

COMMAND_TYPES = {
    "screen.capture", "screen.wake", "app.launch", "ui.dump", "ui.tap",
    "ui.long_press", "ui.swipe", "ui.type", "ui.global.back",
    "ui.global.home", "ui.global.recents", "clipboard.set", "root.exec",
    "apps.list", "files.list", "files.read", "files.write",
}
UI_COMMANDS = {
    "ui.tap", "ui.long_press", "ui.swipe", "ui.type", "ui.global.back",
    "ui.global.home", "ui.global.recents",
}
SCREENSHOT_COMMANDS = {
    "screen.capture", "screen.wake", "app.launch", *UI_COMMANDS,
}
COMMAND_CAPABILITIES = {
    "screen.capture": "screen_capture", "screen.wake": "wake_screen",
    "app.launch": "launch", "ui.dump": "ui_tree", "ui.type": "text_input",
    "clipboard.set": "clipboard", "root.exec": "root_shell",
    "apps.list": "app_inventory", "files.list": "file_access",
    "files.read": "file_access", "files.write": "file_access",
}
CAPABILITY_KEYS = {
    "accessibility", "screen_capture", "wake_screen", "gestures", "text_input",
    "launch", "ui_tree", "clipboard", "root_shell", "file_access", "app_inventory", "screen_stream",
}
TERMINAL_STATES = {"completed", "failed", "cancelled", "expired"}


class BridgeError(Exception):
    def __init__(self, status_code: int, code: str):
        super().__init__(code)
        self.status_code = status_code
        self.code = code


def _now() -> float:
    return time.time()


def _iso(value: Any) -> str | None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return None
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(value))


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _matches_hash(value: str, verifier: str) -> bool:
    return bool(value and verifier and hmac.compare_digest(_hash(value), verifier))


def _integer(value: Any, low: int, high: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise BridgeError(400, "invalid_command_args")
    return value


def _boolean(value: Any) -> bool:
    if not isinstance(value, bool):
        raise BridgeError(400, "invalid_command_args")
    return value


def _exact(value: Any, limit: int, *, empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > limit or "\x00" in value or (not empty and not value):
        raise BridgeError(400, "invalid_command_args")
    return value


def _text(value: Any, limit: int) -> str:
    if not isinstance(value, str):
        raise BridgeError(400, "invalid_command_args")
    cleaned = "".join(c for c in value if c in "\n\t" or ord(c) >= 32)
    if len(cleaned) > limit:
        raise BridgeError(400, "text_too_long")
    return cleaned


def _package(value: Any) -> str:
    if not isinstance(value, str) or len(value) > 160 or not PACKAGE_RE.fullmatch(value):
        raise BridgeError(400, "invalid_command_args")
    return value


def _screenshot_options(args: dict[str, Any], *, capture: bool = False) -> dict[str, int]:
    return {
        "max_width": _integer(args.get("max_width", 1080 if capture else 720), 540, 1440),
        "quality": _integer(args.get("quality", 72 if capture else 60), 50, 90),
    }


def sanitize_command(command_type: Any, value: Any) -> tuple[str, dict[str, Any]]:
    if not isinstance(command_type, str) or command_type not in COMMAND_TYPES:
        raise BridgeError(400, "unsupported_command")
    if not isinstance(value, dict):
        raise BridgeError(400, "invalid_command_args")
    args: dict[str, Any] = value
    if command_type == "screen.capture":
        return command_type, _screenshot_options(args, capture=True)
    if command_type == "screen.wake":
        return command_type, _screenshot_options(args)
    if command_type == "app.launch":
        return command_type, {"package": _package(args.get("package")), **_screenshot_options(args)}
    if command_type == "ui.dump":
        return command_type, {
            "max_nodes": _integer(args.get("max_nodes", 300), 20, 400),
            "max_depth": _integer(args.get("max_depth", 12), 1, 20),
        }
    if command_type == "clipboard.set":
        return command_type, {"text": _text(args.get("text"), MAX_CLIPBOARD_TEXT_CHARS)}
    if command_type in {"root.exec", "apps.list", "files.list", "files.read", "files.write"}:
        result: dict[str, Any] = {}
        if command_type.startswith("files."):
            path = _exact(args.get("path"), MAX_PATH_CHARS)
            if not path.startswith("/"):
                raise BridgeError(400, "absolute_path_required")
            result["path"] = path
        if command_type != "files.write":
            result["max_bytes"] = _integer(args.get("max_bytes", 32768), 1, MAX_FILE_CHUNK_BYTES)
        if command_type == "root.exec":
            result.update({
                "command": _exact(args.get("command"), 16000),
                "root": _boolean(args.get("root", True)),
                "timeout_seconds": _integer(args.get("timeout_seconds", 15), 1, 30),
            })
        elif command_type == "apps.list":
            result.update({
                "query": _exact(args.get("query", ""), 160, empty=True),
                "user_only": _boolean(args.get("user_only", False)),
            })
        elif command_type == "files.read":
            result["offset"] = _integer(args.get("offset", 0), 0, 2**53 - 1)
        elif command_type == "files.write":
            encoded = args.get("data_base64")
            if not isinstance(encoded, str) or len(encoded) > 4 * ((MAX_FILE_CHUNK_BYTES + 2) // 3):
                raise BridgeError(400, "invalid_file_data")
            try:
                decoded = base64.b64decode(encoded, validate=True)
            except (ValueError, binascii.Error):
                raise BridgeError(400, "invalid_file_data") from None
            if len(decoded) > MAX_FILE_CHUNK_BYTES:
                raise BridgeError(400, "file_chunk_too_large")
            result.update({"data_base64": encoded, "append": _boolean(args.get("append", False))})
        return command_type, result

    basis = args.get("basis_command_id")
    if not isinstance(basis, str) or not COMMAND_ID_RE.fullmatch(basis):
        raise BridgeError(400, "invalid_command_args")
    result = {
        "basis_command_id": basis,
        "expected_package": _package(args.get("expected_package")),
        **_screenshot_options(args),
    }
    if command_type == "ui.tap":
        result.update({"x": _integer(args.get("x"), 0, 8192), "y": _integer(args.get("y"), 0, 8192)})
    elif command_type == "ui.long_press":
        result.update({
            "x": _integer(args.get("x"), 0, 8192), "y": _integer(args.get("y"), 0, 8192),
            "duration_ms": _integer(args.get("duration_ms", 650), 300, 3000),
        })
    elif command_type == "ui.swipe":
        result.update({
            "x1": _integer(args.get("x1"), 0, 8192), "y1": _integer(args.get("y1"), 0, 8192),
            "x2": _integer(args.get("x2"), 0, 8192), "y2": _integer(args.get("y2"), 0, 8192),
            "duration_ms": _integer(args.get("duration_ms", 450), 100, 2000),
        })
    elif command_type == "ui.type":
        result["text"] = _text(args.get("text"), MAX_TYPE_TEXT_CHARS)
    return command_type, result


class BridgeService:
    def __init__(self, state_dir: Path, operator_token: str, *, clock=_now):
        self.state_dir = Path(state_dir)
        self.state_path = self.state_dir / "state.json"
        self.artifact_dir = self.state_dir / "artifacts"
        self.operator_token = operator_token
        self.clock = clock
        self.lock = threading.RLock()
        self._generation = 0
        self._waiters: set[tuple[asyncio.AbstractEventLoop, asyncio.Event]] = set()
        self.stream_active = lambda: False
        ensure_private_dir(self.state_dir)
        ensure_private_dir(self.artifact_dir)
        with self.lock:
            if not self.state_path.exists():
                self._write(self._default_state())
            else:
                self._load()

    @staticmethod
    def _default_state() -> dict[str, Any]:
        return {"version": STATE_VERSION, "enrollment": None, "device": None, "commands": []}

    def _load(self) -> dict[str, Any]:
        raw = json.loads(self.state_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict) or raw.get("version") != STATE_VERSION:
            raise RuntimeError("unsupported phone-for-ai state")
        if not isinstance(raw.get("commands"), list):
            raise RuntimeError("invalid phone-for-ai state")
        return raw

    def _write(self, state: dict[str, Any]) -> None:
        ensure_private_dir(self.state_dir)
        temp = self.state_path.with_name(f".{self.state_path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
        try:
            with temp.open("x", encoding="utf-8") as handle:
                json.dump(state, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            temp.chmod(0o600)
            os.replace(temp, self.state_path)
            self.state_path.chmod(0o600)
        finally:
            temp.unlink(missing_ok=True)

    def operator_authorized(self, token: str) -> bool:
        return bool(token and self.operator_token and hmac.compare_digest(token, self.operator_token))

    def _device_authorized(self, state: dict[str, Any], token: str) -> bool:
        device = state.get("device")
        return isinstance(device, dict) and _matches_hash(token, str(device.get("token_hash") or ""))

    def device_authorized(self, token: str) -> bool:
        with self.lock:
            return self._device_authorized(self._load(), token)

    def authorized_device_id(self, token: str) -> str | None:
        with self.lock:
            state = self._load()
            return state["device"]["id"] if self._device_authorized(state, token) else None

    def device_info(self) -> dict[str, Any] | None:
        with self.lock:
            device = self._load().get("device")
            if not isinstance(device, dict):
                return None
            return {key: device.get(key) for key in ("id", "capabilities", "last_seen")}

    def command_busy(self) -> bool:
        now = self.clock()
        with self.lock:
            state = self._load()
            if self._expire(state, now):
                self._write(state)
            return any(c.get("status") in {"pending", "delivered"} for c in state["commands"])

    def _notify(self) -> None:
        with self.lock:
            self._generation += 1
            waiters = list(self._waiters)
        for loop, event in waiters:
            try:
                loop.call_soon_threadsafe(event.set)
            except RuntimeError:
                pass

    def wake_waiters(self) -> None:
        """Release heartbeat long-polls during shutdown or stream state changes."""
        self._notify()

    async def wait_for_change(self, generation: int, timeout: float) -> None:
        if timeout <= 0:
            return
        loop = asyncio.get_running_loop()
        event = asyncio.Event()
        waiter = (loop, event)
        with self.lock:
            if generation != self._generation:
                return
            self._waiters.add(waiter)
        try:
            try:
                await asyncio.wait_for(event.wait(), timeout)
            except asyncio.TimeoutError:
                pass
        finally:
            with self.lock:
                self._waiters.discard(waiter)

    @property
    def generation(self) -> int:
        with self.lock:
            return self._generation

    def _expire(self, state: dict[str, Any], now: float) -> bool:
        changed = False
        for command in state["commands"]:
            if command.get("status") in {"pending", "delivered"} and float(command.get("expires_at") or 0) < now:
                command.update({
                    "status": "expired", "completed_at": now, "ok": False,
                    "error": "command_expired", "delivery_uncertain": command.get("status") == "delivered",
                })
                changed = True
        return changed

    def _cleanup_artifacts(self, state: dict[str, Any], now: float) -> bool:
        changed = False
        for command in state["commands"]:
            artifact = command.get("artifact")
            if isinstance(artifact, dict) and float(artifact.get("uploaded_at") or 0) < now - ARTIFACT_RETENTION_SECONDS:
                try:
                    (self.artifact_dir / str(artifact.get("file"))).unlink(missing_ok=True)
                except OSError:
                    pass
                command.pop("artifact", None)
                command["artifact_expired"] = True
                changed = True
        return changed

    @staticmethod
    def _artifact_public(artifact: Any) -> dict[str, Any] | None:
        if not isinstance(artifact, dict):
            return None
        return {
            "available": True, "mime_type": "image/jpeg", "bytes": artifact.get("bytes"),
            "sha256": artifact.get("sha256"), "uploaded_at": _iso(artifact.get("uploaded_at")),
        }

    def _public_command(self, command: dict[str, Any], *, for_device: bool = False) -> dict[str, Any]:
        keys = ("id", "type", "args", "status", "ok", "result", "error", "delivery_uncertain", "cancellation_kind", "artifact_expired")
        result = {key: command.get(key) for key in keys if key in command}
        for key in ("created_at", "expires_at", "delivered_at", "completed_at"):
            result[key] = _iso(command.get(key))
        artifact = self._artifact_public(command.get("artifact"))
        if artifact is not None and not for_device:
            result["artifact"] = artifact
        return result

    def create_enrollment(self, ttl_seconds: int) -> dict[str, Any]:
        ttl_seconds = _integer(ttl_seconds, 60, 7 * 24 * 60 * 60)
        now = self.clock()
        code = secrets.token_urlsafe(9)
        with self.lock:
            state = self._load()
            for command in state["commands"]:
                if command.get("status") in {"pending", "delivered"}:
                    was_delivered = command["status"] == "delivered"
                    command.update({
                        "status": "cancelled", "completed_at": now, "ok": False,
                        "error": "device_replaced", "cancellation_kind": "abandoned_delivered" if was_delivered else "cancelled_pending",
                        "delivery_uncertain": was_delivered,
                    })
            state["device"] = None
            state["enrollment"] = {"code_hash": _hash(code), "created_at": now, "expires_at": now + ttl_seconds, "used_at": None}
            self._write(state)
        self._notify()
        return {"enrollment_code": code, "expires_at": _iso(now + ttl_seconds)}

    def enroll(self, code: Any, app_version: Any, capabilities: Any) -> dict[str, Any]:
        if not isinstance(code, str) or len(code) > 128 or not code:
            raise BridgeError(400, "invalid_enrollment_code")
        if not isinstance(app_version, str) or not app_version or len(app_version) > 64:
            raise BridgeError(400, "invalid_app_version")
        clean_capabilities = self._sanitize_capabilities(capabilities)
        now = self.clock()
        device_token = secrets.token_urlsafe(32)
        with self.lock:
            state = self._load()
            enrollment = state.get("enrollment")
            valid = (
                isinstance(enrollment, dict) and enrollment.get("used_at") is None
                and float(enrollment.get("expires_at") or 0) >= now
                and _matches_hash(code, str(enrollment.get("code_hash") or ""))
            )
            if not valid:
                raise BridgeError(400, "invalid_or_expired_enrollment_code")
            device_id = secrets.token_hex(12)
            enrollment["used_at"] = now
            state["device"] = {
                "id": device_id, "token_hash": _hash(device_token), "paired_at": now,
                "last_seen": None, "heartbeat_count": 0, "app_version": app_version,
                "capabilities": clean_capabilities, "status": {},
            }
            self._write(state)
        return {"device_id": device_id, "device_token": device_token}

    @staticmethod
    def _sanitize_capabilities(value: Any) -> dict[str, bool]:
        if not isinstance(value, dict):
            raise BridgeError(400, "invalid_capabilities")
        return {key: bool(value.get(key)) for key in sorted(CAPABILITY_KEYS) if isinstance(value.get(key), bool)}

    @staticmethod
    def _sanitize_phone_status(value: Any) -> dict[str, Any]:
        if value is None:
            return {}
        if not isinstance(value, dict):
            raise BridgeError(400, "invalid_status")
        result: dict[str, Any] = {}
        if "battery_pct" in value:
            result["battery_pct"] = _integer(value["battery_pct"], 0, 100)
        for key in ("plugged", "screen_on", "accessibility"):
            if key in value:
                result[key] = _boolean(value[key])
        package = value.get("foreground_package")
        if package is not None:
            result["foreground_package"] = _package(package)
        return result

    def heartbeat(self, token: str, payload: dict[str, Any]) -> None:
        app_version = payload.get("app_version")
        if not isinstance(app_version, str) or not app_version or len(app_version) > 64:
            raise BridgeError(400, "invalid_app_version")
        capabilities = self._sanitize_capabilities(payload.get("capabilities", {}))
        phone_status = self._sanitize_phone_status(payload.get("status"))
        now = self.clock()
        with self.lock:
            state = self._load()
            if not self._device_authorized(state, token):
                raise BridgeError(401, "unauthorized")
            changed = self._expire(state, now) | self._cleanup_artifacts(state, now)
            device = state["device"]
            device.update({
                "last_seen": now, "heartbeat_count": int(device.get("heartbeat_count") or 0) + 1,
                "app_version": app_version, "capabilities": capabilities, "status": phone_status,
            })
            self._write(state)
        if changed:
            self._notify()

    def take_pending(self, token: str) -> dict[str, Any] | None:
        now = self.clock()
        with self.lock:
            state = self._load()
            if not self._device_authorized(state, token):
                raise BridgeError(401, "unauthorized")
            changed = self._expire(state, now)
            command = next((c for c in state["commands"] if c.get("status") == "pending"), None)
            if command is not None:
                command["status"] = "delivered"
                command["delivered_at"] = now
                changed = True
            if changed:
                self._write(state)
            return self._public_command(command, for_device=True) if command else None

    def queue_command(self, command_type: Any, args: Any, ttl_seconds: Any = DEFAULT_COMMAND_TTL_SECONDS) -> dict[str, Any]:
        command_type, clean_args = sanitize_command(command_type, args)
        ttl = _integer(ttl_seconds, 1, MAX_COMMAND_TTL_SECONDS)
        now = self.clock()
        with self.lock:
            state = self._load()
            changed = self._expire(state, now) | self._cleanup_artifacts(state, now)
            device = state.get("device")
            if not isinstance(device, dict):
                if changed:
                    self._write(state)
                raise BridgeError(409, "device_not_paired")
            if any(c.get("status") in {"pending", "delivered"} for c in state["commands"]):
                if changed:
                    self._write(state)
                raise BridgeError(409, "command_busy")
            if self.stream_active():
                raise BridgeError(409, "phone_stream_in_use")
            capability = COMMAND_CAPABILITIES.get(command_type, "gestures" if command_type in UI_COMMANDS else None)
            if capability and device.get("capabilities", {}).get(capability) is not True:
                raise BridgeError(409, "capability_unavailable")
            basis: dict[str, Any] | None = None
            if command_type in UI_COMMANDS:
                basis_id = clean_args["basis_command_id"]
                screenshots = [
                    c for c in state["commands"] if c.get("type") in SCREENSHOT_COMMANDS
                    and c.get("status") == "completed" and c.get("ok") is True
                    and isinstance(c.get("artifact"), dict)
                ]
                basis = next((c for c in screenshots if c.get("id") == basis_id), None)
                if basis is None or not screenshots or screenshots[-1] is not basis:
                    raise BridgeError(409, "basis_not_latest_screenshot")
                if basis.get("device_id") != device.get("id"):
                    raise BridgeError(409, "basis_wrong_device")
                if basis.get("consumed_by"):
                    raise BridgeError(409, "basis_already_used")
                if now - float(basis.get("completed_at") or 0) > SCREEN_BASIS_MAX_AGE_SECONDS:
                    raise BridgeError(409, "basis_stale")
                package_name = (basis.get("result") or {}).get("package_name")
                if not hmac.compare_digest(str(package_name or ""), clean_args["expected_package"]):
                    raise BridgeError(409, "basis_package_mismatch")
            command_id = secrets.token_hex(12)
            command = {
                "id": command_id, "device_id": device["id"], "type": command_type,
                "args": clean_args, "status": "pending", "created_at": now,
                "expires_at": now + ttl, "delivered_at": None, "completed_at": None,
                "ok": None, "result": None, "error": None,
            }
            if basis is not None:
                basis["consumed_by"] = command_id
            state["commands"].append(command)
            if len(state["commands"]) > MAX_COMMAND_HISTORY:
                state["commands"] = state["commands"][-MAX_COMMAND_HISTORY:]
            self._write(state)
        self._notify()
        return self._public_command(command)

    def upload_artifact(self, token: str, command_id: str, body: bytes) -> tuple[dict[str, Any], bool]:
        if not COMMAND_ID_RE.fullmatch(command_id):
            raise BridgeError(400, "invalid_command_id")
        if not body:
            raise BridgeError(400, "artifact_empty")
        if len(body) > MAX_ARTIFACT_BYTES:
            raise BridgeError(413, "artifact_too_large")
        if not body.startswith(b"\xff\xd8") or not body.endswith(b"\xff\xd9"):
            raise BridgeError(400, "invalid_jpeg")
        digest = hashlib.sha256(body).hexdigest()
        now = self.clock()
        with self.lock:
            state = self._load()
            if not self._device_authorized(state, token):
                raise BridgeError(401, "unauthorized")
            command = next((c for c in state["commands"] if c.get("id") == command_id), None)
            if not isinstance(command, dict) or command.get("device_id") != state["device"].get("id"):
                raise BridgeError(404, "command_not_found")
            existing = command.get("artifact")
            if isinstance(existing, dict):
                if hmac.compare_digest(str(existing.get("sha256") or ""), digest):
                    return self._artifact_public(existing) or {}, True
                raise BridgeError(409, "artifact_conflict")
            if command.get("status") != "delivered":
                raise BridgeError(409, "command_not_active")
            ensure_private_dir(self.artifact_dir)
            filename = f"{command_id}.jpg"
            target = self.artifact_dir / filename
            temp = self.artifact_dir / f".{command_id}.{secrets.token_hex(4)}.tmp"
            try:
                with temp.open("xb") as handle:
                    handle.write(body)
                    handle.flush()
                    os.fsync(handle.fileno())
                temp.chmod(0o600)
                os.replace(temp, target)
                target.chmod(0o600)
            finally:
                temp.unlink(missing_ok=True)
            artifact = {"file": filename, "bytes": len(body), "sha256": digest, "uploaded_at": now}
            command["artifact"] = artifact
            self._write(state)
        return self._artifact_public(artifact) or {}, False

    @staticmethod
    def _sanitize_result(value: Any) -> dict[str, Any]:
        if not isinstance(value, dict):
            raise BridgeError(400, "invalid_command_result")
        try:
            encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        except (TypeError, ValueError):
            raise BridgeError(400, "invalid_command_result") from None
        if len(encoded) > MAX_RESULT_BYTES:
            raise BridgeError(413, "result_too_large")
        return value

    def record_result(self, token: str, command_id: str, payload: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        if not COMMAND_ID_RE.fullmatch(command_id):
            raise BridgeError(400, "invalid_command_id")
        if not isinstance(payload.get("ok"), bool):
            raise BridgeError(400, "invalid_command_result")
        result = self._sanitize_result(payload.get("result"))
        error = payload.get("error")
        if error is not None and (not isinstance(error, str) or not ERROR_RE.fullmatch(error)):
            raise BridgeError(400, "invalid_command_result")
        if not payload["ok"] and not error:
            raise BridgeError(400, "command_error_required")
        receipt = {"ok": payload["ok"], "result": result, "error": error if not payload["ok"] else None}
        receipt_digest = hashlib.sha256(json.dumps(receipt, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        now = self.clock()
        with self.lock:
            state = self._load()
            if not self._device_authorized(state, token):
                raise BridgeError(401, "unauthorized")
            command = next((c for c in state["commands"] if c.get("id") == command_id), None)
            if not isinstance(command, dict) or command.get("device_id") != state["device"].get("id"):
                raise BridgeError(404, "command_not_found")
            if command.get("result_receipt_hash"):
                if hmac.compare_digest(command["result_receipt_hash"], receipt_digest):
                    return self._public_command(command), True
                raise BridgeError(409, "result_conflict")
            if command.get("status") != "delivered":
                raise BridgeError(409, "command_not_active")
            if payload["ok"] and command["type"] in SCREENSHOT_COMMANDS:
                required = ("package_name", "image_width", "image_height", "display_width", "display_height")
                if (
                    not isinstance(result.get("package_name"), str)
                    or not PACKAGE_RE.fullmatch(result["package_name"])
                    or any(isinstance(result.get(k), bool) or not isinstance(result.get(k), int) or not 1 <= result[k] <= 16384 for k in required[1:])
                ):
                    raise BridgeError(400, "incomplete_command_result")
                if not isinstance(command.get("artifact"), dict) and result.get("artifact_upload_failed") is not True:
                    raise BridgeError(409, "artifact_required")
            command.update({
                "status": "completed" if payload["ok"] else "failed", "completed_at": now,
                "ok": payload["ok"], "result": result, "error": None if payload["ok"] else error,
                "result_receipt_hash": receipt_digest,
            })
            self._write(state)
            public = self._public_command(command)
        self._notify()
        return public, False

    def get_command(self, command_id: str) -> dict[str, Any]:
        if not COMMAND_ID_RE.fullmatch(command_id):
            raise BridgeError(400, "invalid_command_id")
        now = self.clock()
        with self.lock:
            state = self._load()
            changed = self._expire(state, now) | self._cleanup_artifacts(state, now)
            command = next((c for c in state["commands"] if c.get("id") == command_id), None)
            if changed:
                self._write(state)
            if not command:
                raise BridgeError(404, "command_not_found")
            return self._public_command(command)

    def cancel_command(self, command_id: str) -> tuple[dict[str, Any], bool]:
        now = self.clock()
        with self.lock:
            state = self._load()
            command = next((c for c in state["commands"] if c.get("id") == command_id), None)
            if not command:
                raise BridgeError(404, "command_not_found")
            if command.get("status") == "cancelled":
                return self._public_command(command), True
            if command.get("status") not in {"pending", "delivered"}:
                raise BridgeError(409, "command_not_cancellable")
            delivered = command["status"] == "delivered"
            command.update({
                "status": "cancelled", "completed_at": now, "ok": False,
                "error": "command_abandoned" if delivered else "command_cancelled",
                "cancellation_kind": "abandoned_delivered" if delivered else "cancelled_pending",
                "delivery_uncertain": delivered,
            })
            self._write(state)
            public = self._public_command(command)
        self._notify()
        return public, False

    def artifact_bytes(self, command_id: str) -> bytes:
        with self.lock:
            state = self._load()
            if self._cleanup_artifacts(state, self.clock()):
                self._write(state)
            command = next((c for c in state["commands"] if c.get("id") == command_id), None)
            artifact = command.get("artifact") if isinstance(command, dict) else None
            if not isinstance(artifact, dict):
                raise BridgeError(404, "artifact_not_found")
            path = self.artifact_dir / str(artifact.get("file"))
            try:
                path.resolve(strict=False).relative_to(self.artifact_dir.resolve(strict=False))
            except ValueError:
                raise BridgeError(404, "artifact_not_found") from None
            try:
                return path.read_bytes()
            except FileNotFoundError:
                raise BridgeError(404, "artifact_not_found") from None

    def status(self) -> dict[str, Any]:
        now = self.clock()
        with self.lock:
            state = self._load()
            changed = self._expire(state, now) | self._cleanup_artifacts(state, now)
            if changed:
                self._write(state)
            device = state.get("device")
            public_device = None
            if isinstance(device, dict):
                last_seen = device.get("last_seen")
                public_device = {
                    "id": device.get("id"), "paired_at": _iso(device.get("paired_at")),
                    "last_seen": _iso(last_seen),
                    "online": isinstance(last_seen, (int, float)) and now - last_seen <= ONLINE_WINDOW_SECONDS,
                    "app_version": device.get("app_version"), "capabilities": device.get("capabilities", {}),
                    "status": device.get("status", {}),
                }
            active = next((c for c in state["commands"] if c.get("status") in {"pending", "delivered"}), None)
            latest = state["commands"][-1] if state["commands"] else None
            return {
                "device": public_device,
                "active_command": self._public_command(active) if active else None,
                "latest_command": self._public_command(latest) if latest else None,
            }
