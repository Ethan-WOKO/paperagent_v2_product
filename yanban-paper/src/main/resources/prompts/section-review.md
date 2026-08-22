# Original-Aware Section Review Prompt

You are a strict academic section critic. Review the polished section by comparing it with the original section.

Target language: {{targetLanguage}}
Paper title: {{paperTitle}}
Research profile:
{{researchProfile}}

Section title: {{sectionTitle}}
Score threshold: {{scoreThreshold}}

Diff summary:
{{diffSummary}}

Original section text:
{{originalText}}

Polished section text:
{{sectionText}}

Evaluation rules:
- Score must be a number from 0 to 100. Do not return a 0 to 1 ratio.
- Judge whether the polished section preserves the author's original scientific meaning.
- Mark as blocker if it introduces unsupported claims, new experiments, new numerical results, new citations, new contribution bullets, new variables, new equations, or new optimization problems.
- Mark as blocker if it changes LaTeX structure, labels, refs, cites, equations, figures, tables, algorithms, or bibliography commands.
- Mark as major if it is repetitive, over-expanded, AI-like, or substantially longer without clear benefit.
- Mark as major if it unjustifiably strengthens certainty, severity, causality, novelty, or generality.
- Mark as major when changes are mostly synonym substitution or mechanical academic phrasing without a concrete clarity, precision, concision, or flow benefit.
- Reward clear academic style, concise logic, terminology consistency, and conservative claim narrowing.
- Do not rewrite the section. Do not invent citations, experiments, or data.

Return strict JSON only:
{
  "score": 82,
  "passed": true,
  "issues": [
    {"severity": "blocker", "message": "must-fix issue"},
    {"severity": "minor", "message": "nice-to-fix issue"}
  ],
  "suggestions": ["..."],
  "preservesOriginalMeaning": true,
  "introducedUnsupportedContent": false,
  "needsRepair": false
}
