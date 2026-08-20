package com.yanban.knowledge.eval;

/** Product-oriented rerank intents. API_DEFAULT exists only as the frozen comparison baseline. */
public enum RetrievalIntent {
    API_DEFAULT(null),
    GENERAL_RESEARCH("""
            Given a user's research task, rank passages by their direct usefulness for answering or completing the task. \
            Prefer passages containing explicit facts, definitions, methods, results, or evidence over passages that are \
            only topically related. Preserve important entities, numbers, and constraints."""),
    QUESTION_ANSWERING("""
            Given a research question, rank passages by how directly and completely they answer it. Prefer explicit, \
            source-grounded answers over passages that only mention the topic."""),
    CLAIM_EVIDENCE("""
            Given a scientific claim, rank abstracts by whether they provide direct evidence that can verify, support, \
            or refute it. Prefer direct experimental evidence over general topical similarity."""),
    METHOD_RESULT("""
            Given a research task, rank passages by whether they directly describe the requested method, experimental \
            setup, measured result, or conclusion. Prefer operational details and reported findings over background text.""");

    private final String instruction;

    RetrievalIntent(String instruction) {
        this.instruction = instruction;
    }

    public String instruction() {
        return instruction;
    }
}
