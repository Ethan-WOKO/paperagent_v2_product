package com.yanban.knowledge.service;

import java.io.IOException;
import java.io.InputStream;

/** The caller owns the stream and must close it when transfer ends. */
public record KnowledgeDocumentDownload(String filename, InputStream stream) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
