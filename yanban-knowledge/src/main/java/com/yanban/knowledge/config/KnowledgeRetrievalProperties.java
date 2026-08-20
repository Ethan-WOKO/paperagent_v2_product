package com.yanban.knowledge.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "yanban.knowledge.retrieval")
public class KnowledgeRetrievalProperties {

    @Min(1)
    private int candidateLimit = 50;

    @DecimalMin(value = "0.0", inclusive = false)
    private double lexicalWeight = 0.5d;

    @DecimalMin(value = "0.0", inclusive = false)
    private double vectorWeight = 1.0d;

    @Min(1)
    private int rrfRankConstant = 10;

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public void setCandidateLimit(int candidateLimit) {
        this.candidateLimit = candidateLimit;
    }

    public double getLexicalWeight() {
        return lexicalWeight;
    }

    public void setLexicalWeight(double lexicalWeight) {
        this.lexicalWeight = lexicalWeight;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public void setVectorWeight(double vectorWeight) {
        this.vectorWeight = vectorWeight;
    }

    public int getRrfRankConstant() {
        return rrfRankConstant;
    }

    public void setRrfRankConstant(int rrfRankConstant) {
        this.rrfRankConstant = rrfRankConstant;
    }
}
