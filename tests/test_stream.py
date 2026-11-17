"""Observe/takeover protocol against temporary state and in-memory sockets only."""
import asyncio
import json
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager

import pytest
from starlette.requests import Request

from phone_for_ai import stream
from phone_for_ai.core import BridgeError, BridgeService, CAPABILITY_KEYS, READ_ONLY_COMMANDS


def request(body=None):
    payload = json.dumps({} if body is None else body).encode()

    async def receive():
        return {"type": "http.request", "body": payload, "more_body": False}

    return Request({"type": "http", "method": "POST", "path": stream.BASE_PATH,
                    "headers": [(b"authorization", b"Bearer operator")]}, receive)


def paired(tmp_path, capabilities=None):
    service = BridgeService(tmp_path, "operator")
    caps = {key: True for key in CAPABILITY_KEYS} if capabilities is None else capabilities
    code = service.create_enrollment(60)["enrollment_code"]
    device = service.enroll(code, "test", caps)
    service.heartbeat(device["device_token"], {"app_version": "test", "capabilities": caps, "status": {}})
    relay = stream.StreamRelay(service)
    service.stream_active = relay.is_control_active
    return relay, device["device_token"]


class Socket:
    def __init__(self, session_id, token):
        self.path_params = {"session_id": session_id}
        self.incoming = asyncio.Queue()
        self.sent = []
        self.closed = []
        self.changed = asyncio.Event()
        self.feed({"type": "auth", "token": token})

    def feed(self, value):
        self.incoming.put_nowait({"type": "websocket.receive", "text": json.dumps(value)})

    def disconnect(self):
        self.incoming.put_nowait({"type": "websocket.disconnect", "code": 1000})

    async def accept(self): pass

    async def receive(self):
        return await self.incoming.get()

    async def send_json(self, value):
        self.sent.append(value)
        self.changed.set()

    async def send_bytes(self, value):
        self.sent.append(value)
        self.changed.set()

    async def close(self, code=1000):
        self.closed.append(code)

    async def wait_sent(self, value):
        async def wait():
            while value not in self.sent:
                self.changed.clear()
                await self.changed.wait()
        await asyncio.wait_for(wait(), 1)


@asynccontextmanager
async def connected(relay, token, *, device=True):
    created = await relay.create(request({"mode": "observe"}))
    assert created.status_code == 201, created.body
    session = json.loads(created.body)["session"]
    viewer = Socket(session["id"], session["viewer_token"])
    viewer_task = asyncio.create_task(relay.viewer_socket(viewer))
    tasks = [viewer_task]
    phone = None
    try:
        await viewer.wait_sent({"type": "status", "state": "waiting", "control_active": False})
        if device:
            phone = Socket(session["id"], token)
            tasks.append(asyncio.create_task(relay.device_socket(phone)))
            await viewer.wait_sent({"type": "status", "state": "connected", "control_active": False})
        yield viewer, phone, viewer_task
    finally:
        if relay.session is not None:
            await relay.finish(relay.session)
        await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 1)


def touch(action=0):
    return {"type": "touch", "action": action, "pointer_id": 0,
            "width": 100, "height": 200, "x": 3, "y": 4}


def test_observation_accepts_busy_phone_and_allows_next_agent_command(tmp_path):
    async def scenario():
        relay, token = paired(tmp_path)
        service = relay.service
        active = service.queue_command("clipboard.set", {"text": "first"})
        assert service.take_pending(token)["id"] == active["id"]
        assert service.command_busy()
        async with connected(relay, token) as (viewer, _, _):
            assert relay.is_active() and not relay.is_control_active()
            assert relay.desired_stream(relay.session.device_id)["mode"] == "observe"
            service.record_result(token, active["id"], {"ok": True, "result": {}})
            queued = service.queue_command("clipboard.set", {"text": "next"})
            assert service.take_pending(token)["id"] == queued["id"]
            viewer.feed({"type": "ping"})
            await viewer.wait_sent({"type": "pong"})
            assert not relay.is_control_active()
    asyncio.run(scenario())


def test_passive_inputs_keep_video_observation_and_do_not_take_control(tmp_path):
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token) as (viewer, phone, _):
            for action in (1, 2, 3): viewer.feed(touch(action))
            viewer.feed({"type": "text", "text": ""})
            viewer.feed({"type": "reset_video"})
            viewer.feed({"type": "ping"})
            await viewer.wait_sent({"type": "pong"})
            assert phone.sent == [{"type": "reset_video"}]
            assert not relay.is_control_active()
            metadata = {"type": "video", "codec": "h264", "width": 100, "height": 200}
            phone.feed(metadata)
            packet = b"\x01" + b"\0" * 8 + b"\0\0\0\1\x65test"
            phone.incoming.put_nowait({"type": "websocket.receive", "bytes": packet})
            await viewer.wait_sent(packet)
            assert metadata in viewer.sent
    asyncio.run(scenario())


@pytest.mark.parametrize("control", [touch(), {"type": "key", "keycode": 4}, {"type": "text", "text": "hello"}])
def test_first_intent_reserves_before_ack_and_preserves_input_order(tmp_path, control):
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token) as (viewer, phone, _):
            viewer.feed(control)
            following = touch(1) if control["type"] == "touch" else {"type": "key", "keycode": 3}
            viewer.feed(following)
            await phone.wait_sent({"type": "take_control"})
            assert relay.is_control_active()
            assert phone.sent == [{"type": "take_control"}]
            status = json.loads((await relay.status(request())).body)
            assert status["control_active"] is True and status["session"]["control_active"] is True
            with pytest.raises(BridgeError, match="phone_stream_in_use"):
                relay.service.queue_command("clipboard.set", {"text": "blocked"})
            phone.feed({"type": "control_ready"})
            await phone.wait_sent(following)
            assert phone.sent == [{"type": "take_control"}, control, following]
            assert {"type": "status", "state": "connected", "control_active": True} in viewer.sent
        assert not relay.is_control_active()
        assert json.loads((await relay.status(request())).body)["control_active"] is False
    asyncio.run(scenario())


