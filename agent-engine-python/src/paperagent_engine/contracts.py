"""Phase-one contracts, deliberately distinct from the production /v1 contract."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]
Key = Annotated[str, StringConstraints(pattern=r"^[a-zA-Z0-9_-]{1,64}$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class TaskFrame(StrictModel):
    objective: Text
    objects: list[Text] = Field(min_length=1, max_length=8)
    deliverables: list[Text] = Field(min_length=1, max_length=8)
    constraints: list[Text] = Field(default_factory=list, max_length=8)
    project_version: Key
    permission_tier: Literal["READ_ONLY"] = "READ_ONLY"
    run_mode: Literal["PERSISTENT_PLAN_EXECUTE"] = "PERSISTENT_PLAN_EXECUTE"


class Document(StrictModel):
    id: Key
    text: str = Field(min_length=1, max_length=12000)


class Memory(StrictModel):
    id: Key
    content: Text


class Submission(StrictModel):
    protocol: Literal["python-dev/1"] = "python-dev/1"
    client_request_id: Key
    frame: TaskFrame
    documents: list[Document] = Field(min_length=1, max_length=8)
    memories: list[Memory] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def unique_ids(self):
        for values in (self.documents, self.memories):
            if len({v.id for v in values}) != len(values):
                raise ValueError("duplicate object identifiers")
        return self


class Step(StrictModel):
    key: Key
    objective: Text
    done_when: Text
    depends_on: list[Key] = Field(default_factory=list, max_length=5)


class Plan(StrictModel):
    steps: list[Step] = Field(min_length=1, max_length=5)

    @model_validator(mode="after")
    def serial_dependencies(self):
        seen = set()
        for step in self.steps:
            if step.key in seen or not set(step.depends_on) <= seen:
                raise ValueError("steps must have unique keys and ordered acyclic dependencies")
            seen.add(step.key)
        return self


class StepResult(StrictModel):
    kind: Literal["complete", "replan"]
    summary: Text
    evidence_refs: list[Key] = Field(default_factory=list, max_length=16)


class ToolCall(StrictModel):
    name: Key
    arguments: dict


class Action(StrictModel):
    call: ToolCall | None = None
    result: StepResult | None = None

    @model_validator(mode="after")
    def exactly_one(self):
        if (self.call is None) == (self.result is None):
            raise ValueError("exactly one call or result is required")
        return self


class EngineError(Exception):
    def __init__(self, code: str, status: int = 409):
        self.code = code
        self.status = status
        super().__init__(code)
