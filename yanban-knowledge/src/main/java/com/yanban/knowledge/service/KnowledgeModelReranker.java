package com.yanban.knowledge.service;

import java.util.List;

public interface KnowledgeModelReranker {

    boolean available();

    List<KnowledgeSearchResult> rerank(String query, List<KnowledgeSearchResult> candidates, int topK);
}