def test_preconnect_intent_is_dropped_without_reserving_controls(tmp_path):
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token, device=False) as (viewer, _, _):
            viewer.feed(touch())
            viewer.feed({"type": "ping"})
            await viewer.wait_sent({"type": "pong"})
            assert not relay.is_control_active()
    asyncio.run(scenario())


@pytest.mark.parametrize("value", [None, False, 1, "true"])
def test_legacy_or_malformed_observe_capability_is_rejected(tmp_path, value):
    async def scenario():
        relay, _ = paired(tmp_path, {"screen_stream": True, "screen_stream_observe": value})
        response = await relay.create(request())
        assert response.status_code == 409
        assert json.loads(response.body)["error"] == "screen_stream_observe_unsupported"
        assert relay.session is None
    asyncio.run(scenario())


@pytest.mark.parametrize("control", [
    {**touch(), "x": -1}, {**touch(), "action": 4}, {**touch(), "pointer_id": True},
    {"type": "key", "keycode": 255}, {"type": "text", "text": 5},
    {"type": "text", "text": "x" * 2001}, {"type": "text", "text": "\ud800"},
    {"type": "take_control"}, [],
])
def test_invalid_control_never_takes_over_and_cleans_up(tmp_path, control):
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token) as (viewer, phone, task):
            viewer.feed(control)
            await asyncio.wait_for(task, 1)
            assert {"type": "take_control"} not in phone.sent
            assert not relay.is_control_active() and relay.session is None
            assert any(isinstance(v, dict) and v.get("type") == "error" for v in viewer.sent)
    asyncio.run(scenario())


def test_takeover_timeout_never_replays_first_input(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, "CONTROL_TIMEOUT", 0.01)
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token) as (viewer, phone, task):
            viewer.feed(touch())
            await asyncio.wait_for(task, 1)
            assert {"type": "error", "error": "control_takeover_timeout"} in viewer.sent
            assert touch() not in phone.sent
            assert relay.session is None and not relay.is_control_active()
    asyncio.run(scenario())


@pytest.mark.parametrize("failure", ["disconnect", "device_error", "unsolicited_ack"])
def test_phone_failure_releases_pending_control_and_blocked_readers(tmp_path, failure):
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token) as (viewer, phone, task):
            if failure != "unsolicited_ack":
                viewer.feed(touch())
                await phone.wait_sent({"type": "take_control"})
            if failure == "disconnect": phone.disconnect()
            elif failure == "device_error": phone.feed({"type": "error", "error": "control_takeover_failed"})
            else: phone.feed({"type": "control_ready"})
            await asyncio.wait_for(task, 1)
            assert touch() not in phone.sent
            assert relay.session is None and not relay.is_control_active()
    asyncio.run(scenario())


def test_cancelling_viewer_during_takeover_preserves_cancellation_and_releases_control(tmp_path):
    async def scenario():
        relay, token = paired(tmp_path)
        async with connected(relay, token) as (viewer, phone, task):
            viewer.feed(touch())
            await phone.wait_sent({"type": "take_control"})
            task.cancel("test cancellation")
            with pytest.raises(asyncio.CancelledError) as caught:
                await task
            assert caught.value.args == ("test cancellation",)
            assert not relay.is_control_active() and relay.session is None
    asyncio.run(scenario())


@pytest.mark.parametrize("operation", ["queue", "dispatch"])
def test_threaded_command_operations_share_takeover_mutex(tmp_path, operation):
    relay, token = paired(tmp_path)
    service = relay.service
    session = stream.Session("a" * 32, service.device_info()["id"], "viewer")
    relay.session = session
    if operation == "dispatch": service.queue_command("clipboard.set", {"text": "queued first"})
    started = threading.Event()

    def command_thread():
        started.set()
        if operation == "dispatch": return service.take_pending(token)
        return service.queue_command("clipboard.set", {"text": "after intent"})

    with ThreadPoolExecutor(max_workers=1) as executor:
        with service.lock:
            future = executor.submit(command_thread)
            assert started.wait(1)
            relay.mark_control(session)
        if operation == "dispatch":
            assert future.result(timeout=1) is None
            assert service._load()["commands"][-1]["status"] == "pending"
        else:
            with pytest.raises(BridgeError, match="phone_stream_in_use"): future.result(timeout=1)
            assert service._load()["commands"] == []


@pytest.mark.parametrize("command_type", sorted(READ_ONLY_COMMANDS))
def test_control_owner_allows_pure_read_admission_and_dispatch(tmp_path, command_type):
    relay, token = paired(tmp_path)
    relay.session = stream.Session("b" * 32, relay.service.device_info()["id"], "viewer")
    relay.mark_control(relay.session)
    args = {"path": "/data/local/tmp/example"} if command_type.startswith("files.") else {}
    queued = relay.service.queue_command(command_type, args)
    assert relay.service.take_pending(token)["id"] == queued["id"]
    assert relay.is_control_active()
