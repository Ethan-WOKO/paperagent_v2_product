package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ProjectAssetAdmissionPolicyTest {

    @Test
    void admitsTextPdfDocxAndXlsxByContentNotExtensionAlone()
            throws Exception {
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "notes.md", "plain text".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "paper.pdf", "%PDF-1.7\n%%EOF".getBytes(
                        StandardCharsets.US_ASCII))).isTrue();
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "paper.docx", ooxml("word/document.xml"))).isTrue();
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "results.xlsx", ooxml("xl/workbook.xml"))).isTrue();

        assertThat(ProjectAssetAdmissionPolicy.admits(
                "paper.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "paper.docx", ooxml("xl/workbook.xml"))).isFalse();
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "results.xlsx", ooxml("word/document.xml"))).isFalse();
    }

    @Test
    void rejectsNulTextAndUnsafeOoxmlEntryNames() throws Exception {
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "image.png", new byte[] {'P', 'N', 'G', 0, 1}))
                .isFalse();
        assertThat(ProjectAssetAdmissionPolicy.admits(
                "paper.docx", zip(Map.of(
                        "[Content_Types].xml", "types",
                        "../word/document.xml", "document"))))
                .isFalse();
    }

    private static byte[] ooxml(String part) throws Exception {
        return zip(Map.of(
                "[Content_Types].xml", "types",
                part, "content"));
    }

    private static byte[] zip(Map<String, String> entries)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
