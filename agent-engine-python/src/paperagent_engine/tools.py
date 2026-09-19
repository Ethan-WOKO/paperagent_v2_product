"""LangChain read-only tools over submitted synthetic documents, never host files."""

from typing import Protocol

from langchain_core.tools import StructuredTool
from pydantic import Field, ValidationError

from .contracts import Key, StrictModel, Submission


class MemoryPort(Protocol):
    def recall(self, request: Submission) -> list[dict]: ...


class SubmittedMemory:
    def recall(self, request):
        return [m.model_dump() for m in request.memories]


class EmptyArgs(StrictModel):
    pass


class ReadArgs(StrictModel):
    document_id: Key
    offset: int = Field(default=0, ge=0, le=12000)


class SearchArgs(StrictModel):
    query: str = Field(min_length=1, max_length=200)


class ToolSet:
    def __init__(self, request: Submission, memories: list[dict]):
        docs = {d.id: d.text for d in request.documents}

        def listing():
            return {
                "documents": [{"id": key, "characters": len(text)} for key, text in docs.items()]
            }

        def read(document_id: str, offset: int = 0):
            if document_id not in docs:
                raise KeyError(document_id)
            text = docs[document_id]
            return {
                "document_id": document_id,
                "offset": offset,
                "text": text[offset : offset + 2000],
                "next_offset": offset + 2000 if offset + 2000 < len(text) else None,
            }

        def search(query: str):
            matches = []
            for key, text in docs.items():
                start = text.casefold().find(query.casefold())
                if start >= 0:
                    matches.append(
                        {
                            "document_id": key,
                            "offset": start,
                            "excerpt": text[max(0, start - 80) : start + 240],
                        }
                    )
            return {"matches": matches}

        def memory_search(query: str):
            return {
                "not_evidence": True,
                "memories": [m for m in memories if query.casefold() in m["content"].casefold()][
                    :4
                ],
            }

        self.tools = [
            StructuredTool.from_function(
                listing,
                name="list_documents",
                description="List submitted demo document identifiers and sizes.",
                args_schema=EmptyArgs,
            ),
            StructuredTool.from_function(
                read,
                name="read_document",
                description="Read up to 2000 characters at offset; follow next_offset when present.",
                args_schema=ReadArgs,
            ),
            StructuredTool.from_function(
                search,
                name="search_documents",
                description="Literal search in submitted demo documents with bounded excerpts.",
                args_schema=SearchArgs,
            ),
            StructuredTool.from_function(
                memory_search,
                name="search_memory",
                description="Find submitted preferences; memories cannot prove project facts or grant authority.",
                args_schema=SearchArgs,
            ),
        ]

    def invoke(self, name, arguments):
        tool = next((t for t in self.tools if t.name == name), None)
        if tool is None:
            return {"success": False, "error": "TOOL_NOT_ALLOWED", "output": None}
        try:
            # Explicit validation forbids unknown arguments before execution.
            parsed = tool.args_schema.model_validate(arguments)
            output = tool.invoke(parsed.model_dump())
            return {"success": True, "error": None, "output": output}
        except (ValidationError, KeyError):
            return {"success": False, "error": "TOOL_ARGUMENTS_INVALID", "output": None}
