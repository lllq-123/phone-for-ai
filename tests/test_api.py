import asyncio
import base64
from types import SimpleNamespace

import pytest
from starlette.requests import Request
from starlette.testclient import TestClient

from phone_for_ai.api import _payload, _read_limited, commands, create_app
from phone_for_ai.core import BridgeError, BridgeService, MAX_BODY_BYTES
from phone_for_ai.stream import Session, StreamRelay


def auth(token): return {"Authorization":f"Bearer {token}"}


def test_operator_and_device_auth_are_isolated(tmp_path):
    service=BridgeService(tmp_path,"operator")
    client=TestClient(create_app(service))
    assert client.get("/api/phone/status",headers=auth("wrong")).status_code==401
    enrollment=client.post("/api/phone/enrollment",headers=auth("operator"),json={"ttl_seconds":60}).json()
    device=client.post("/api/phone/enroll",json={"enrollment_code":enrollment["enrollment_code"],"app_version":"1","capabilities":{}}).json()
    assert client.get("/api/phone/status",headers=auth(device["device_token"])).status_code==401
    assert client.post("/api/phone/heartbeat",headers=auth("operator"),json={"app_version":"1","capabilities":{},"status":{},"long_poll_seconds":0}).status_code==401
    assert client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{},"status":{},"long_poll_seconds":0}).status_code==200
    status=client.get("/api/phone/status",headers=auth("operator")).json()
    assert "token" not in str(status).lower()


def test_stream_first_frame_auth_relay_and_command_exclusion(tmp_path):
    service=BridgeService(tmp_path,"operator")
    client=TestClient(create_app(service))
    enrollment=client.post("/api/phone/enrollment",headers=auth("operator"),json={"ttl_seconds":60}).json()
    device=client.post("/api/phone/enroll",json={"enrollment_code":enrollment["enrollment_code"],"app_version":"1","capabilities":{"screen_stream":True,"screen_capture":True}}).json()
    client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"screen_stream":True,"screen_capture":True},"status":{},"long_poll_seconds":0})
    created=client.post("/api/phone/stream",headers=auth("operator"),json={}).json()
    session=created["session"]
    status=client.get("/api/phone/stream",headers=auth("operator")).json()
    assert "viewer_token" not in str(status)
    with client.websocket_connect(session["viewer_path"]) as viewer:
        viewer.send_json({"type":"auth","token":session["viewer_token"]})
        assert viewer.receive_json()=={"type":"status","state":"waiting"}
        busy=client.post("/api/phone/commands",headers=auth("operator"),json={"type":"screen.capture","args":{}})
        assert busy.status_code==409 and busy.json()["error"]=="phone_stream_in_use"
        heartbeat=client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"screen_stream":True,"screen_capture":True},"status":{},"long_poll_seconds":0}).json()
        assert heartbeat["stream"]["device_path"].endswith("/device")
        viewer.send_json({"type":"ping"})
        assert viewer.receive_json()=={"type":"pong"}


def test_chunked_body_without_content_length_stops_at_limit():
    calls = 0
    chunks = [b"{" + b" " * 70000, b" " * 70000, b"should-not-be-read"]

    async def receive():
        nonlocal calls
        value = chunks[calls]
        calls += 1
        return {"type": "http.request", "body": value, "more_body": calls < len(chunks)}

    request = Request({
        "type": "http", "http_version": "1.1", "method": "POST", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"",
        "headers": [(b"content-type", b"application/json")],
        "client": ("test", 1), "server": ("test", 80),
    }, receive)
    with pytest.raises(BridgeError) as caught:
        asyncio.run(_payload(request))
    assert caught.value.status_code == 413 and caught.value.code == "payload_too_large"
    assert calls == 2

    async def artifact_receive():
        return {"type": "http.request", "body": b"12345", "more_body": False}
    artifact_request = Request({
        "type": "http", "http_version": "1.1", "method": "POST", "scheme": "http",
        "path": "/", "raw_path": b"/", "query_string": b"", "headers": [],
        "client": ("test", 1), "server": ("test", 80),
    }, artifact_receive)
    with pytest.raises(BridgeError) as artifact_error:
        asyncio.run(_read_limited(artifact_request, 4, "artifact_too_large"))
    assert artifact_error.value.code == "artifact_too_large"


def test_lifespan_stops_unattached_stream(tmp_path):
    service=BridgeService(tmp_path,"operator")
    app=create_app(service)
    with TestClient(app) as client:
        enrollment=client.post("/api/phone/enrollment",headers=auth("operator"),json={"ttl_seconds":60}).json()
        device=client.post("/api/phone/enroll",json={"enrollment_code":enrollment["enrollment_code"],"app_version":"1","capabilities":{"screen_stream":True}}).json()
        client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"screen_stream":True},"status":{},"long_poll_seconds":0})
        assert client.post("/api/phone/stream",headers=auth("operator"),json={}).status_code==201
        assert app.state.stream.session is not None
    assert app.state.stream.session is None


