package com.yanban.agent.v2.adapter.taskframe;

/**
 * A deterministic product-fact failure with an inspectable code and path.
 */
public final class ProductTaskFrameValidationException extends IllegalArgumentException {
    private final ProductTaskFrameValidationCode code;
    private final String path;

    ProductTaskFrameValidationException(
            ProductTaskFrameValidationCode code,
            String path,
            String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public ProductTaskFrameValidationCode code() {
        return code;
    }

    public String path() {
        return path;
    }
}
