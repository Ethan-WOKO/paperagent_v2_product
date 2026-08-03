# Knowledge Base Evaluation Fixtures

This directory documents the fixed facts used by the pre-launch evaluation scripts.

`run-local-eval.ps1` generates run-specific fixture files under `docs/测试资产/运行结果/fixtures-<runId>/` so every run can use unique lookup keys and avoid matching stale documents from earlier runs.

Fixture modes:

| Mode | Uploaded files |
|---|---|
| `markdown` | Three Markdown files generated from the fixed fact templates |
| `mixed` | One PDF, one DOCX, and one Markdown file generated from the same fixed fact templates |

The generated fixtures contain these stable fact types:

| Fact | Expected answer |
|---|---|
| Mentor name | `Zhang Mingyuan` |
| Mentor phone | Not documented in the knowledge base |
| Weekly meeting | `Wednesday 14:00` |
| Phase A default model | `DeepSeek` |
| max_steps range | Default `20`, settings range `5` to `100` |
| Final paper workflow step | `OpenAlex literature recommendation export` |
| Priority file types | `PDF`, `DOCX`, `Markdown` |

The PDF and DOCX files are intentionally minimal but valid real formats. They are test fixtures for Apache Tika extraction and RAG indexing, not polished user-facing documents.
