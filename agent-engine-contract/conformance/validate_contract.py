from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parent.parent
CONFORMANCE = ROOT / "conformance"
SCHEMA_DIR = ROOT / "schemas"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def build_registry(schemas: dict[str, dict[str, Any]]) -> Registry:
    registry = Registry()
    for path_name, schema in schemas.items():
        resource = Resource.from_contents(schema)
        registry = registry.with_resource(schema["$id"], resource)
        registry = registry.with_resource((SCHEMA_DIR / path_name).as_uri(), resource)
    return registry


def validator_for(
    schema_name: str,
    schemas: dict[str, dict[str, Any]],
    registry: Registry,
    definition: str | None = None,
) -> Draft202012Validator:
    schema = schemas[schema_name]
    target: dict[str, Any]
    if definition is None:
        target = schema
    else:
        target = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$ref": f"{schema['$id']}#/$defs/{definition}",
        }
    return Draft202012Validator(
        target,
        registry=registry,
        format_checker=Draft202012Validator.FORMAT_CHECKER,
    )


def validate_openapi() -> int:
    document = yaml.safe_load((ROOT / "openapi.yaml").read_text(encoding="utf-8"))
    assert document["openapi"].startswith("3.1."), "OpenAPI 3.1 is required"
    assert document["info"]["version"] == "1.1", "OpenAPI search extension version drifted"
    required_paths = {
        "/v1/tasks",
        "/v1/tasks/{taskId}",
        "/v1/tasks/{taskId}/events",
        "/v1/tasks/{taskId}/cancel",
        "/v1/tasks/{taskId}/answer",
        "/internal/v1/agent-engine/tasks/{taskId}/workspace/files",
        "/internal/v1/agent-engine/tasks/{taskId}/workspace/read",
        "/internal/v1/agent-engine/tasks/{taskId}/sandbox-executions",
        "/internal/v1/agent-engine/tasks/{taskId}/sandbox-executions/{clientRequestId}",
        "/internal/v1/agent-engine/tasks/{taskId}/receipts/{receiptRef}",
        "/internal/v1/agent-engine/tasks/{taskId}/knowledge-searches",
        "/internal/v1/agent-engine/tasks/{taskId}/literature-searches",
    }
    assert required_paths == set(document["paths"]), "OpenAPI path set drifted"

    search_paths = {
        "/internal/v1/agent-engine/tasks/{taskId}/knowledge-searches",
        "/internal/v1/agent-engine/tasks/{taskId}/literature-searches",
    }
    for path in search_paths:
        responses = document["paths"][path]["post"]["responses"]
        for status, response in responses.items():
            if status == "200":
                continue
            assert response == {"$ref": "#/components/responses/ProductSearchProblem"}, (
                f"{path} {status} escaped the closed search error vocabulary"
            )

    operation_count = 0
    for path_item in document["paths"].values():
        for method, operation in path_item.items():
            if method not in {"get", "post", "put", "patch", "delete"}:
                continue
            operation_count += 1
            assert operation.get("security"), f"{operation['operationId']} has no security"

    referenced = re.findall(r"\./schemas/([a-z0-9-]+\.schema\.json)", (ROOT / "openapi.yaml").read_text(encoding="utf-8"))
    assert referenced, "OpenAPI must reference standalone schemas"
    for name in referenced:
        assert (SCHEMA_DIR / name).is_file(), f"missing OpenAPI schema ref: {name}"
    return operation_count


def validate_event_sequence(
    schemas: dict[str, dict[str, Any]], registry: Registry
) -> int:
    events = load_json(CONFORMANCE / "fixtures/positive/events.json")
    validator = validator_for("task-event.schema.json", schemas, registry)
    for event in events:
        validator.validate(event)

    sequences = [event["sequence"] for event in events]
    assert sequences == list(range(1, len(events) + 1)), "event sequence is not contiguous"
    assert len({event["taskId"] for event in events}) == 1, "events cross task identity"
    terminal = [
        event
        for event in events
        if event["type"] == "status"
        and event["state"] in {"succeeded", "failed", "cancelled"}
    ]
    assert len(terminal) == 1, "exactly one terminal status is required"
    if terminal[0]["state"] == "succeeded":
        deliveries = [event for event in events if event["type"] == "delivery"]
        assert len(deliveries) == 1, "success requires exactly one delivery"
        assert deliveries[0]["sequence"] < terminal[0]["sequence"], "delivery must precede success"

    serialized = json.dumps(events, ensure_ascii=False)
    forbidden = ["taskGrant", "Authorization", "DEEPSEEK_API_KEY", "C:/", "C:\\\\", "/home/"]
    for fragment in forbidden:
        assert fragment not in serialized, f"event fixture leaked forbidden fragment: {fragment}"
    return len(events)


def validate_digest() -> None:
    submission = load_json(CONFORMANCE / "fixtures/positive/task-submission.json")
    actual = hashlib.sha256(canonical_json(submission["authority"])).hexdigest()
    assert actual == submission["requestDigest"], "task authority digest mismatch"
    assert "taskGrant" not in submission["authority"], "secret entered digest authority"


