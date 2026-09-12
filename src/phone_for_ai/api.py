from __future__ import annotations

import json
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import Response
from starlette.routing import Route
from starlette.routing import Mount
from starlette.staticfiles import StaticFiles

from .config import Settings, read_operator_token
from .core import BridgeError, BridgeService, MAX_ARTIFACT_BYTES, MAX_BODY_BYTES, MAX_LONG_POLL_SECONDS


def _json(payload: dict[str, Any], status_code: int = 200) -> Response:
    return Response(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        status_code=status_code,
        media_type="application/json",
        headers={"Cache-Control": "no-store"},
    )


def _bearer(request: Request) -> str:
    value = request.headers.get("authorization", "")
    return value[7:].strip() if value.startswith("Bearer ") else ""


async def _payload(request: Request) -> dict[str, Any]:
    content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
    if content_type != "application/json":
        raise BridgeError(415, "content_type_required")
    body = await _read_limited(request, MAX_BODY_BYTES, "payload_too_large")
    try:
        parsed = json.loads(body or b"{}")
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise BridgeError(400, "invalid_json") from None
    if not isinstance(parsed, dict):
        raise BridgeError(400, "invalid_payload")
    return parsed


async def _read_limited(request: Request, limit: int, too_large_code: str) -> bytes:
    """Read a request incrementally without retaining a body larger than limit."""
    length = request.headers.get("content-length")
    if length:
        try:
            declared = int(length)
        except ValueError:
            raise BridgeError(400, "invalid_content_length") from None
        if declared < 0:
            raise BridgeError(400, "invalid_content_length")
        if declared > limit:
            raise BridgeError(413, too_large_code)
    body = bytearray()
    async for chunk in request.stream():
        if len(chunk) > limit - len(body):
            raise BridgeError(413, too_large_code)
        body.extend(chunk)
    return bytes(body)


def _service(request: Request) -> BridgeService:
    return request.app.state.bridge


def _operator(request: Request) -> BridgeService:
    service = _service(request)
    if not service.operator_authorized(_bearer(request)):
        raise BridgeError(401, "unauthorized")
    return service


async def _handle(call):
    try:
        return await call()
    except BridgeError as error:
        return _json({"ok": False, "error": error.code}, error.status_code)


async def enrollment(request: Request) -> Response:
    async def run():
        service = _operator(request)
        payload = await _payload(request)
        ttl = payload.get("ttl_seconds", 24 * 60 * 60)
        active_stream = request.app.state.stream.session
        if active_stream is not None:
            await request.app.state.stream.finish(active_stream, "pairing_revoked")
        return _json({"ok": True, **service.create_enrollment(ttl)}, 201)
    return await _handle(run)


async def enroll(request: Request) -> Response:
    async def run():
        payload = await _payload(request)
        result = _service(request).enroll(
            payload.get("enrollment_code"), payload.get("app_version"), payload.get("capabilities"),
        )
        return _json({"ok": True, **result}, 201)
    return await _handle(run)


async def heartbeat(request: Request) -> Response:
    async def run():
        token = _bearer(request)
        service = _service(request)
        if not service.device_authorized(token):
            raise BridgeError(401, "unauthorized")
        payload = await _payload(request)
        service.heartbeat(token, payload)
        requested = payload.get("long_poll_seconds", MAX_LONG_POLL_SECONDS)
        if isinstance(requested, bool) or not isinstance(requested, (int, float)):
            raise BridgeError(400, "invalid_long_poll_seconds")
        seconds = max(0.0, min(float(requested), float(MAX_LONG_POLL_SECONDS)))
        generation = service.generation
        command = service.take_pending(token)
        if command is None and seconds:
            # Registering the waiter rechecks generation, so a command queued here cannot be missed.
            await service.wait_for_change(generation, seconds)
            command = service.take_pending(token)
        body: dict[str, Any] = {
            "ok": True, "server_time": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat().replace("+00:00", "Z"),
            "long_poll_seconds": seconds, "heartbeat_interval_seconds": 2,
        }
        if command is not None:
            body["command"] = command
        device_id = service.authorized_device_id(token)
        stream = request.app.state.stream.desired_stream(device_id) if device_id else None
        if stream is not None:
            body["stream"] = stream
        return _json(body)
    return await _handle(run)


