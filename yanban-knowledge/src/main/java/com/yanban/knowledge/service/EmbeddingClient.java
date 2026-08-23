package com.yanban.knowledge.service;

import java.util.List;

public interface EmbeddingClient {
    List<Double> embed(String text);

    default List<List<Double>> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
