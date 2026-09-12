import json

from phone_for_ai.client import command_succeeded
from phone_for_ai.mcp_server import _general, _with_image


class FakeClient:
    def __init__(self): self.ids=[]
    def artifact(self, command_id): self.ids.append(command_id); return b"remote-jpeg"


def test_mcp_fetches_remote_artifact_bytes():
    fake=FakeClient()
    blocks=_with_image({"ok":True,"command":{"id":"abc","artifact":{"available":True}}}, fake)
    assert fake.ids==["abc"]
    assert len(blocks)==2


def test_command_and_shell_failures_are_not_execution_success():
    failed={"ok":True,"command":{"type":"screen.capture","status":"failed","ok":False,"result":{},"error":"capture_failed"}}
    nonzero={"ok":True,"command":{"type":"root.exec","status":"completed","ok":True,"result":{"exit_code":7,"timed_out":False}}}
    timed_out={"ok":True,"command":{"type":"root.exec","status":"completed","ok":True,"result":{"exit_code":0,"timed_out":True}}}
    success={"ok":True,"command":{"type":"root.exec","status":"completed","ok":True,"result":{"exit_code":0,"timed_out":False}}}
    assert command_succeeded(failed) is False
    assert command_succeeded(nonzero) is False
    assert command_succeeded(timed_out) is False
    assert command_succeeded(success) is True
    assert json.loads(_general(nonzero))["execution_ok"] is False
