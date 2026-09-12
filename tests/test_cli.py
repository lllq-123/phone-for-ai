from phone_for_ai import cli


class FakeClient:
    def __init__(self, result): self.result = result
    def run(self, command_type, args, wait): return self.result


def test_call_exit_code_follows_command_receipt(monkeypatch, capsys):
    failed={"ok":True,"command":{"type":"screen.capture","status":"failed","ok":False,"result":{},"error":"capture_failed"}}
    monkeypatch.setattr(cli, "_client", lambda settings: FakeClient(failed))
    assert cli.main(["call","phone_capture_screen","--args","{}","--wait","0"]) == 1
    capsys.readouterr()

    nonzero={"ok":True,"command":{"type":"root.exec","status":"completed","ok":True,"result":{"exit_code":2,"timed_out":False}}}
    monkeypatch.setattr(cli, "_client", lambda settings: FakeClient(nonzero))
    assert cli.main(["call","phone_shell","--args",'{"command":"false"}',"--wait","0"]) == 1