def validate_answer_digest() -> None:
    answer = load_json(CONFORMANCE / "fixtures/positive/task-answer.json")
    actual = hashlib.sha256(answer["answer"].encode("utf-8")).hexdigest()
    assert actual == answer["answerDigest"], "answer digest mismatch"


def validate_product_search_digests() -> int:
    cases = [
        (
            "knowledge-search",
            "gateway-knowledge-search-request.json",
            "gateway-knowledge-search-response.json",
            ("query", "maxResults"),
        ),
        (
            "literature-search",
            "gateway-literature-search-request.json",
            "gateway-literature-search-response.json",
            ("query", "maxResults", "yearFrom"),
        ),
    ]
    for case_name, request_name, response_name, digest_fields in cases:
        request = load_json(CONFORMANCE / "fixtures/positive" / request_name)
        response = load_json(CONFORMANCE / "fixtures/positive" / response_name)
        authority = {field: request[field] for field in digest_fields}
        canonical = canonical_json(authority)
        actual = hashlib.sha256(canonical).hexdigest()
        assert actual == request["requestDigest"], f"{case_name} request digest mismatch"
        assert actual == response["requestDigest"], f"{case_name} response digest mismatch"
        assert request["clientRequestId"] == response["clientRequestId"], f"{case_name} client request binding mismatch"
        assert request["query"] == response["query"], f"{case_name} query binding mismatch"
        assert request["maxResults"] == response["maxResults"], f"{case_name} result-limit binding mismatch"
        assert len(response["results"]) <= request["maxResults"], f"{case_name} returned too many results"
        if "yearFrom" in request:
            assert request["yearFrom"] == response["yearFrom"], f"{case_name} year binding mismatch"
        assert response["replayed"] is False, f"{case_name} first response must not claim replay"
        canonical_fixture = (CONFORMANCE / "canonical" / f"{case_name}.canonical.json").read_bytes().rstrip(b"\r\n")
        expected_fixture_digest = (CONFORMANCE / "canonical" / f"{case_name}.sha256").read_text(encoding="ascii").strip()
        assert canonical == canonical_fixture, f"{case_name} canonical fixture mismatch"
        assert actual == expected_fixture_digest, f"{case_name} cross-language digest fixture mismatch"
        serialized = json.dumps(response, ensure_ascii=False)
        forbidden = ["taskGrant", "Authorization", "api_key", "apiKey", "file://", "127.0.0.1", "localhost", "sourceFailures", "Exception"]
        for fragment in forbidden:
            assert fragment not in serialized, f"{case_name} response leaked forbidden fragment: {fragment}"
    return len(cases)


def validate_scenarios() -> int:
    scenarios = load_json(CONFORMANCE / "scenarios.json")
    ids = [row["id"] for row in scenarios["requiredScenarios"]]
    required = {
        "submit-exact-replay",
        "submit-digest-conflict",
        "sse-resume",
        "sandbox-exact-replay",
        "sandbox-digest-conflict",
        "restart-after-receipt",
        "cancel-idempotent",
        "answer-exact-replay",
        "answer-content-conflict",
        "fixed-model-budget",
        "fixed-sandbox-polling",
        "fixed-sse-heartbeat",
        "task-grant-boundary",
        "event-redaction",
        "knowledge-search-scope",
        "product-search-exact-replay",
        "product-search-digest-conflict",
        "product-search-grant-boundary",
        "product-search-cross-user-denied",
        "product-search-bounds",
        "literature-upstream-redaction",
        "product-search-event-redaction",
    }
    assert len(ids) == len(set(ids)), "duplicate conformance scenario id"
    assert set(ids) == required, "required conformance scenario set drifted"
    return len(ids)


def main() -> int:
    schemas = {
        path.name: load_json(path)
        for path in sorted(SCHEMA_DIR.glob("*.schema.json"))
    }
    assert schemas, "no schemas found"
    for schema in schemas.values():
        Draft202012Validator.check_schema(schema)
    registry = build_registry(schemas)

    manifest = load_json(CONFORMANCE / "manifest.json")
    positive_count = 0
    for row in manifest["positive"]:
        instance = load_json(CONFORMANCE / row["file"])
        validator_for(
            row["schema"], schemas, registry, row.get("definition")
        ).validate(instance)
        positive_count += 1

    negative_count = 0
    for row in manifest["negative"]:
        instance = load_json(CONFORMANCE / row["file"])
        errors = list(
            validator_for(
                row["schema"], schemas, registry, row.get("definition")
            ).iter_errors(instance)
        )
        assert errors, f"negative fixture unexpectedly passed: {row['file']}"
        negative_count += 1

    operation_count = validate_openapi()
    event_count = validate_event_sequence(schemas, registry)
    validate_digest()
    validate_answer_digest()
    digest_case_count = validate_product_search_digests()
    scenario_count = validate_scenarios()

    print(
        "contract validation passed: "
        f"{len(schemas)} schemas, {operation_count} operations, "
        f"{positive_count} positive fixtures, {negative_count} negative fixtures, "
        f"{event_count} ordered events, {digest_case_count} product-search digests, "
        f"{scenario_count} runtime scenarios"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"contract validation failed: {exc}", file=sys.stderr)
        raise
