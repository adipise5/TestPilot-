from __future__ import annotations

from collections import Counter
import os
from pathlib import Path
import sqlite3
from typing import Any

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.sqlite import SqliteSaver

from app.graph import WorkflowRuntime, build_graph
from app.models import WorkflowStartRequest


os.environ.setdefault("LANGGRAPH_STRICT_MSGPACK", "true")


class FakeWorkflowTools:
    def __init__(self, *, requires_approval: bool) -> None:
        self.requires_approval = requires_approval
        self.calls: Counter[str] = Counter()

    def invoke(self, node: str, state: dict[str, Any]) -> dict[str, Any]:
        self.calls[node] += 1
        if node == "intake":
            return {
                "commit_sha": "a" * 40,
                "repository_connected": True,
                "source_count": 2 if self.requires_approval else 1,
                "source_paths": ["src/main/java/example/Calculator.java"],
                "build_system": "MAVEN",
            }
        if node == "codebase_mapper":
            return {
                "analysis": {"summary": "mapped"},
                "codebase_map": {
                    "source_count": 2 if self.requires_approval else 1,
                    "packages": ["example"],
                    "modules": ["root"],
                    "frameworks": ["SPRING"] if self.requires_approval else [],
                    "external_resources": ["DATABASE"] if self.requires_approval else [],
                },
            }
        if node == "test_planner":
            levels = ["UNIT", "MODULE", "INTEGRATION"] if self.requires_approval else ["UNIT"]
            return {
                "test_plan": [
                    {"level": level, "objective": f"{level.lower()} coverage"}
                    for level in levels
                ],
                "approval_required": self.requires_approval,
                "approval_reason": "Database-backed integration plan requires approval",
            }
        if node.endswith("_test_specialist"):
            level = node.removesuffix("_test_specialist").upper()
            suffix = {"UNIT": "Test", "MODULE": "ModuleTest", "INTEGRATION": "IntegrationTest"}[level]
            return {
                "proposal": {
                    "level": level,
                    "source_file": "Calculator.java",
                    "test_class": f"example.Calculator{suffix}",
                    "test_code": f"class Calculator{suffix} {{ @Test void works() {{}} }}",
                }
            }
        if node == "test_reviewer":
            return {
                "accepted_proposals": state["test_proposals"],
                "review": {"approved": True},
            }
        if node == "execution_coordinator":
            return {
                "execution": {
                    "outcome": "SUCCESS",
                    "completed_test_process": True,
                    "failed_result_ids": [],
                }
            }
        if node == "failure_triage":
            return {"triage": []}
        if node == "report":
            terminal = "REJECTED" if state.get("approval_decision") == "REJECTED" else "COMPLETED"
            return {
                "workflow_status": terminal,
                "report": {"decision": terminal, "commit_sha": state["commit_sha"]},
            }
        raise AssertionError(f"Unexpected node: {node}")


def request(thread_id: str) -> WorkflowStartRequest:
    return WorkflowStartRequest(
        contract_version=1,
        test_run_id=41,
        project_id=7,
        thread_id=thread_id,
        graph_version="testpilot-v1",
    )


def test_unit_only_workflow_completes_without_human_interrupt() -> None:
    tools = FakeWorkflowTools(requires_approval=False)
    runtime = WorkflowRuntime(build_graph(tools, InMemorySaver()))

    response = runtime.start(request("unit-thread"))

    assert response.status == "COMPLETED"
    assert tools.calls["unit_test_specialist"] == 1
    assert tools.calls["module_test_specialist"] == 0
    assert tools.calls["integration_test_specialist"] == 0
    assert tools.calls["execution_coordinator"] == 1


def test_sqlite_checkpoint_resumes_after_restart_without_repeating_completed_tools(
    tmp_path: Path,
) -> None:
    tools = FakeWorkflowTools(requires_approval=True)
    checkpoint_path = tmp_path / "workflow.sqlite3"
    first_connection = sqlite3.connect(checkpoint_path, check_same_thread=False)
    first_runtime = WorkflowRuntime(build_graph(tools, SqliteSaver(first_connection)))

    waiting = first_runtime.start(request("durable-thread"))
    calls_before_resume = tools.calls.copy()
    first_connection.close()

    assert waiting.status == "WAITING_FOR_APPROVAL"
    assert waiting.interrupt is not None
    assert tools.calls["execution_coordinator"] == 0

    second_connection = sqlite3.connect(checkpoint_path, check_same_thread=False)
    second_runtime = WorkflowRuntime(build_graph(tools, SqliteSaver(second_connection)))
    completed = second_runtime.resume("durable-thread", approved=True, comment="Approved fixture DB")
    second_connection.close()

    assert completed.status == "COMPLETED"
    for node, count in calls_before_resume.items():
        assert tools.calls[node] == count
    assert tools.calls["execution_coordinator"] == 1
    assert tools.calls["report"] == 1


def test_rejected_integration_plan_never_calls_execution_tool() -> None:
    tools = FakeWorkflowTools(requires_approval=True)
    runtime = WorkflowRuntime(build_graph(tools, InMemorySaver()))

    waiting = runtime.start(request("rejected-thread"))
    rejected = runtime.resume("rejected-thread", approved=False, comment="No external DB")

    assert waiting.status == "WAITING_FOR_APPROVAL"
    assert rejected.status == "REJECTED"
    assert tools.calls["execution_coordinator"] == 0
    assert tools.calls["report"] == 1


def test_start_is_idempotent_after_completion() -> None:
    tools = FakeWorkflowTools(requires_approval=False)
    runtime = WorkflowRuntime(build_graph(tools, InMemorySaver()))
    workflow_request = request("idempotent-thread")

    first = runtime.start(workflow_request)
    calls_after_first = tools.calls.copy()
    second = runtime.start(workflow_request)

    assert first.status == "COMPLETED"
    assert second.status == "COMPLETED"
    assert tools.calls == calls_after_first
