"""Read-only Java gateway adapter; product credentials never enter graph state."""

import json
from typing import Any

import httpx
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import StructuredTool
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import Field, field_validator

from .contracts import EngineError, StrictModel
from .storage import digest


class ProductGateway:
    def __init__(self, origin, service_token, client=None):
        if origin not in {"http://127.0.0.1:8080", "http://localhost:8080"}:
            raise ValueError("Product gateway must be the local Java service on port 8080")
        self.origin = origin
        self.service_token = service_token
        self.client = client or httpx.Client(timeout=35, follow_redirects=False)
        self.grants = {}

    def call(self, path, body=None, task_id=None):
        token = self.grants.get(task_id) if task_id else self.service_token
        if not token:
            raise EngineError("TASK_GRANT_REQUIRED", 401)
        try:
            response = self.client.request(
                "GET" if body is None else "POST",
                self.origin + path,
                json=body,
                headers={"Authorization": "Bearer " + token},
            )
        except httpx.HTTPError:
            raise EngineError("PRODUCT_GATEWAY_UNAVAILABLE", 502) from None
        if response.status_code >= 300:
            raise EngineError("PRODUCT_GATEWAY_REJECTED", response.status_code)
        return response.json()

    def state(self, suffix, body=None):
        return self.call("/internal/v1/agent-engine/task-state" + suffix, body)

    def tool(self, task_id, suffix, body=None):
        return self.call(f"/internal/v1/agent-engine/tasks/{task_id}" + suffix, body, task_id)


class GatewayChatModel(BaseChatModel):
    gateway: Any = Field(exclude=True)
    task_id: str
    call_id: str
    route: dict

    @property
    def _llm_type(self):
        return "paperagent-product-gateway"

    @property
    def _identifying_params(self):
        return {"model": self.route["model"], "provider": self.route["provider"]}

    def bind_tools(self, tools, **kwargs):
        return self.bind(tools=[convert_to_openai_tool(tool) for tool in tools])

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        tools = kwargs.get("tools", [])
        projected = []
        for message in messages:
            role = {"human": "user", "system": "system", "ai": "assistant", "tool": "tool"}[
                message.type
            ]
            item = {"role": role, "content": message.content}
            if message.type == "tool":
                item["toolCallId"] = message.tool_call_id
            projected.append(item)
        if tools:
            projected.insert(
                0,
                {
                    "role": "system",
                    "content": "Return exactly one tool call using the supplied function schemas. For structured output, call the schema function; do not return prose.",
                },
            )
        payload = {
            "contractVersion": "1.0",
            "clientRequestId": self.call_id,
            "provider": self.route["provider"],
            "model": self.route["model"],
            "messages": projected,
            "tools": tools,
            "maxOutputTokens": 4096,
        }
        payload["requestDigest"] = digest(payload)
        result = self.gateway.tool(self.task_id, "/model-completions", payload)
        if (
            result.get("clientRequestId") != self.call_id
            or result.get("requestDigest") != payload["requestDigest"]
        ):
            raise EngineError("MODEL_RESPONSE_IDENTITY_INVALID")
        calls = [
            {
                "id": call["id"],
                "name": call["name"],
                "args": json.loads(call["arguments"]),
                "type": "tool_call",
            }
            for call in result["toolCalls"]
        ]
        return ChatResult(
            generations=[
                ChatGeneration(
                    message=AIMessage(content=result.get("content") or "", tool_calls=calls)
                )
            ]
        )


class Empty(StrictModel):
    pass


class Read(StrictModel):
    path: str = Field(min_length=1, max_length=512)
    expectedSha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    documentCursor: str | None = Field(default=None, max_length=100)
    offset: int = Field(default=0, ge=0, le=10_000_000)

    @field_validator("path")
    @classmethod
    def relative_path(cls, value):
        if (
            value.startswith("/")
            or any(c in value for c in ("\\", "\x00", ":"))
            or any(p in {"", ".", ".."} for p in value.split("/"))
        ):
            raise ValueError("Expected normalized relative path")
        return value


class GatewayTools:
    def __init__(self, gateway, task_id, authority):
        def listing():
            result = gateway.tool(task_id, "/workspace/files")
            if (
                result.get("taskId") != task_id
                or result.get("projectVersion") != authority["project"]["projectVersion"]
            ):
                raise EngineError("PROJECT_VERSION_MISMATCH")
            # Fail explicitly on too large a listing rather than silently losing paths.
            if len(result["files"]) > 100:
                raise EngineError("PYTHON_MANIFEST_LIMIT")
            return {"files": result["files"]}

        def reading(path, expectedSha256, documentCursor=None, offset=0):
            body = {"contractVersion": "1.0", "path": path, "expectedSha256": expectedSha256}
            if documentCursor:
                body["documentCursor"] = documentCursor
            result = gateway.tool(task_id, "/workspace/read", body)
            if result.get("path") != path or result.get("sha256") != expectedSha256:
                raise EngineError("PROJECT_CONTENT_MISMATCH")
            content = result.get("content", "")
            # Structured document cursors live inside FileRead.content.summary.
            # First page through every character of that response before advancing
            # the document cursor, so a bounded model observation cannot skip text.
            next_cursor = None
            try:
                structured = json.loads(content)
                if (
                    isinstance(structured, dict)
                    and structured.get("tool") == "project.document.extract"
                ):
                    next_cursor = structured.get("summary", {}).get("nextCursor")
            except (ValueError, TypeError):
                pass
            if offset > len(content):
                raise EngineError("READ_OFFSET_OUT_OF_RANGE")
            next_offset = offset + 6000 if len(content) > offset + 6000 else None
            return {
                "path": path,
                "sha256": expectedSha256,
                "content": content[offset : offset + 6000],
                "truncated": bool(result.get("truncated")) or next_offset is not None,
                "nextOffset": next_offset,
                "nextDocumentCursor": next_cursor if next_offset is None else None,
            }

        self.tools = [
            StructuredTool.from_function(
                listing,
                name="list_project_files",
                description="List the frozen Project files and exact hashes. Call before reading files.",
                args_schema=Empty,
            ),
            StructuredTool.from_function(
                reading,
                name="read_project_file",
                description="Read a frozen Project file with its manifest hash. Follow nextOffset using the same documentCursor, then follow nextDocumentCursor with offset=0. Report any truncation without a continuation; do not claim unseen content was checked.",
                args_schema=Read,
            ),
        ]
        skill = authority.get("skill")
        if skill:
            self.tools = [t for t in self.tools if t.name in skill["allowedTools"]]

    def invoke(self, name, arguments):
        tool = next((tool for tool in self.tools if tool.name == name), None)
        if tool is None:
            return {"success": False, "output": None, "error": "TOOL_NOT_ALLOWED"}
        try:
            args = tool.args_schema.model_validate(arguments)
        except ValueError:
            return {"success": False, "output": None, "error": "TOOL_ARGUMENTS_INVALID"}
        return {"success": True, "output": tool.invoke(args.model_dump()), "error": None}
