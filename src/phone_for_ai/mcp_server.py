from __future__ import annotations

import base64
import json
from typing import Literal

from mcp.server.fastmcp import FastMCP, Image

from .client import ApiClient, mark_execution
from .config import Settings, read_operator_token


INSTRUCTIONS = """Control one paired Android phone through explicit commands.
Capture a screen before each UI action. Use the returned command id and package_name as
basis_command_id and expected_package. Coordinates use display_width/display_height.
Each screenshot basis is valid once for 180 seconds. A pending/delivered command may have
already caused a side effect; inspect it with phone_command_result instead of resubmitting it.
"""


def _json(result: dict) -> str:
    return json.dumps(result, ensure_ascii=False, indent=2)


def _client() -> ApiClient:
    settings = Settings.from_env()
    return ApiClient(settings.api_base, read_operator_token(settings))


def _with_image(result: dict, client: ApiClient | None = None) -> list:
    mark_execution(result)
    rendered = _json(result)
    command = result.get("command") if isinstance(result, dict) else None
    artifact = command.get("artifact") if isinstance(command, dict) else None
    if result.get("ok") and isinstance(artifact, dict) and artifact.get("available") and command.get("id"):
        api = client or _client()
        try:
            data = api.artifact(command["id"])
        except (OSError, urllib_error()):
            return [rendered]
        return [rendered, Image(data=data, format="jpeg")]
    return [rendered]


def urllib_error():
    # Kept behind a function so importing the MCP stays cheap and tests can use a fake client.
    import urllib.error
    return urllib.error.URLError


def _run(command_type: str, args: dict, wait_seconds: int = 80) -> dict:
    return mark_execution(_client().run(command_type, args, wait_seconds))


def _general(result: dict, encoding: str = "utf-8") -> str:
    mark_execution(result)
    command = result.get("command") if isinstance(result, dict) else None
    data = command.get("result") if isinstance(command, dict) else None
    if isinstance(data, dict):
        if "output_base64" in data:
            try:
                data["output"] = base64.b64decode(data.pop("output_base64"), validate=True).decode("utf-8", errors="replace")
            except (ValueError, TypeError):
                pass
        if "data_base64" in data and encoding == "utf-8":
            try:
                raw = base64.b64decode(data["data_base64"], validate=True)
                data["text"] = raw.decode("utf-8")
                del data["data_base64"]
            except (ValueError, UnicodeDecodeError, TypeError):
                data["encoding"] = "base64"
    return _json(result)


