package com.yanban.api.project;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Deterministic admission policy for Project text and read-only binary assets. */
final class ProjectAssetAdmissionPolicy {

    private static final int TEXT_SAMPLE_BYTES = 8 * 1024;
    private static final int MAX_OOXML_ENTRIES = 4_096;
    private static final int MAX_OOXML_ENTRY_NAME_CHARACTERS = 2_048;
    private static final long MAX_OOXML_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private ProjectAssetAdmissionPolicy() {
    }

    static boolean admits(String path, byte[] content) {
        if (path == null || content == null) {
            return false;
        }
        return switch (binaryKind(path)) {
            case PDF -> hasPrefix(content, PDF_SIGNATURE);
            case DOC -> validLegacyWord(content);
            case DOCX -> validOoxml(content, "word/");
            case XLSX -> validOoxml(content, "xl/");
            case NONE -> readableText(content);
        };
    }

    static boolean readOnlyBinaryPath(String path) {
        return binaryKind(path) != BinaryKind.NONE;
    }

    static boolean readableText(byte[] content) {
        if (content == null) {
            return false;
        }
        int sample = Math.min(TEXT_SAMPLE_BYTES, content.length);
        for (int index = 0; index < sample; index++) {
            if (content[index] == 0) {
                return false;
            }
        }
        return true;
    }

    private static BinaryKind binaryKind(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return BinaryKind.PDF;
        }
        if (lower.endsWith(".docx")) {
            return BinaryKind.DOCX;
        }
        if (lower.endsWith(".doc")) {
            return BinaryKind.DOC;
        }
        if (lower.endsWith(".xlsx")) {
            return BinaryKind.XLSX;
        }
        return BinaryKind.NONE;
    }

    private static boolean validLegacyWord(byte[] content) {
        try (POIFSFileSystem filesystem = new POIFSFileSystem(
                new ByteArrayInputStream(content))) {
            return filesystem.getRoot()
                    .hasEntryCaseInsensitive("WordDocument");
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static boolean validOoxml(byte[] content, String requiredPrefix) {
        if (content.length < 4
                || content[0] != 'P'
                || content[1] != 'K') {
            return false;
        }
        boolean contentTypes = false;
        boolean requiredPart = false;
        int entries = 0;
        long expanded = 0;
        byte[] buffer = new byte[8 * 1024];
        try (ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_OOXML_ENTRIES) {
                    return false;
                }
                String name = entry.getName();
                if (name == null || name.length()
                        > MAX_OOXML_ENTRY_NAME_CHARACTERS
                        || !safeZipEntryName(name)) {
                    return false;
                }
                contentTypes |= "[Content_Types].xml".equals(name);
                requiredPart |= name.startsWith(requiredPrefix)
                        && !entry.isDirectory();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    expanded += read;
                    if (expanded > MAX_OOXML_EXPANDED_BYTES) {
                        return false;
                    }
                }
            }
            return contentTypes && requiredPart;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static boolean hasPrefix(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeZipEntryName(String name) {
        if (name.isBlank() || name.startsWith("/")
                || name.startsWith("\\") || name.contains("\\")
                || name.matches("^[A-Za-z]:.*")
                || name.chars().anyMatch(character ->
                        character >= 0 && character < 32)) {
            return false;
        }
        String logicalName = name.endsWith("/")
                ? name.substring(0, name.length() - 1) : name;
        for (String segment : logicalName.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".")
                    || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private enum BinaryKind {
        NONE,
        PDF,
        DOC,
        DOCX,
        XLSX
    }
}
