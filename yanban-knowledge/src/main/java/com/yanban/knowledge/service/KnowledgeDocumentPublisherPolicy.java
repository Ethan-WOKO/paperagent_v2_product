package com.yanban.knowledge.service;

import java.util.Set;

/** Product identity bridge for file sharing only; not a retrieval policy. */
public interface KnowledgeDocumentPublisherPolicy {
    Set<Long> administratorIds();

    boolean isAdministrator(Long userId);
}
