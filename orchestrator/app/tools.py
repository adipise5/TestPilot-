from __future__ import annotations

from copy import deepcopy
from typing import Any, Protocol

import httpx


class RetryableToolError(Exception):
    """A transient tool failure that LangGraph may retry."""


class ToolContractError(ValueError):
    """A non-retryable contract or policy failure."""


class WorkflowTools(Protocol):
    def invoke(self, node: str, state: dict[str, Any]) -> dict[str, Any]: ...


class SpringWorkflowTools:
    def __init__(
        self,
        api_base: str,
        internal_token: str,
        timeout_seconds: float = 150.0,
    ) -> None:
        self._api_base = api_base.rstrip("/")
        self._internal_token = internal_token
        self._timeout = timeout_seconds

    def invoke(self, node: str, state: dict[str, Any]) -> dict[str, Any]:
        safe_state = deepcopy(state)
        payload = {
            "contractVersion": 1,
            "testRunId": safe_state["test_run_id"],
            "threadId": safe_state["thread_id"],
            "graphVersion": safe_state["graph_version"],
            "idempotencyKey": (
                f"{safe_state['thread_id']}:{safe_state['graph_version']}:{node}:v1"
            ),
            "state": safe_state,
        }
        try:
            response = httpx.post(
                f"{self._api_base}/api/internal/v1/workflow-tools/{node}",
                headers={"X-TestPilot-Internal-Token": self._internal_token},
                json=payload,
                timeout=self._timeout,
            )
        except httpx.RequestError as exc:
            raise RetryableToolError(f"Spring workflow tool {node} is unavailable") from exc

        if response.status_code >= 500:
            raise RetryableToolError(f"Spring workflow tool {node} failed transiently")
        if response.status_code >= 400:
            try:
                detail = response.json().get("message", "request was rejected")
            except ValueError:
                detail = "request was rejected"
            raise ToolContractError(f"Spring workflow tool {node}: {detail}")

        body = response.json()
        if body.get("contractVersion") != 1 or body.get("node") != node:
            raise ToolContractError(f"Spring workflow tool {node} returned a mismatched contract")
        updates = body.get("updates")
        if not isinstance(updates, dict):
            raise ToolContractError(f"Spring workflow tool {node} returned invalid updates")
        return updates
