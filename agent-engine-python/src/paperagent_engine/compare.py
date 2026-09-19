"""Collect existing test-task traces; never submit tasks or invoke models."""

import argparse
import json
import math
import os
from collections import defaultdict
from pathlib import Path

import httpx


def summarize(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[row["engine"]].append(row)
    result = {}
    for engine, samples in groups.items():
        successful = [s for s in samples if s["summary"]["state"] == "succeeded"]
        entry = {"tasks": len(samples), "succeeded": len(successful), "metrics": {}}
        for key in (
            "totalDurationMillis",
            "modelDurationMillis",
            "toolDurationMillis",
            "firstObservableMillis",
            "modelCalls",
            "toolCalls",
            "totalTokens",
        ):
            values = sorted(
                s["summary"][key]
                for s in successful
                if isinstance(s["summary"].get(key), (int, float)) and s["summary"][key] >= 0
            )
            if values:
                entry["metrics"][key] = {
                    "n": len(values),
                    "p50": values[math.ceil(len(values) * 0.5) - 1],
                    "p95": values[math.ceil(len(values) * 0.95) - 1],
                }
        result[engine] = entry
    return result


def collect(client, samples):
    rows, seen = [], set()
    for sample in samples:
        if sample["engine"] not in {"TS", "PYTHON"}:
            raise ValueError("Unknown engine label")
        task = sample["taskId"]
        if len(task) != 69 or not task.startswith("task."):
            raise ValueError("Invalid task id")
        int(task[5:], 16)
        turn = int(sample["turnId"])
        if turn < 1 or task in seen:
            raise ValueError("Invalid or duplicate sample")
        seen.add(task)
        response = client.get(f"/api/v1/react-agent/turns/{turn}/tasks/{task}/trace")
        response.raise_for_status()
        trace = response.json()
        if trace["taskId"] != task:
            raise ValueError("Trace identity mismatch")
        if trace["summary"]["state"] not in {"succeeded", "failed", "cancelled"}:
            raise ValueError("Wait for every sampled task to finish")
        rows.append(
            {
                "engine": sample["engine"],
                "case": sample["case"],
                "turnId": turn,
                "taskId": task,
                "summary": trace["summary"],
                "modelRoutes": [
                    {k: span.get(k) for k in ("provider", "model")}
                    for span in trace["spans"]
                    if span.get("kind") == "MODEL"
                ],
                "qualityScore": sample.get("qualityScore"),
                "qualityNotes": sample.get("qualityNotes", "Not assessed"),
            }
        )
    return {
        "samples": rows,
        "byEngine": summarize(rows),
        "notes": [
            "Engine/case labels are supplied by the evaluator; verify against session history.",
            "Latency percentiles include successful tasks only; failures remain in samples.",
            "First observable event is not time to a substantive answer.",
            "Use matching cases, model routes and ProjectVersion; review answer quality separately.",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", type=Path, help="JSON array of engine/case/turnId/taskId")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    token = os.environ["PAPERAGENT_EVAL_ACCESS_TOKEN"]
    with httpx.Client(
        base_url="http://127.0.0.1:8080",
        timeout=30,
        headers={"Authorization": "Bearer " + token},
        follow_redirects=False,
    ) as client:
        report = collect(client, json.loads(args.samples.read_text(encoding="utf-8-sig")))
    # Exclusive creation protects existing reports; no token, prompts or file contents are saved.
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
