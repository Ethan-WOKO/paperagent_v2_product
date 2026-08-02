package com.yanban.api.agent.v2.effect.project;

import java.util.Locale;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;

/** Read-only OOXML package signals. No relationship target is resolved. */
final class V2ProjectOoxmlSupport {
    private V2ProjectOoxmlSupport() {
    }

    static PackageSignals inspect(OPCPackage value) {
        int externalRelationships = external(value.getRelationships());
        boolean macrosPresent = false;
        try {
            for (var part : value.getParts()) {
                String name = part.getPartName().getName()
                        .toLowerCase(Locale.ROOT);
                macrosPresent |= name.endsWith("/vbaproject.bin");
                if (name.endsWith(".rels")) {
                    continue;
                }
                externalRelationships += external(part.getRelationships());
            }
        } catch (Exception invalid) {
            throw V2ProjectAnalysisToolSupport.failed("ooxml_relationships");
        }
        return new PackageSignals(
                externalRelationships, macrosPresent);
    }

    private static int external(Iterable<PackageRelationship> values) {
        int count = 0;
        for (PackageRelationship value : values) {
            if (value.getTargetMode() == TargetMode.EXTERNAL) {
                count++;
            }
        }
        return count;
    }

    record PackageSignals(
            int externalRelationshipCount,
            boolean macrosPresent) {
    }
}
