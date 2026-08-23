package com.yanban.knowledge.service;

public class KnowledgePermanentProcessingException extends RuntimeException {
    public KnowledgePermanentProcessingException(String message) { super(message); }
    public KnowledgePermanentProcessingException(String message, Throwable cause) { super(message, cause); }
}
