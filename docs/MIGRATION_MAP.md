# V2 Capability Migration Map

## Literature search task start

- Status: `REUSE_WITH_ADAPTER`
- Assessed product entry: `LiteratureSearchStartToolExecutor`
- V2 tool identity: `literature.search`
- Product tool identity: `literature_search_start`
- Authority: authenticated Agent turn, recovered ACTIVE Step, persisted
  EffectIntent, and current fenced lease; model arguments provide no identity
  or permission authority.
- Adaptation: permit only query, bounded `topK`, bounded `yearFrom`, and
  `includeBibtex`; derive `clientRequestId` from ToolCallId; inject user and
  optional Project identity from verified product state.
- Atomicity: a unique V2 execution claim, the product literature task, its
  ExecutionReceipt, and EffectOutcome commit in one product database
  transaction. A committed result is replay-only.
- Excluded: legacy Agent planning/verification/loop services, literature
  status/result/cancel, live retrieval, and generic tool execution.
