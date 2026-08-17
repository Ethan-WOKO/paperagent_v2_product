import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class CanonicalDigestCheck {
    public static void main(String[] args) throws Exception {
        Path root = args.length == 0
                ? Path.of("agent-engine-contract", "conformance")
                : Path.of(args[0]);
        Path canonicalDir = root.resolve("canonical");
        List<Path> fixtures;
        try (var paths = Files.list(canonicalDir)) {
            fixtures = paths
                    .filter(path -> path.getFileName().toString().endsWith(".canonical.json"))
                    .sorted()
                    .toList();
        }
        if (fixtures.isEmpty()) {
            throw new IllegalStateException("no canonical digest fixtures");
        }
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (Path fixture : fixtures) {
            String name = fixture.getFileName().toString();
            String base = name.substring(0, name.length() - ".canonical.json".length());
            String expected = Files.readString(canonicalDir.resolve(base + ".sha256")).trim();
            String canonical = Files.readString(fixture, StandardCharsets.UTF_8)
                    .replaceFirst("\\r?\\n$", "");
            String actual = HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
            if (!actual.equals(expected)) {
                throw new IllegalStateException(base + " digest mismatch: " + actual);
            }
        }
        System.out.println("Java canonical digest validation passed: " + fixtures.size() + " fixtures");
    }
}