async def artifact(request: Request) -> Response:
    async def run():
        service = _service(request)
        command_id = request.path_params["command_id"]
        if request.method == "GET":
            service = _operator(request)
            return Response(
                service.artifact_bytes(command_id), media_type="image/jpeg",
                headers={"Cache-Control": "no-store"},
            )
        token = _bearer(request)
        if not service.device_authorized(token):
            raise BridgeError(401, "unauthorized")
        content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        if content_type != "image/jpeg":
            raise BridgeError(415, "image_jpeg_required")
        body = await _read_limited(request, MAX_ARTIFACT_BYTES, "artifact_too_large")
        public, duplicate = service.upload_artifact(token, command_id, body)
        return _json({"ok": True, "duplicate": duplicate, "artifact": public}, 200 if duplicate else 201)
    return await _handle(run)


async def result(request: Request) -> Response:
    async def run():
        service = _service(request)
        token = _bearer(request)
        if not service.device_authorized(token):
            raise BridgeError(401, "unauthorized")
        payload = await _payload(request)
        command, duplicate = service.record_result(
            token, request.path_params["command_id"], payload,
        )
        return _json({"ok": True, "duplicate": duplicate, "command": command})
    return await _handle(run)


async def status(request: Request) -> Response:
    async def run():
        return _json({"ok": True, **_operator(request).status()})
    return await _handle(run)


async def commands(request: Request) -> Response:
    async def run():
        service = _operator(request)
        payload = await _payload(request)
        command = service.queue_command(payload.get("type"), payload.get("args"), payload.get("ttl_seconds", 600))
        return _json({"ok": True, "command": command}, 201)
    return await _handle(run)


async def command(request: Request) -> Response:
    async def run():
        service = _operator(request)
        command_id = request.path_params["command_id"]
        if request.method == "POST":
            public, duplicate = service.cancel_command(command_id)
            return _json({"ok": True, "duplicate": duplicate, "command": public})
        return _json({"ok": True, "command": service.get_command(command_id)})
    return await _handle(run)


ROUTES = [
    Route("/api/phone/enrollment", enrollment, methods=["POST"]),
    Route("/api/phone/enroll", enroll, methods=["POST"]),
    Route("/api/phone/heartbeat", heartbeat, methods=["POST"]),
    Route("/api/phone/commands/{command_id}/artifact", artifact, methods=["GET", "POST"]),
    Route("/api/phone/commands/{command_id}/result", result, methods=["POST"]),
    Route("/api/phone/status", status, methods=["GET"]),
    Route("/api/phone/commands", commands, methods=["POST"]),
    Route("/api/phone/commands/{command_id}", command, methods=["GET"]),
    Route("/api/phone/commands/{command_id}/cancel", command, methods=["POST"]),
]


def create_app(service: BridgeService | None = None) -> Starlette:
    if service is None:
        settings = Settings.from_env()
        service = BridgeService(settings.state_dir, read_operator_token(settings))

    @asynccontextmanager
    async def lifespan(app: Starlette):
        try:
            yield
        finally:
            service.wake_waiters()
            active = app.state.stream.session
            if active is not None:
                await app.state.stream.finish(active, "server_shutdown")

    from .stream import StreamRelay
    relay = StreamRelay(service)
    service.stream_active = relay.is_active
    routes = [*ROUTES, *relay.routes()]
    packaged_web = Path(__file__).with_name("web")
    source_web = Path(__file__).resolve().parents[2] / "web"
    web_dir = packaged_web if packaged_web.is_dir() else source_web
    if web_dir.is_dir():
        routes.append(Mount("/", app=StaticFiles(directory=web_dir, html=True), name="web"))
    app = Starlette(routes=routes, lifespan=lifespan)
    app.state.bridge = service
    app.state.stream = relay
    return app


app = None  # CLI passes an app instance to uvicorn after resolving configuration.
