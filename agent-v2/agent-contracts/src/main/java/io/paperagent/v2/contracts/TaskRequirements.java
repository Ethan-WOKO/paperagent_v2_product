package io.paperagent.v2.contracts;

import java.util.HashSet;
import java.util.List;

public record TaskRequirements(
        RequirementDeclarationMode declarationMode,
        DeliveryRequirement deliveryRequirement,
        List<ValidationRequirement> validationRequirements,
        PublishRequirement publishRequirement) {

    public TaskRequirements {
        Contracts.required(declarationMode, "taskRequirements.declarationMode");
        Contracts.required(deliveryRequirement, "taskRequirements.deliveryRequirement");
        validationRequirements = Contracts.list(
                validationRequirements, "taskRequirements.validationRequirements");
        Contracts.required(publishRequirement, "taskRequirements.publishRequirement");
        if (declarationMode == RequirementDeclarationMode.EXPLICIT
                && publishRequirement == PublishRequirement.LEGACY_UNSPECIFIED) {
            Contracts.fail(ViolationCode.INCONSISTENT_REFERENCE,
                    "taskRequirements.publishRequirement",
                    "explicit requirements must declare whether publication is required");
        }
        if (declarationMode == RequirementDeclarationMode.LEGACY_UNSPECIFIED
                && (!validationRequirements.isEmpty()
                || publishRequirement != PublishRequirement.LEGACY_UNSPECIFIED)) {
            Contracts.fail(ViolationCode.INCONSISTENT_REFERENCE,
                    "taskRequirements.declarationMode",
                    "legacy unspecified requirements cannot carry formal requirements");
        }
        HashSet<String> ids = new HashSet<>();
        validationRequirements.forEach(requirement -> {
            Contracts.required(requirement, "taskRequirements.validationRequirements[]");
            if (!ids.add(requirement.requirementId())) {
                Contracts.fail(ViolationCode.DUPLICATE_ID,
                        "taskRequirements.validationRequirements[].requirementId",
                        "requirement identifiers must be unique");
            }
        });
    }

    public static TaskRequirements legacyUnspecified() {
        return new TaskRequirements(RequirementDeclarationMode.LEGACY_UNSPECIFIED,
                DeliveryRequirement.FINAL_DELIVERY_REQUIRED, List.of(),
                PublishRequirement.LEGACY_UNSPECIFIED);
    }

    public static TaskRequirements explicit(
            List<ValidationRequirement> validationRequirements,
            PublishRequirement publishRequirement) {
        return new TaskRequirements(RequirementDeclarationMode.EXPLICIT,
                DeliveryRequirement.FINAL_DELIVERY_REQUIRED,
                validationRequirements, publishRequirement);
    }
}
