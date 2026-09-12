from __future__ import annotations

import hmac
import os
import sqlite3
from threading import Lock

os.environ.setdefault("LANGGRAPH_STRICT_MSGPACK", "true")

from fastapi import Depends, FastAPI, Header, HTTPException, status
from langgraph.checkpoint.sqlite import SqliteSaver

from .config import Settings
from .graph import WorkflowRuntime, build_graph
from .models import (
    WorkflowInvocationResponse,
    WorkflowResumeRequest,
    WorkflowStartRequest,
)
from .tools import SpringWorkflowTools


def create_runtime(settings: Settings) -> WorkflowRuntime:
    settings.require_internal_token()
    checkpoint_path = settings.checkpoint_path
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(checkpoint_path, check_same_thread=False)
    checkpointer = SqliteSaver(connection)
    tools = SpringWorkflowTools(settings.spring_api_base, settings.internal_token)
    return WorkflowRuntime(build_graph(tools, checkpointer))


def create_app(
    settings: Settings | None = None,
    runtime: WorkflowRuntime | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_environment()
    resolved_runtime = runtime or create_runtime(resolved_settings)
    locks: dict[str, Lock] = {}
    locks_guard = Lock()

    def require_internal_token(
        supplied: str | None = Header(default=None, alias="X-TestPilot-Internal-Token"),
    ) -> None:
        expected = resolved_settings.internal_token
        valid = (
            len(expected.encode("utf-8")) >= 32
            and supplied is not None
            and hmac.compare_digest(expected, supplied)
        )
        if not valid:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Internal workflow authentication failed",
            )

    def thread_lock(thread_id: str) -> Lock:
        with locks_guard:
            return locks.setdefault(thread_id, Lock())

    app = FastAPI(title="TestPilot LangGraph Orchestrator", version="1.0")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.post(
        "/v1/workflows",
        response_model=WorkflowInvocationResponse,
        dependencies=[Depends(require_internal_token)],
    )
    def start(request: WorkflowStartRequest) -> WorkflowInvocationResponse:
        with thread_lock(request.thread_id):
            return resolved_runtime.start(request)

    @app.post(
        "/v1/workflows/{thread_id}/resume",
        response_model=WorkflowInvocationResponse,
        dependencies=[Depends(require_internal_token)],
    )
    def resume(
        thread_id: str,
        request: WorkflowResumeRequest,
    ) -> WorkflowInvocationResponse:
        try:
            with thread_lock(thread_id):
                return resolved_runtime.resume(thread_id, request.approved, request.comment)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    @app.get(
        "/v1/workflows/{thread_id}",
        response_model=WorkflowInvocationResponse,
        dependencies=[Depends(require_internal_token)],
    )
    def get_status(thread_id: str) -> WorkflowInvocationResponse:
        try:
            with thread_lock(thread_id):
                return resolved_runtime.status(thread_id)
        except ValueError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc

    return app


app = create_app()
