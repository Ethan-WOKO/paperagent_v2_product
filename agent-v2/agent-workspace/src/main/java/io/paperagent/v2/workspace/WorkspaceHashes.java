package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class WorkspaceHashes {
    private WorkspaceHashes() {
    }

    static ContentHash sha256(byte[] content) {
        WorkspaceValues.require(content, "hash");
        return new ContentHash(
                "sha256",
                lowercaseHex(newSha256Digest().digest(content)));
    }

    static ContentHash sha256(
            Path path,
            long maximum,
            String operation,
            ProjectPath projectPath) {
        MessageDigest digest = newSha256Digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(
                path,
                StandardOpenOption.READ,
                NOFOLLOW_LINKS)) {
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > maximum - read) {
                    throw new WorkspaceException(
                            WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                            operation,
                            projectPath);
                }
                digest.update(buffer, 0, read);
                total += read;
            }
            return new ContentHash("sha256", lowercaseHex(digest.digest()));
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (UnsupportedOperationException exception) {
            throw new WorkspaceException(
                    WorkspaceErrorCode.LINK_ESCAPE,
                    operation,
                    projectPath);
        } catch (IOException exception) {
            throw new WorkspaceException(WorkspaceErrorCode.IO_FAILURE, operation, projectPath);
        }
    }

    static String sha256Text(String value) {
        return lowercaseHex(
                newSha256Digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 17 must provide SHA-256");
        }
    }

    static String lowercaseHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
