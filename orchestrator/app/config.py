from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    spring_api_base: str
    internal_token: str
    checkpoint_path: Path

    @classmethod
    def from_environment(cls) -> "Settings":
        return cls(
            spring_api_base=os.getenv("TESTPILOT_API_BASE", "http://localhost:8080").rstrip("/"),
            internal_token=os.getenv("WORKFLOW_INTERNAL_TOKEN", ""),
            checkpoint_path=Path(
                os.getenv("LANGGRAPH_CHECKPOINT_PATH", "data/checkpoints.sqlite3")
            ),
        )

    def require_internal_token(self) -> None:
        if len(self.internal_token.encode("utf-8")) < 32:
            raise RuntimeError(
                "WORKFLOW_INTERNAL_TOKEN must contain at least 32 UTF-8 bytes"
            )
