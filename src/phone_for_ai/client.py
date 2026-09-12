from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


def command_succeeded(response: Any) -> bool:
    """Interpret a command receipt, including process-level shell failure."""
    if not isinstance(response, dict) or response.get("ok") is not True:
        return False
    command = response.get("command")
    if not isinstance(command, dict):
        return True
    if command.get("status") != "completed" or command.get("ok") is not True:
        return False
    if command.get("type") == "root.exec":
        result = command.get("result")
        return (
            isinstance(result, dict)
            and type(result.get("exit_code")) is int
            and result["exit_code"] == 0
            and result.get("timed_out") is False
        )
    return True


def mark_execution(response: dict[str, Any]) -> dict[str, Any]:
    if isinstance(response.get("command"), dict):
        response["execution_ok"] = command_succeeded(response)
    return response


class ApiClient:
    def __init__(self, api_base: str, operator_token: str):
        self.api_base = api_base.rstrip("/")
        self.operator_token = operator_token

    def request(self, path: str, *, method: str = "GET", payload: dict[str, Any] | None = None, timeout: float = 30) -> dict[str, Any]:
        body = None
        headers = {"Authorization": f"Bearer {self.operator_token}", "Accept": "application/json"}
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.api_base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                result = json.loads(response.read())
        except urllib.error.HTTPError as error:
            try:
                result = json.loads(error.read())
            except (UnicodeDecodeError, json.JSONDecodeError):
                result = {"ok": False, "error": f"http_{error.code}"}
        except (OSError, TimeoutError, urllib.error.URLError) as error:
            return {"ok": False, "error": "phone_api_unavailable", "detail": type(error).__name__}
        return result if isinstance(result, dict) else {"ok": False, "error": "invalid_phone_api_response"}

    def artifact(self, command_id: str, *, timeout: float = 30) -> bytes:
        quoted = urllib.parse.quote(command_id, safe="")
        request = urllib.request.Request(
            f"{self.api_base}/commands/{quoted}/artifact",
            headers={"Authorization": f"Bearer {self.operator_token}", "Accept": "image/jpeg"},
        )
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()

    def wait(self, command_id: str, wait_seconds: int = 80) -> dict[str, Any]:
        deadline = time.monotonic() + max(0, min(85, wait_seconds))
        quoted = urllib.parse.quote(command_id, safe="")
        while True:
            result = self.request(f"/commands/{quoted}")
            command = result.get("command") if isinstance(result, dict) else None
            if not result.get("ok") or not isinstance(command, dict):
                return result
            if command.get("status") not in {"pending", "delivered"}:
                return result
            if time.monotonic() >= deadline:
                return {
                    "ok": False, "error": "phone_command_pending",
                    "hint": "The command may already have run. Inspect this command id; do not submit the action again.",
                    "command": command,
                }
            time.sleep(0.5)

    def run(self, command_type: str, args: dict[str, Any], wait_seconds: int = 80) -> dict[str, Any]:
        queued = self.request("/commands", method="POST", payload={"type": command_type, "args": args})
        command = queued.get("command") if isinstance(queued, dict) else None
        if not queued.get("ok") or not isinstance(command, dict) or not command.get("id"):
            return queued
        return self.wait(command["id"], wait_seconds)
