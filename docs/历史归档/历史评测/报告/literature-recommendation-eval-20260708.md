# Literature Recommendation Evaluation Report - 2026-07-08

> Generated from: `yanban-paper/target/literature-recommendation-eval/report.md`
>
> Related current roadmap: `docs/design/paper-agent-next-steps-roadmap.md` updated on 2026-07-07.

## 1. Conclusion

The current literature recommendation implementation has a usable first-pass evaluation baseline.

Result:

```text
average_score: 4.445 / 5
case_count: 10
minimum_case_score: 4.133 / 5
all_cases_passed_threshold: true
```

This supports continuing the current direction:

1. keep extracting literature recommendation into an independent capability,
2. keep using a dated evaluation report for every ranking or prompt change,
3. do not move on to paper-polish UI or Project work until this capability is cleanly documented and merged.

## 2. Verification Command

Executed on 2026-07-08:

```powershell
mvn --% -pl yanban-paper -am -Dtest=LiteratureRecommendationServiceTest,LiteratureRecommendationEvaluationTest,LiteratureServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Result:

```text
BUILD SUCCESS
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

The same run generated:

```text
yanban-paper/target/literature-recommendation-eval/report.json
yanban-paper/target/literature-recommendation-eval/report.md
```

## 3. Evaluation Method

The evaluation uses fixed golden cases.

For each case:

1. define a literature query,
2. define the expected discipline and citation need,
3. define a golden ranked list,
4. run the project literature recommendation pipeline,
5. score final-hit recall, top-30 recall, and ranking closeness.

The fixture covers 10 areas:

| Case | Area | Score |
| --- | --- | ---: |
| LIT-01 | Radar / MIMO radar | 4.621 |
| LIT-02 | Radar / SAR automatic target recognition | 4.188 |
| LIT-03 | Radar / automotive mmWave radar perception | 4.242 |
| LIT-04 | Radar / integrated sensing and communication | 4.567 |
| LIT-05 | Radar / mmWave human sensing | 4.756 |
| LIT-06 | Medical image segmentation | 4.133 |
| LIT-07 | Climate / remote-sensing precipitation nowcasting | 4.215 |
| LIT-08 | Materials science / property prediction | 4.269 |
| LIT-09 | Bioinformatics / protein structure prediction | 5.000 |
| LIT-10 | NLP / RAG evaluation | 4.458 |

## 4. What This Proves

The current implementation can:

1. generate or normalize academic search queries,
2. merge local literature-card hits with external source candidates,
3. deduplicate by DOI / arXiv / OpenAlex / Semantic Scholar / title hash identity,
4. produce explainable ranking diagnostics,
5. mark already-present papers from existing BibTeX,
6. produce BibTeX output for recommendations,
7. reuse the same recommendation path from paper-task literature retrieval.

Relevant code under current working tree:

1. `yanban-paper/src/main/java/com/yanban/paper/literature/LiteratureRecommendationService.java`
2. `yanban-api/src/main/java/com/yanban/api/agent/RecommendLiteratureToolExecutor.java`
3. `yanban-api/src/main/java/com/yanban/api/agent/AgentLangChain4jTools.java`
4. `yanban-paper/src/main/java/com/yanban/paper/literature/LiteratureService.java`

## 5. Remaining Risks

This report is a baseline, not a final product-quality guarantee.

Remaining risks:

1. The golden corpus is deterministic and fixture-backed; it does not yet prove quality against live OpenAlex / arXiv responses.
2. Some cases have perfect recall but only medium ranking closeness. The weakest current score is LIT-06 at 4.133 / 5.
3. The generated report shows some off-topic papers still appearing in the final or preview list, especially when generic deep-learning terms overlap across domains.
4. The evaluation validates retrieval/ranking behavior, not user-facing frontend presentation.
5. The current working tree still has uncommitted frontend changes and backup files that need separate review before merge.

## 6. Recommended Next Action

Before moving to paper polish diff / LaTeX compile validation:

1. decide whether the current frontend changes belong to this literature recommendation task,
2. keep or remove `.bak` frontend files intentionally,
3. run the `yanban-api` focused tests for `recommend_literature` registration and LangChain4j tool binding,
4. add this report to the implementation summary or PR notes,
5. then treat the 2026-07-08 score as the first baseline for future reranking changes.