def build_mcp() -> FastMCP:
    mcp = FastMCP("phone-for-ai", instructions=INSTRUCTIONS)

    @mcp.tool()
    def phone_status() -> str:
        """Show pairing, online state, phone status, and the active/latest command without secrets."""
        return _json(_client().request("/status"))

    @mcp.tool()
    def phone_command_result(command_id: str) -> list:
        """Read one command receipt. Use this after an uncertain timeout; do not resubmit the action."""
        result = mark_execution(_client().wait(command_id, 0))
        command = result.get("command") or {}
        if command.get("type") in {"root.exec", "files.list", "files.read", "files.write", "apps.list", "ui.dump", "clipboard.set"}:
            return [_general(result, "base64")]
        return _with_image(result)

    @mcp.tool()
    def phone_command_cancel(command_id: str) -> str:
        """Cancel pending work or abandon a delivered command. Delivery may mean its side effect happened."""
        return _json(mark_execution(_client().request(f"/commands/{command_id}/cancel", method="POST", payload={})))

    @mcp.tool()
    def phone_capture_screen(max_width: int = 1080, quality: int = 72) -> list:
        """Capture the current screen and return JPEG plus display/package metadata."""
        return _with_image(_run("screen.capture", {"max_width": max_width, "quality": quality}))

    @mcp.tool()
    def phone_wake(max_width: int = 720, quality: int = 60) -> list:
        """Wake the phone and capture a screen. A secure keyguard still needs the person."""
        return _with_image(_run("screen.wake", {"max_width": max_width, "quality": quality}))

    @mcp.tool()
    def phone_ui_tree(max_nodes: int = 300, max_depth: int = 12) -> str:
        """Read the current accessibility node tree without consuming a screenshot basis."""
        return _json(_run("ui.dump", {"max_nodes": max_nodes, "max_depth": max_depth}))

    @mcp.tool()
    def phone_tap(basis_command_id: str, expected_package: str, x: int, y: int, max_width: int = 720, quality: int = 60) -> list:
        """Tap display coordinates using a fresh, unused screenshot basis."""
        return _with_image(_run("ui.tap", locals()))

    @mcp.tool()
    def phone_long_press(basis_command_id: str, expected_package: str, x: int, y: int, duration_ms: int = 650, max_width: int = 720, quality: int = 60) -> list:
        """Long-press display coordinates using a fresh screenshot basis."""
        return _with_image(_run("ui.long_press", locals()))

    @mcp.tool()
    def phone_swipe(basis_command_id: str, expected_package: str, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 450, max_width: int = 720, quality: int = 60) -> list:
        """Swipe between display coordinates using a fresh screenshot basis."""
        return _with_image(_run("ui.swipe", locals()))

    @mcp.tool()
    def phone_type(basis_command_id: str, expected_package: str, text: str, max_width: int = 720, quality: int = 60) -> list:
        """Replace the focused editable field exactly; an empty string clears it."""
        return _with_image(_run("ui.type", locals()))

    @mcp.tool()
    def phone_global_action(basis_command_id: str, expected_package: str, action: Literal["back", "home", "recents"], max_width: int = 720, quality: int = 60) -> list:
        """Run Back, Home, or Recents using a fresh screenshot basis."""
        args = {k: v for k, v in locals().items() if k != "action"}
        return _with_image(_run(f"ui.global.{action}", args))

    @mcp.tool()
    def phone_launch_app(package: str, max_width: int = 720, quality: int = 60) -> list:
        """Launch an Android package and return a screenshot."""
        return _with_image(_run("app.launch", locals()))

    @mcp.tool()
    def phone_clipboard_set(text: str) -> str:
        """Set the phone clipboard exactly; an empty string clears it."""
        return _json(_run("clipboard.set", {"text": text}))

    @mcp.tool()
    def phone_shell(command: str, root: bool = True, timeout_seconds: int = 15, max_bytes: int = 32768) -> str:
        """Execute a bounded phone shell command. Timeouts do not undo side effects."""
        return _general(_run("root.exec", locals()))

    @mcp.tool()
    def phone_list_files(path: str = "/sdcard", max_bytes: int = 32768) -> str:
        """List an absolute phone path with bounded output."""
        return _general(_run("files.list", locals()))

    @mcp.tool()
    def phone_read_file(path: str, offset: int = 0, max_bytes: int = 32768, encoding: Literal["utf-8", "base64"] = "utf-8") -> str:
        """Read a bounded byte range; use next_offset/eof for another chunk."""
        args = {k: v for k, v in locals().items() if k != "encoding"}
        return _general(_run("files.read", args), encoding)

    @mcp.tool()
    def phone_write_file(path: str, content: str, encoding: Literal["utf-8", "base64"] = "utf-8", append: bool = False) -> str:
        """Write at most 49152 decoded bytes; content may be UTF-8 text or Base64."""
        if encoding not in {"utf-8", "base64"}:
            return _json({"ok": False, "error": "invalid_encoding"})
        data = base64.b64encode(content.encode()).decode() if encoding == "utf-8" else content
        return _general(_run("files.write", {"path": path, "data_base64": data, "append": append}))

    @mcp.tool()
    def phone_list_apps(query: str = "", user_only: bool = False, max_bytes: int = 32768) -> str:
        """List installed packages with bounded output."""
        return _general(_run("apps.list", locals()))

    return mcp


def run() -> None:
    build_mcp().run()
