package com.yanban.api.agent.v2.compatibility.literature;

public final class LiteratureDeliveryTaskBindingException
        extends RuntimeException {
    private final String path;

    LiteratureDeliveryTaskBindingException(String path) {
        super("V2 literature task binding failed at " + path);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
