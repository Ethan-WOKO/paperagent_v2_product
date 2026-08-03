# Paper Quality Baseline Evaluation Report - 2026-07-09

> Related issue: #94 evaluation baseline for literature search and paper polish quality checks.

## 1. Conclusion

This is the first repeatable baseline for literature recommendation and paper polish quality checks.

The baseline is intentionally conservative:

1. it checks that literature recommendation does not regress against fixed golden rankings;
2. it checks that paper polish does not break citations, references, labels, math, figures, environments, or obvious language boundaries;
3. it does not claim to automatically judge all academic writing quality.

## 2. Verification Command

Run from the repository root:

```powershell
docs/evaluation/run-paper-quality-baseline-eval.ps1
```

Equivalent focused Maven command:

```powershell
mvn --% -pl yanban-paper -am -Dtest=LiteratureRecommendationEvaluationTest,PaperPolishBaselineEvaluationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected generated outputs:

```text
yanban-paper/target/literature-recommendation-eval/report.json
yanban-paper/target/literature-recommendation-eval/report.md
yanban-paper/target/paper-polish-baseline-eval/report.json
yanban-paper/target/paper-polish-baseline-eval/report.md
```

Latest local run on 2026-07-09:

```text
BUILD SUCCESS
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0

Literature baseline:
- averageScore: 4.427 / 5
- caseCount: 10

Paper polish baseline:
- caseCount: 3
- statusCounts: POLISHED=2, FAILED_KEEP_ORIGINAL=1
```

## 3. Fixed Samples

Literature recommendation uses the existing deterministic golden corpus in `LiteratureRecommendationEvaluationTest`.

Paper polish uses existing LaTeX samples:

| Case | Sample | Purpose |
| --- | --- | --- |
| POLISH-EN-001 | `en-literature-gap/main.tex` | English section polish must preserve `\cite`, `\ref`, and section structure. |
| POLISH-ZH-001 | `zh-rag-polish/main.tex` | Chinese sample must preserve citation, figure, label, ref, math, and graphics spans. |
| POLISH-NEG-001 | `en-literature-gap/main.tex` | Unsafe output that drops protected placeholders must keep the original section. |

## 4. Mandatory Checks

Paper polish baseline requires:

1. protected LaTeX commands are preserved;
2. no blocker LaTeX lint issues are introduced;
3. required citation/ref/label/math/figure tokens remain present;
4. unsafe placeholder or structure changes are rejected;
5. failed unsafe polish does not replace the original text.

Observed quality metrics are recorded but are not the only pass/fail signal:

1. status distribution;
2. review score;
3. attempts;
4. changed flag;
5. length delta.

## 5. Manual Inspection Boundary

These evals are baseline regression checks, not full academic peer review.

Manual review is still required for:

1. contribution novelty;
2. discipline-specific rhetoric;
3. correctness of literature interpretation;
4. whether a citation is truly suitable for a claim;
5. final publication readiness.

## 6. Current Risks

1. Literature recommendation is fixture-backed; live OpenAlex/arXiv quality is not a required gate.
2. Paper polish uses a deterministic model stub; it validates guardrails and reportability, not live model fluency.
3. The current sample set is intentionally small and does not cover all disciplines or complex LaTeX templates.
4. Generated reports under `target/` are runtime artifacts and should be regenerated for each PR that changes ranking, prompt, parser, or polish behavior.
