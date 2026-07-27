package io.paperagent.v2.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class WorkspaceDiffValidationTest {
    private final ContentHash before = new ContentHash("sha256", ContractFixtures.hash('a'));
    private final ContentHash after = new ContentHash("sha256", ContractFixtures.hash('b'));

    @Test
    void acceptsAllFourDiffKinds() {
        new WorkspaceDiffEntry(
                DiffKind.ADD, new ProjectPath("paper/new.tex"), Optional.empty(),
                Optional.empty(), Optional.of(after), Map.of());
        new WorkspaceDiffEntry(
                DiffKind.MODIFY, new ProjectPath("paper/main.tex"), Optional.empty(),
                Optional.of(before), Optional.of(after), Map.of());
        new WorkspaceDiffEntry(
                DiffKind.DELETE, new ProjectPath("paper/old.tex"), Optional.empty(),
                Optional.of(before), Optional.empty(), Map.of());
        new WorkspaceDiffEntry(
                DiffKind.RENAME, new ProjectPath("paper/old.tex"), Optional.of(new ProjectPath("paper/new.tex")),
                Optional.of(before), Optional.of(before), Map.of());
    }

    @Test
    void rejectsAbsoluteAndTraversalPaths() {
        assertPathRejected("/etc/passwd");
        assertPathRejected("C:/secret.txt");
        assertPathRejected("paper/../secret.txt");
        assertPathRejected("paper//main.tex");
        assertPathRejected("paper/./main.tex");
        assertPathRejected("paper\\main.tex");
    }

    @Test
    void rejectsInvalidAddCombination() {
        assertInvalid(DiffKind.ADD, Optional.of(before), Optional.of(after), Optional.empty());
    }

    @Test
    void rejectsInvalidModifyCombination() {
        assertInvalid(DiffKind.MODIFY, Optional.empty(), Optional.of(after), Optional.empty());
        assertInvalid(DiffKind.MODIFY, Optional.of(before), Optional.of(before), Optional.empty());
    }

    @Test
    void rejectsInvalidDeleteCombination() {
        assertInvalid(DiffKind.DELETE, Optional.of(before), Optional.of(after), Optional.empty());
    }

    @Test
    void rejectsInvalidRenameCombination() {
        ProjectPath same = new ProjectPath("paper/main.tex");
        ContractViolationException exception = ContractFixtures.violation(() -> new WorkspaceDiffEntry(
                DiffKind.RENAME,
                same,
                Optional.of(same),
                Optional.of(before),
                Optional.of(after),
                Map.of()));
        assertEquals(ViolationCode.INVALID_DIFF_ENTRY, exception.primaryCode());
    }

    @Test
    void rejectsMalformedHash() {
        ContractViolationException exception =
                ContractFixtures.violation(() -> new ContentHash("sha256", "not-a-hash"));
        assertEquals(ViolationCode.INVALID_HASH, exception.primaryCode());
    }

    private static void assertPathRejected(String value) {
        ContractViolationException exception =
                ContractFixtures.violation(() -> new ProjectPath(value));
        assertEquals(ViolationCode.INVALID_PATH, exception.primaryCode());
    }

    private void assertInvalid(
            DiffKind kind,
            Optional<ContentHash> beforeHash,
            Optional<ContentHash> afterHash,
            Optional<ProjectPath> target) {
        ContractViolationException exception = ContractFixtures.violation(() -> new WorkspaceDiffEntry(
                kind,
                new ProjectPath("paper/main.tex"),
                target,
                beforeHash,
                afterHash,
                Map.of()));
        assertEquals(ViolationCode.INVALID_DIFF_ENTRY, exception.primaryCode());
    }
}