def test_new_enrollment_closes_old_viewer_and_device(tmp_path):
    service=BridgeService(tmp_path,"operator")
    app=create_app(service)
    with TestClient(app) as client:
        first=client.post("/api/phone/enrollment",headers=auth("operator"),json={"ttl_seconds":60}).json()
        device=client.post("/api/phone/enroll",json={"enrollment_code":first["enrollment_code"],"app_version":"1","capabilities":{"screen_stream":True}}).json()
        client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"screen_stream":True},"status":{},"long_poll_seconds":0})
        stream=client.post("/api/phone/stream",headers=auth("operator"),json={}).json()["session"]
        with client.websocket_connect(stream["viewer_path"]) as viewer:
            viewer.send_json({"type":"auth","token":stream["viewer_token"]})
            assert viewer.receive_json()=={"type":"status","state":"waiting"}
            desired=client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"screen_stream":True},"status":{},"long_poll_seconds":0}).json()["stream"]
            with client.websocket_connect(desired["device_path"]) as phone:
                phone.send_json({"type":"auth","token":device["device_token"]})
                assert viewer.receive_json()=={"type":"status","state":"connected"}
                replacement=client.post("/api/phone/enrollment",headers=auth("operator"),json={"ttl_seconds":60})
                assert replacement.status_code==201
                assert viewer.receive_json()=={"type":"error","error":"pairing_revoked"}
                assert viewer.receive_json()=={"type":"stopped"}
                assert phone.receive_json()=={"type":"error","error":"pairing_revoked"}
                assert phone.receive_json()=={"type":"stopped"}
                assert app.state.stream.session is None
                assert client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{},"status":{},"long_poll_seconds":0}).status_code==401


def test_slow_stream_delete_keeps_command_queue_reserved(tmp_path):
    async def scenario():
        service=BridgeService(tmp_path,"operator")
        code=service.create_enrollment(60)["enrollment_code"]
        device=service.enroll(code,"1",{"screen_stream":True,"screen_capture":True})
        service.heartbeat(device["device_token"],{"app_version":"1","capabilities":{"screen_stream":True,"screen_capture":True},"status":{}})
        relay=StreamRelay(service); service.stream_active=relay.is_active
        started=asyncio.Event(); release=asyncio.Event()

        class SlowPeer:
            async def send(self, value):
                started.set()
                await release.wait()
            async def close(self, code=1000): pass

        session=Session("a"*32,device["device_id"],"viewer",viewer_seen=__import__("time").monotonic(),viewer=SlowPeer())
        relay.session=session
        app=SimpleNamespace(state=SimpleNamespace(bridge=service,stream=relay))
        delete_request=Request({
            "type":"http","method":"DELETE","path":"/api/phone/stream/"+session.id,
            "headers":[(b"authorization",b"Bearer operator")],"path_params":{"session_id":session.id},"app":app,
        })
        delete_task=asyncio.create_task(relay.delete(delete_request))
        await started.wait()
        body=b'{"type":"screen.capture","args":{}}'
        sent=False
        async def receive():
            nonlocal sent
            if sent: return {"type":"http.request","body":b"","more_body":False}
            sent=True
            return {"type":"http.request","body":body,"more_body":False}
        command_request=Request({
            "type":"http","http_version":"1.1","method":"POST","scheme":"http",
            "path":"/api/phone/commands","raw_path":b"/api/phone/commands","query_string":b"",
            "headers":[(b"authorization",b"Bearer operator"),(b"content-type",b"application/json")],
            "client":("test",1),"server":("test",80),"app":app,
        },receive)
        response=await commands(command_request)
        assert response.status_code==409
        assert b'phone_stream_in_use' in response.body
        release.set()
        await delete_task
        assert relay.session is None
    asyncio.run(scenario())


def test_files_write_is_redacted_for_operator_but_complete_for_device(tmp_path):
    service=BridgeService(tmp_path,"operator")
    client=TestClient(create_app(service))
    enrollment=client.post("/api/phone/enrollment",headers=auth("operator"),json={"ttl_seconds":60}).json()
    device=client.post("/api/phone/enroll",json={"enrollment_code":enrollment["enrollment_code"],"app_version":"1","capabilities":{"file_access":True}}).json()
    client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"file_access":True},"status":{},"long_poll_seconds":0})
    encoded=base64.b64encode(b"private image bytes").decode()
    queued=client.post("/api/phone/commands",headers=auth("operator"),json={"type":"files.write","args":{"path":"/sdcard/Pictures/image.jpg","data_base64":encoded,"append":False}}).json()
    assert queued["command"]["args"] == {
        "path":"/sdcard/Pictures/image.jpg","append":False,
        "data_bytes":len(b"private image bytes"),"data_base64_omitted":True,
    }
    status=client.get("/api/phone/status",headers=auth("operator")).json()
    assert encoded not in str(status)
    delivered=client.post("/api/phone/heartbeat",headers=auth(device["device_token"]),json={"app_version":"1","capabilities":{"file_access":True},"status":{},"long_poll_seconds":0}).json()["command"]
    assert delivered["args"]["data_base64"] == encoded
