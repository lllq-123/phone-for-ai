from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from .api import create_app
from .client import ApiClient, command_succeeded
from .config import Settings, initialize, read_operator_token
from .core import COMMAND_TYPES


ALIASES = {
    "phone_capture_screen": "screen.capture", "phone_wake": "screen.wake",
    "phone_ui_tree": "ui.dump", "phone_tap": "ui.tap", "phone_long_press": "ui.long_press",
    "phone_swipe": "ui.swipe", "phone_type": "ui.type", "phone_launch_app": "app.launch",
    "phone_clipboard_set": "clipboard.set", "phone_shell": "root.exec",
    "phone_list_files": "files.list", "phone_read_file": "files.read",
    "phone_write_file": "files.write", "phone_list_apps": "apps.list",
}


def _print(payload: dict[str, Any]) -> None:
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def _client(settings: Settings) -> ApiClient:
    return ApiClient(settings.api_base, read_operator_token(settings))


def _parse_json(value: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as error:
        raise argparse.ArgumentTypeError(f"invalid JSON: {error.msg}") from error
    if not isinstance(parsed, dict):
        raise argparse.ArgumentTypeError("--args must be a JSON object")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="phone-for-ai")
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init", help="create private state and an operator token")
    init.add_argument("--state-dir")
    serve = sub.add_parser("serve", help="serve the bridge with one uvicorn worker")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8765)
    pair = sub.add_parser("pair", help="create and display a one-use phone enrollment code")
    pair.add_argument("--ttl", type=int, default=24 * 60 * 60)
    sub.add_parser("status", help="show phone and command state")
    call = sub.add_parser("call", help="submit a phone_* tool name or wire command")
    call.add_argument("tool")
    call.add_argument("--args", type=_parse_json, default={})
    call.add_argument("--wait", type=int, default=80)
    call.add_argument("--output", type=Path, help="save a returned JPEG artifact")
    sub.add_parser("mcp", help="run the stdio MCP server")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    settings = Settings.from_env(state_dir=getattr(args, "state_dir", None))
    try:
        if args.command == "init":
            created = initialize(settings)
            print(f"State initialized at {settings.state_dir}" if created else f"State already initialized at {settings.state_dir}")
            return 0
        if args.command == "serve":
            import uvicorn
            service_app = create_app()
            uvicorn.run(service_app, host=args.host, port=args.port, workers=1)
            return 0
        if args.command == "mcp":
            from .mcp_server import run
            run()
            return 0
        client = _client(settings)
        if args.command == "pair":
            result = client.request("/enrollment", method="POST", payload={"ttl_seconds": args.ttl})
        elif args.command == "status":
            result = client.request("/status")
        else:
            tool = args.tool
            call_args = dict(args.args)
            if tool == "phone_status":
                result = client.request("/status")
                _print(result)
                return 0 if command_succeeded(result) else 1
            if tool in {"phone_command_result", "phone_command_cancel"}:
                command_id = call_args.get("command_id")
                if not isinstance(command_id, str):
                    _print({"ok": False, "error": "command_id_required"})
                    return 2
                result = (client.wait(command_id, 0) if tool == "phone_command_result"
                          else client.request(f"/commands/{command_id}/cancel", method="POST", payload={}))
                command = result.get("command") if isinstance(result, dict) else None
                if args.output and isinstance(command, dict) and isinstance(command.get("artifact"), dict):
                    args.output.write_bytes(client.artifact(command["id"]))
                    os.chmod(args.output, 0o600)
                    result["artifact_saved_to"] = str(args.output)
                _print(result)
                if tool == "phone_command_cancel":
                    return 0 if result.get("ok") else 1
                return 0 if command_succeeded(result) else 1
            if tool == "phone_global_action":
                action = call_args.pop("action", None)
                command_type = f"ui.global.{action}"
            else:
                command_type = ALIASES.get(tool, tool)
            if command_type not in COMMAND_TYPES:
                _print({"ok": False, "error": "unsupported_command", "tool": tool})
                return 2
            result = client.run(command_type, call_args, args.wait)
            command = result.get("command") if isinstance(result, dict) else None
            if args.output and isinstance(command, dict) and isinstance(command.get("artifact"), dict):
                args.output.write_bytes(client.artifact(command["id"]))
                os.chmod(args.output, 0o600)
                result["artifact_saved_to"] = str(args.output)
        _print(result)
        return 0 if command_succeeded(result) else 1
    except (OSError, RuntimeError, ValueError) as error:
        print(f"phone-for-ai: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
