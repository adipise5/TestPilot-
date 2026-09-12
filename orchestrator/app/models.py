from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ContractModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class WorkflowStartRequest(ContractModel):
    contract_version: int = Field(ge=1, le=1)
    test_run_id: int = Field(gt=0)
    project_id: int = Field(gt=0)
    thread_id: str = Field(min_length=1, max_length=100, pattern=r"^[a-zA-Z0-9._-]+$")
    graph_version: str = Field(min_length=1, max_length=40)


class WorkflowResumeRequest(ContractModel):
    contract_version: int = Field(ge=1, le=1)
    approved: bool
    comment: str | None = Field(default=None, max_length=500)


class WorkflowInvocationResponse(ContractModel):
    contract_version: int = 1
    thread_id: str
    status: str
    interrupt: dict | None = None
    completed_nodes: list[str] = Field(default_factory=list)
