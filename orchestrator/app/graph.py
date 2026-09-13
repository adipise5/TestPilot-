from __future__ import annotations

from operator import add
from typing import Annotated, Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, RetryPolicy, interrupt

from .models import WorkflowInvocationResponse, WorkflowStartRequest
from .tools import RetryableToolError, WorkflowTools


class WorkflowState(TypedDict, total=False):
    contract_version: int
    test_run_id: int
    project_id: int
    thread_id: str
    graph_version: str
    commit_sha: str
    repository_connected: bool
    source_count: int
    source_paths: list[str]
    build_system: str
    analysis: dict[str, Any]
    codebase_map: dict[str, Any]
    rag_ingestion: dict[str, Any]
    test_plan: list[dict[str, Any]]
    approval_required: bool
    approval_reason: str
    approval_decision: str
    approval_comment: str | None
    test_proposals: Annotated[list[dict[str, Any]], add]
    accepted_proposals: list[dict[str, Any]]
    review: dict[str, Any]
    execution: dict[str, Any]
    triage: list[dict[str, Any]]
    report: dict[str, Any]
    workflow_status: str
    completed_nodes: Annotated[list[str], add]


SPECIALIST_NODES = {
    "UNIT": "unit_test_specialist",
    "MODULE": "module_test_specialist",
    "INTEGRATION": "integration_test_specialist",
}


def retryable(exception: BaseException) -> bool:
    return isinstance(exception, RetryableToolError)


def build_graph(tools: WorkflowTools, checkpointer: Any):
    builder = StateGraph(WorkflowState)
    retry_policy = RetryPolicy(
        max_attempts=3,
        initial_interval=0.25,
        backoff_factor=2.0,
        max_interval=2.0,
        jitter=False,
        retry_on=retryable,
    )

    for node in (
        "intake",
        "codebase_mapper",
        "test_planner",
        "unit_test_specialist",
        "module_test_specialist",
        "integration_test_specialist",
        "test_reviewer",
        "execution_coordinator",
        "failure_triage",
        "report",
    ):
        builder.add_node(node, tool_node(node, tools), retry_policy=retry_policy)
    builder.add_node("human_approval", human_approval)

    builder.add_edge(START, "intake")
    builder.add_edge("intake", "codebase_mapper")
    builder.add_edge("codebase_mapper", "test_planner")
    builder.add_conditional_edges("test_planner", next_specialist)
    builder.add_conditional_edges("unit_test_specialist", next_specialist)
    builder.add_conditional_edges("module_test_specialist", next_specialist)
    builder.add_conditional_edges("integration_test_specialist", next_specialist)
    builder.add_conditional_edges(
        "test_reviewer",
        after_review,
        {"human_approval": "human_approval", "execution_coordinator": "execution_coordinator"},
    )
    builder.add_conditional_edges(
        "human_approval",
        after_approval,
        {"execution_coordinator": "execution_coordinator", "report": "report"},
    )
    builder.add_conditional_edges(
        "execution_coordinator",
        after_execution,
        {"failure_triage": "failure_triage", "report": "report"},
    )
    builder.add_edge("failure_triage", "report")
    builder.add_edge("report", END)
    return builder.compile(checkpointer=checkpointer)


def tool_node(node: str, tools: WorkflowTools):
    def invoke_tool(state: WorkflowState) -> dict[str, Any]:
        updates = dict(tools.invoke(node, dict(state)))
        proposal = updates.pop("proposal", None)
        if proposal is not None:
            updates["test_proposals"] = [proposal]
        updates["completed_nodes"] = [node]
        return updates

    return invoke_tool


def next_specialist(
    state: WorkflowState,
) -> Literal[
    "unit_test_specialist",
    "module_test_specialist",
    "integration_test_specialist",
    "test_reviewer",
]:
    planned = [item["level"] for item in state.get("test_plan", [])]
    completed = set(state.get("completed_nodes", []))
    for level in ("UNIT", "MODULE", "INTEGRATION"):
        node = SPECIALIST_NODES[level]
        if level in planned and node not in completed:
            return node  # type: ignore[return-value]
    return "test_reviewer"


