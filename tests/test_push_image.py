import os
from pathlib import Path, PurePosixPath
import runpy
import subprocess
import sys

import pytest


scan_command = runpy.run_path(str(Path(__file__).parents[1] / "scripts" / "push-image.py"))["media_scan_command"]


@pytest.mark.parametrize("provider_result, succeeds", [
    ("Row: 0 _data=ignored, _size=79", True),
    ("No result found.", False),
    ("Row: 0 _data=ignored, _size=790", False),
])
def test_scan_requires_correct_media_row_and_quotes_paths(tmp_path, provider_result, succeeds):
    # Run the real shell gate against a provider fixture that exits zero even
    # when it did not index anything, as Android's content command can do.
    target = tmp_path / "cat's $(touch PWNED).png"
    target.write_bytes(b"x" * 79)
    executable = tmp_path / "content"
    executable.write_text(f"#!{sys.executable}\n" + '''
import os, sys
args = sys.argv[1:]
path = os.environ["EXPECTED_PATH"]
if args[0] == "call":
    assert args[args.index("--method") + 1] == "scan_file"
    assert args[args.index("--arg") + 1] == path
    print("Result: Bundle[]")
else:
    assert args[0] == "query"
    assert args[args.index("--where") + 1] == "_data='" + path.replace("'", "''") + "'"
    print(os.environ["PROVIDER_RESULT"])
''')
    executable.chmod(0o755)
    env = {**os.environ, "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"],
           "EXPECTED_PATH": str(target.resolve()), "PROVIDER_RESULT": provider_result}
    result = subprocess.run(["/bin/sh", "-c", scan_command(PurePosixPath(target), 79)],
                            cwd=tmp_path, env=env, capture_output=True, text=True)
    assert (result.returncode == 0) is succeeds, result.stderr
    assert not (tmp_path / "PWNED").exists()
