from __future__ import annotations

import os
import secrets
import stat
from dataclasses import dataclass
from pathlib import Path


DEFAULT_API_BASE = "http://127.0.0.1:8765/api/phone"


def default_state_dir() -> Path:
    configured = os.environ.get("PHONE_FOR_AI_STATE_DIR")
    if configured:
        return Path(configured).expanduser()
    data_home = os.environ.get("XDG_DATA_HOME")
    root = Path(data_home).expanduser() if data_home else Path.home() / ".local" / "share"
    return root / "phone-for-ai"


def token_file_for(state_dir: Path) -> Path:
    configured = os.environ.get("PHONE_FOR_AI_TOKEN_FILE")
    return Path(configured).expanduser() if configured else state_dir / "operator.token"


@dataclass(frozen=True)
class Settings:
    state_dir: Path
    token_file: Path
    api_base: str

    @classmethod
    def from_env(cls, *, state_dir: str | Path | None = None) -> "Settings":
        resolved_state = Path(state_dir).expanduser() if state_dir is not None else default_state_dir()
        return cls(
            state_dir=resolved_state,
            token_file=token_file_for(resolved_state),
            api_base=os.environ.get("PHONE_FOR_AI_API_BASE", DEFAULT_API_BASE).rstrip("/"),
        )


def ensure_private_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)


def initialize(settings: Settings) -> bool:
    """Create the state directory and operator token. Return True when newly created."""
    ensure_private_dir(settings.state_dir)
    if not settings.token_file.parent.exists():
        settings.token_file.parent.mkdir(parents=True, mode=0o700)
    try:
        fd = os.open(settings.token_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        mode = stat.S_IMODE(settings.token_file.stat().st_mode)
        if mode != 0o600:
            settings.token_file.chmod(0o600)
        return False
    try:
        os.write(fd, (secrets.token_urlsafe(32) + "\n").encode("ascii"))
        os.fsync(fd)
    finally:
        os.close(fd)
    return True


def read_operator_token(settings: Settings) -> str:
    try:
        token = settings.token_file.read_text(encoding="utf-8").strip()
    except FileNotFoundError as exc:
        raise RuntimeError(f"operator token is missing; run phone-for-ai init ({settings.token_file})") from exc
    if not token:
        raise RuntimeError(f"operator token is empty: {settings.token_file}")
    return token