def after_review(
    state: WorkflowState,
) -> Literal["human_approval", "execution_coordinator"]:
    return "human_approval" if state.get("approval_required", False) else "execution_coordinator"


def human_approval(state: WorkflowState) -> dict[str, Any]:
    decision = interrupt(
        {
            "question": state.get(
                "approval_reason",
                "Approve integration tests that require controlled external resources",
            ),
            "testPlan": state.get("test_plan", []),
            "commitSha": state.get("commit_sha"),
        }
    )
    approved = decision.get("approved", False) if isinstance(decision, dict) else bool(decision)
    comment = decision.get("comment") if isinstance(decision, dict) else None
    return {
        "approval_decision": "APPROVED" if approved else "REJECTED",
        "approval_comment": comment,
        "completed_nodes": ["human_approval"],
    }


def after_approval(
    state: WorkflowState,
) -> Literal["execution_coordinator", "report"]:
    return "execution_coordinator" if state.get("approval_decision") == "APPROVED" else "report"


def after_execution(state: WorkflowState) -> Literal["failure_triage", "report"]:
    failed = state.get("execution", {}).get("failed_result_ids", [])
    return "failure_triage" if failed else "report"


class WorkflowRuntime:
    def __init__(self, graph: Any) -> None:
        self._graph = graph

    def start(self, request: WorkflowStartRequest) -> WorkflowInvocationResponse:
        config = self._config(request.thread_id)
        snapshot = self._graph.get_state(config)
        if snapshot.values:
            if snapshot.next and "human_approval" not in snapshot.next:
                result = self._graph.invoke(None, config=config)
                return self._response(
                    request.thread_id,
                    dict(result),
                    self._graph.get_state(config),
                )
            return self._response(request.thread_id, dict(snapshot.values), snapshot)

        initial: WorkflowState = {
            "contract_version": 1,
            "test_run_id": request.test_run_id,
            "project_id": request.project_id,
            "thread_id": request.thread_id,
            "graph_version": request.graph_version,
            "test_proposals": [],
            "completed_nodes": [],
        }
        result = self._graph.invoke(initial, config=config)
        return self._response(request.thread_id, dict(result), self._graph.get_state(config))

    def resume(
        self,
        thread_id: str,
        approved: bool,
        comment: str | None,
    ) -> WorkflowInvocationResponse:
        config = self._config(thread_id)
        snapshot = self._graph.get_state(config)
        if not snapshot.values:
            raise ValueError("Workflow thread does not exist")
        if not snapshot.next:
            return self._response(thread_id, dict(snapshot.values), snapshot)
        result = self._graph.invoke(
            Command(resume={"approved": approved, "comment": comment}),
            config=config,
        )
        return self._response(thread_id, dict(result), self._graph.get_state(config))

    def status(self, thread_id: str) -> WorkflowInvocationResponse:
        config = self._config(thread_id)
        snapshot = self._graph.get_state(config)
        if not snapshot.values:
            raise ValueError("Workflow thread does not exist")
        return self._response(thread_id, dict(snapshot.values), snapshot)

    def _response(
        self,
        thread_id: str,
        state: dict[str, Any],
        snapshot: Any,
    ) -> WorkflowInvocationResponse:
        if "human_approval" in snapshot.next:
            interrupt_value = {
                "question": state.get("approval_reason", "Approval required"),
                "testPlan": state.get("test_plan", []),
                "commitSha": state.get("commit_sha"),
            }
            status = "WAITING_FOR_APPROVAL"
        else:
            interrupt_value = None
            status = state.get("workflow_status", "RUNNING")
        return WorkflowInvocationResponse(
            thread_id=thread_id,
            status=status,
            interrupt=interrupt_value,
            completed_nodes=state.get("completed_nodes", []),
        )

    @staticmethod
    def _config(thread_id: str) -> dict[str, dict[str, str]]:
        return {"configurable": {"thread_id": thread_id}}
