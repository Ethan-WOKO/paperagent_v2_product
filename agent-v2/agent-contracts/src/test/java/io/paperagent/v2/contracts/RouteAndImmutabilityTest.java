package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RouteAndImmutabilityTest {
    @Test
    void routeContractHasExactlyTwoValues() {
        assertArrayEquals(
                new Route[]{Route.DIRECT, Route.PERSISTENT_PLAN_EXECUTE},
                Route.values());
    }

    @Test
    void rejectsBlankIdWithStableCode() {
        ContractViolationException exception =
                ContractFixtures.violation(() -> new TaskFrameId(" "));
        assertEquals(ViolationCode.REQUIRED_TEXT_BLANK, exception.primaryCode());
    }

    @Test
    void rejectsNullCollectionElementWithStableCode() {
        List<String> targets = new ArrayList<>();
        targets.add("paper");
        targets.add(null);
        ContractViolationException exception = ContractFixtures.violation(() -> new TaskFrame(
                ContractFixtures.TASK_ID,
                "objective",
                targets,
                List.of("diff"),
                List.of(),
                java.util.Optional.empty(),
                ContractFixtures.profile(),
                ContractFixtures.T0));
        assertEquals(ViolationCode.NULL_COLLECTION_ELEMENT, exception.primaryCode());
    }

    @Test
    void returnedCollectionsAreImmutableAndDefensivelyCopied() {
        ArrayList<String> targets = new ArrayList<>(List.of("paper"));
        TaskFrame frame = new TaskFrame(
                ContractFixtures.TASK_ID,
                "objective",
                targets,
                List.of("diff"),
                List.of(),
                java.util.Optional.empty(),
                ContractFixtures.profile(),
                ContractFixtures.T0);
        targets.add("late mutation");

        assertEquals(List.of("paper"), frame.targets());
        assertThrows(UnsupportedOperationException.class, () -> frame.targets().add("mutation"));

        ObjectValue objectValue = new ObjectValue(Map.of("key", new ListValue(List.of(new TextValue("value")))));
        assertThrows(UnsupportedOperationException.class,
                () -> objectValue.values().put("other", NullValue.INSTANCE));
        assertThrows(UnsupportedOperationException.class,
                () -> ((ListValue) objectValue.values().get("key")).values().add(new BooleanValue(true)));

        ExecutionProfile profile = ContractFixtures.profile();
        assertThrows(UnsupportedOperationException.class,
                () -> profile.capabilities().add(Capability.ACCESS_NETWORK));
        assertEquals(Set.of(), profile.secretReferences());
    }
}
