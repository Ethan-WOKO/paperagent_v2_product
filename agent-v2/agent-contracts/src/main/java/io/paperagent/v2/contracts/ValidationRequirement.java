package io.paperagent.v2.contracts;

public record ValidationRequirement(
        String requirementId,
        ValidationSubject subject,
        String completionCondition) {

    public ValidationRequirement {
        requirementId = Contracts.text(requirementId, "validationRequirement.requirementId");
        Contracts.required(subject, "validationRequirement.subject");
        completionCondition = Contracts.text(completionCondition,
                "validationRequirement.completionCondition");
    }
}
