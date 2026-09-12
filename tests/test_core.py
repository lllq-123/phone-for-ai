import json
import stat

import pytest

from phone_for_ai.config import Settings, initialize
from phone_for_ai.core import BridgeError, BridgeService


class Clock:
    def __init__(self): self.value = 1_800_000_000.0
    def __call__(self): return self.value


CAPS = {k: True for k in ("screen_capture", "wake_screen", "gestures", "text_input", "launch", "ui_tree", "clipboard", "root_shell", "file_access", "app_inventory", "screen_stream")}


def paired(tmp_path):
    clock = Clock(); service = BridgeService(tmp_path, "operator-secret", clock=clock)
    code = service.create_enrollment(60)["enrollment_code"]
    enrolled = service.enroll(code, "0.1.0", CAPS)
    service.heartbeat(enrolled["device_token"], {"app_version":"0.1.0","capabilities":CAPS,"status":{}})
    return service, enrolled["device_token"], clock


def finish_capture(service, token, package="com.example.app"):
    command = service.queue_command("screen.capture", {})
    delivered = service.take_pending(token)
    assert delivered["id"] == command["id"]
    service.upload_artifact(token, command["id"], b"\xff\xd8abc\xff\xd9")
    service.record_result(token, command["id"], {"ok":True,"result":{"package_name":package,"image_width":720,"image_height":1280,"display_width":1080,"display_height":1920}})
    return command


def test_init_permissions_and_enrollment_one_use_expiry(tmp_path):
    settings = Settings(tmp_path / "state", tmp_path / "state" / "operator.token", "http://x")
    assert initialize(settings) is True
    assert initialize(settings) is False
    assert stat.S_IMODE(settings.state_dir.stat().st_mode) == 0o700
    assert stat.S_IMODE(settings.token_file.stat().st_mode) == 0o600
    service = BridgeService(tmp_path / "expired", "op", clock=(clock := Clock()))
    code = service.create_enrollment(60)["enrollment_code"]
    service.enroll(code, "1", {})
    with pytest.raises(BridgeError, match="invalid_or_expired"): service.enroll(code, "1", {})
    code2 = service.create_enrollment(60)["enrollment_code"]; clock.value += 61
    with pytest.raises(BridgeError, match="invalid_or_expired"): service.enroll(code2, "1", {})


def test_single_active_delivery_once_and_persistence(tmp_path):
    service, token, clock = paired(tmp_path)
    command = service.queue_command("clipboard.set", {"text":""})
    with pytest.raises(BridgeError, match="command_busy"): service.queue_command("screen.capture", {})
    assert service.take_pending(token)["id"] == command["id"]
    assert service.take_pending(token) is None
    restarted = BridgeService(tmp_path, "operator-secret", clock=clock)
    assert restarted.take_pending(token) is None
    receipt, duplicate = restarted.record_result(token, command["id"], {"ok":True,"result":{}})
    assert receipt["status"] == "completed" and duplicate is False
    _, duplicate = restarted.record_result(token, command["id"], {"ok":True,"result":{}})
    assert duplicate is True
    with pytest.raises(BridgeError, match="result_conflict"):
        restarted.record_result(token, command["id"], {"ok":False,"result":{},"error":"different"})


def test_basis_guards_and_non_ui_does_not_consume(tmp_path):
    service, token, clock = paired(tmp_path)
    capture = finish_capture(service, token)
    non_ui = service.queue_command("clipboard.set", {"text":""})
    service.take_pending(token); service.record_result(token, non_ui["id"], {"ok":True,"result":{}})
    with pytest.raises(BridgeError, match="basis_package_mismatch"):
        service.queue_command("ui.tap", {"basis_command_id":capture["id"],"expected_package":"com.other.app","x":1,"y":1})
    action = service.queue_command("ui.tap", {"basis_command_id":capture["id"],"expected_package":"com.example.app","x":1,"y":1})
    service.cancel_command(action["id"])
    with pytest.raises(BridgeError, match="basis_already_used"):
        service.queue_command("ui.tap", {"basis_command_id":capture["id"],"expected_package":"com.example.app","x":1,"y":1})
    capture2 = finish_capture(service, token); clock.value += 181
    with pytest.raises(BridgeError, match="basis_stale"):
        service.queue_command("ui.type", {"basis_command_id":capture2["id"],"expected_package":"com.example.app","text":""})


def test_basis_cannot_cross_device_pairing(tmp_path):
    service, token, _ = paired(tmp_path)
    capture = finish_capture(service, token)
    code = service.create_enrollment(60)["enrollment_code"]
    replacement = service.enroll(code, "0.1.0", CAPS)
    service.heartbeat(replacement["device_token"], {"app_version":"0.1.0","capabilities":CAPS,"status":{}})
    with pytest.raises(BridgeError, match="basis_wrong_device"):
        service.queue_command("ui.tap", {"basis_command_id":capture["id"],"expected_package":"com.example.app","x":1,"y":1})


def test_artifact_precedes_result_limits_and_no_paths(tmp_path):
    service, token, _ = paired(tmp_path)
    command = service.queue_command("screen.capture", {}); service.take_pending(token)
    payload={"ok":True,"result":{"package_name":"com.example.app","image_width":1,"image_height":1,"display_width":1,"display_height":1}}
    with pytest.raises(BridgeError, match="artifact_required"): service.record_result(token, command["id"], payload)
    public, _ = service.upload_artifact(token, command["id"], b"\xff\xd8\xff\xd9")
    assert "file" not in public
    receipt, _ = service.record_result(token, command["id"], payload)
    assert "path" not in json.dumps(receipt)
    with pytest.raises(BridgeError, match="invalid_file_data|file_chunk_too_large"):
        service.queue_command("files.write", {"path":"/tmp/x","data_base64":__import__("base64").b64encode(b"x"*49153).decode(),"append":False})
