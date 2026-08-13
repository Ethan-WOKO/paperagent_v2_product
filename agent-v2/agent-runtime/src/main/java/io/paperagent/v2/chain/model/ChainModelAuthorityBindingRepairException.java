package io.paperagent.v2.chain.model;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Safe, server-authored repair feedback for exact authority-ref binding. */
public final class ChainModelAuthorityBindingRepairException
        extends RuntimeException {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,128}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.]{1,128}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9._#:-]{1,256}");

    public ChainModelAuthorityBindingRepairException(
            String code, String reviewedObjectPath,
            String reviewedObjectRef, String directFactPath,
            List<String> directFactRefs) {
        super(feedback(code, reviewedObjectPath, reviewedObjectRef,
                directFactPath, directFactRefs));
    }

    public String safeFeedback() {
        return getMessage();
    }

    private static String feedback(
            String code, String reviewedObjectPath,
            String reviewedObjectRef, String directFactPath,
            List<String> directFactRefs) {
        String checkedCode = checked(CODE, code, "code");
        String reviewedPath = checked(PATH, reviewedObjectPath,
                "reviewedObjectPath");
        String reviewedRef = checked(REF, reviewedObjectRef,
                "reviewedObjectRef");
        String factsPath = checked(PATH, directFactPath, "directFactPath");
        List<String> facts = List.copyOf(Objects.requireNonNull(
                directFactRefs, "directFactRefs"));
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("directFactRefs is empty");
        }
        String encoded = facts.stream()
                .map(value -> "\"" + checked(REF, value,
                        "directFactRef") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return checkedCode + "; " + reviewedPath
                + " must include exactly this formal authority ref: \""
                + reviewedRef + "\"; " + factsPath
                + " must include every ref in this exact list: " + encoded;
    }

    private static String checked(
            Pattern pattern, String value, String field) {
        String checked = Objects.requireNonNull(value, field);
        if (!pattern.matcher(checked).matches()) {
            throw new IllegalArgumentException(field + " is not a safe identifier");
        }
        return checked;
    }
}
