package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Canonical, iteration-order-independent source-manifest fingerprint.
 */
final class WorkspaceManifestFingerprint {
    private static final byte[] DOMAIN =
            "paperagent.workspace.source-manifest.v1".getBytes(StandardCharsets.UTF_8);
    private static final Comparator<String> METADATA_UTF8_ORDER =
            (left, right) -> compareUnsigned(
                    metadataUtf8(left),
                    metadataUtf8(right));

    private WorkspaceManifestFingerprint() {
    }

    static ContentHash calculate(ProjectVersionSnapshot snapshot) {
        WorkspaceValues.require(snapshot, "sourceManifestFingerprint");
        MessageDigest digest = WorkspaceHashes.newSha256Digest();
        field(digest, DOMAIN);
        field(digest, trustedUtf8(snapshot.version().projectId()));
        field(digest, trustedUtf8(snapshot.version().versionId()));
        metadata(digest, snapshot.metadata());

        List<ProjectFileSnapshot> files = new ArrayList<>(snapshot.files());
        files.sort((left, right) -> compareUnsigned(
                pathUtf8(left.path()),
                pathUtf8(right.path())));
        integer(digest, files.size());
        for (ProjectFileSnapshot file : files) {
            field(digest, pathUtf8(file.path()));
            longInteger(digest, file.content().length);
            field(digest, trustedUtf8(file.hash().value()));
            metadata(digest, file.metadata());
        }
        return new ContentHash("sha256", WorkspaceHashes.lowercaseHex(digest.digest()));
    }

    private static void metadata(MessageDigest digest, Map<String, String> metadata) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(metadata.entrySet());
        entries.sort((left, right) -> {
            int key = METADATA_UTF8_ORDER.compare(
                    left.getKey(),
                    right.getKey());
            return key != 0
                    ? key
                    : METADATA_UTF8_ORDER.compare(
                            left.getValue(),
                            right.getValue());
        });
        integer(digest, entries.size());
        for (Map.Entry<String, String> entry : entries) {
            field(digest, metadataUtf8(entry.getKey()));
            field(digest, metadataUtf8(entry.getValue()));
        }
    }

    private static void field(MessageDigest digest, byte[] bytes) {
        integer(digest, bytes.length);
        digest.update(bytes);
    }

    private static void integer(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void longInteger(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static byte[] pathUtf8(ProjectPath path) {
        try {
            return strictUtf8(path.value());
        } catch (CharacterCodingException exception) {
            throw new WorkspaceException(
                    WorkspaceErrorCode.PATH_COLLISION,
                    "materialize",
                    path);
        }
    }

    private static byte[] metadataUtf8(String value) {
        try {
            return strictUtf8(value);
        } catch (CharacterCodingException exception) {
            throw new WorkspaceException(
                    WorkspaceErrorCode.INVALID_METADATA,
                    "materialize");
        }
    }

    private static byte[] trustedUtf8(String value) {
        try {
            return strictUtf8(value);
        } catch (CharacterCodingException exception) {
            throw new WorkspaceException(
                    WorkspaceErrorCode.INVALID_METADATA,
                    "materialize");
        }
    }

    private static byte[] strictUtf8(String value)
            throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
