# Evidence-Aware Introduction Polish Prompt

You improve the Introduction while preserving LaTeX placeholders and respecting citation evidence.

Report/UI language: {{targetLanguage}}
Paper title: {{paperTitle}}
Research profile:
{{researchProfile}}

Compact whole-paper context for this section:
{{sectionContext}}

Introduction analysis, citation slots, citation audit, and retrieval diagnostics:
{{conceptLadder}}

Attempt: {{attemptIndex}} / {{maxAttempts}}
Review comments from previous attempt:
{{reviewComments}}

Rules:
- Improve the Introduction's argument structure: background → related work categories → gap → contribution, while keeping the original contribution scope.
- Preserve the Introduction's original writing language. Do not translate an English paper/section into Chinese even if the report/UI language is zh.
- Preserve correct user claims and correct existing citations.
- Do not delete or change existing citation placeholders unless the citation audit clearly indicates the cited context is weak or mismatched.
- Do not invent new citation keys, papers, experiments, numeric results, unsupported claims, or new contribution bullets.
- If evidence is insufficient, narrow the claim instead of making it stronger.
- Do not strengthen certainty, severity, causality, novelty, or generality beyond the original wording and cited evidence.
- Do not perform synonym-only rewrites; retain wording that is already clear and academically appropriate.
- Avoid mechanical or inflated phrases when a shorter direct form is accurate.
- Preserve every placeholder exactly as given. You may move placeholders, but must not create new placeholders.
- Do not add, delete, rename, or reorder LaTeX labels, refs, cites, section/subsection headings, equations, figures, tables, algorithms, environments, or bibliography commands.
- Do not introduce bullet lists unless a bullet list already exists in the original Introduction.
- Do not introduce new mathematical models, new optimization problems, new variables, or new unlabeled display equations. Polish the existing prose only.
- Return only the two tags below.
- If review comments list missing or unexpected placeholders or protected commands, restore the exact listed tokens before making any prose changes.

Introduction text:
{{sectionText}}

<output>polished introduction text here</output>
<explanation>short explanation of which claims were preserved, narrowed, or reorganized</explanation>
