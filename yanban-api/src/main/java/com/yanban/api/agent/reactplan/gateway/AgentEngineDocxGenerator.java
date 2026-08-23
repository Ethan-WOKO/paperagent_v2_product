package com.yanban.api.agent.reactplan.gateway;

import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.DocxBlock;
import com.yanban.api.agent.reactplan.gateway.AgentEngineGatewayDtos.WorkspaceDocxCreateRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Deterministic, active-content-free DOCX generation for one isolated Workspace ADD. */
final class AgentEngineDocxGenerator {
    static final int MAX_BLOCKS = 600;
    static final int MAX_TEXT_CHARACTERS = 200_000;
    static final int MAX_TABLE_ROWS = 100;
    static final int MAX_TABLE_COLUMNS = 20;
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    GeneratedDocx generate(WorkspaceDocxCreateRequest request) {
        validate(request);
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (request.title() != null) {
                document.getProperties().getCoreProperties()
                        .setTitle(request.title());
            }
            if (request.author() != null) {
                document.getProperties().getCoreProperties()
                        .setCreator(request.author());
            }
            Style style = Style.from(request.styleProfile());
            for (DocxBlock block : request.blocks()) {
                append(document, block, style);
            }
            document.write(output);
            byte[] bytes = output.toByteArray();
            verify(bytes, request.blocks());
            return new GeneratedDocx(bytes, DOCX_MEDIA_TYPE);
        } catch (AgentEngineDocxException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AgentEngineDocxException("DOCX_GENERATION_FAILED");
        }
    }

    private void append(XWPFDocument document, DocxBlock block, Style style) {
        switch (block.type()) {
            case "HEADING" -> appendHeading(document, block, style);
            case "PARAGRAPH" -> appendParagraph(document, block, style);
            case "TABLE" -> appendTable(document, block, style);
            case "PAGE_BREAK" -> document.createParagraph()
                    .createRun().addBreak(BreakType.PAGE);
            default -> throw new AgentEngineDocxException(
                    "DOCX_BLOCK_INVALID");
        }
    }

    private void appendHeading(
            XWPFDocument document, DocxBlock block, Style style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment(block.alignment(),
                ParagraphAlignment.LEFT));
        paragraph.setSpacingAfter(120);
        XWPFRun run = paragraph.createRun();
        run.setText(block.text());
        run.setBold(true);
        int level = block.level() == null ? 1 : block.level();
        format(run, style.headingFont(), switch (level) {
            case 1 -> 18;
            case 2 -> 16;
            default -> 14;
        });
    }

    private void appendParagraph(
            XWPFDocument document, DocxBlock block, Style style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(alignment(block.alignment(),
                style.paragraphAlignment()));
        paragraph.setSpacingBetween(style.lineSpacing());
        paragraph.setSpacingAfter(80);
        if (Boolean.TRUE.equals(block.firstLineIndent())) {
            paragraph.setIndentationFirstLine(480);
        }
        XWPFRun run = paragraph.createRun();
        run.setText(block.text());
        run.setBold(Boolean.TRUE.equals(block.bold()));
        format(run, style.bodyFont(),
                block.fontSize() == null ? style.bodySize()
                        : block.fontSize());
    }

    private void appendTable(
            XWPFDocument document, DocxBlock block, Style style) {
        List<List<String>> rows = block.rows();
        XWPFTable table = document.createTable(
                rows.size(), rows.get(0).size());
        for (int row = 0; row < rows.size(); row++) {
            for (int column = 0; column < rows.get(row).size(); column++) {
                var cell = table.getRow(row).getCell(column);
                cell.removeParagraph(0);
                XWPFParagraph paragraph = cell.addParagraph();
                paragraph.setAlignment(ParagraphAlignment.LEFT);
                XWPFRun run = paragraph.createRun();
                run.setText(rows.get(row).get(column));
                run.setBold(row == 0 && Boolean.TRUE.equals(block.headerRow()));
                format(run, style.bodyFont(), style.bodySize());
            }
        }
    }

    private void verify(byte[] bytes, List<DocxBlock> expected) {
        try (XWPFDocument reopened = new XWPFDocument(
                new ByteArrayInputStream(bytes))) {
            List<String> actual = new ArrayList<>();
            for (var element : reopened.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    boolean pageBreak = paragraph.getRuns().stream()
                            .map(XWPFRun::getCTR)
                            .anyMatch(run -> run.getBrList().stream()
                                    .anyMatch(br -> br.getType()
                                            == org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType.PAGE));
                    actual.add(pageBreak ? "PAGE_BREAK\0" :
                            "PARAGRAPH\0" + safe(paragraph.getText()));
                } else if (element instanceof XWPFTable table) {
                    actual.add("TABLE\0" + tableRows(table));
                }
            }
            List<String> wanted = expected.stream().map(block -> switch (block.type()) {
                case "HEADING", "PARAGRAPH" ->
                        "PARAGRAPH\0" + safe(block.text());
                case "PAGE_BREAK" -> "PAGE_BREAK\0";
                case "TABLE" -> "TABLE\0" + encodeRows(block.rows());
                default -> throw new AgentEngineDocxException(
                        "DOCX_BLOCK_INVALID");
            }).toList();
            if (!actual.equals(wanted)) {
                throw new AgentEngineDocxException(
                        "DOCX_VERIFICATION_FAILED");
            }
        } catch (AgentEngineDocxException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AgentEngineDocxException("DOCX_VERIFICATION_FAILED");
        }
    }

    void validate(WorkspaceDocxCreateRequest request) {
        if (request == null || request.blocks() == null
                || request.blocks().isEmpty()
                || request.blocks().size() > MAX_BLOCKS
                || !(request.styleProfile() == null
                || "GENERAL".equals(request.styleProfile())
                || "CHINESE_ACADEMIC".equals(request.styleProfile()))) {
            throw new AgentEngineDocxException("DOCX_REQUEST_INVALID");
        }
        int characters = length(request.title()) + length(request.author());
        for (DocxBlock block : request.blocks()) {
            if (block == null || block.type() == null) {
                throw new AgentEngineDocxException("DOCX_BLOCK_INVALID");
            }
            switch (block.type()) {
                case "HEADING" -> {
                    requireText(block.text());
                    if (block.level() == null || block.level() < 1
                            || block.level() > 3 || block.rows() != null) {
                        throw new AgentEngineDocxException(
                                "DOCX_BLOCK_INVALID");
                    }
                }
                case "PARAGRAPH" -> {
                    requireText(block.text());
                    if (block.rows() != null || block.fontSize() != null
                            && (block.fontSize() < 8
                            || block.fontSize() > 36)) {
                        throw new AgentEngineDocxException(
                                "DOCX_BLOCK_INVALID");
                    }
                }
                case "TABLE" -> validateTable(block);
                case "PAGE_BREAK" -> {
                    if (block.text() != null || block.rows() != null) {
                        throw new AgentEngineDocxException(
                                "DOCX_BLOCK_INVALID");
                    }
                }
                default -> throw new AgentEngineDocxException(
                        "DOCX_BLOCK_INVALID");
            }
            if (block.alignment() != null
                    && !("LEFT".equals(block.alignment())
                    || "CENTER".equals(block.alignment())
                    || "RIGHT".equals(block.alignment())
                    || "JUSTIFY".equals(block.alignment()))) {
                throw new AgentEngineDocxException("DOCX_BLOCK_INVALID");
            }
            characters += length(block.text());
            if (block.rows() != null) {
                characters += block.rows().stream().flatMap(List::stream)
                        .mapToInt(AgentEngineDocxGenerator::length).sum();
            }
            if (characters > MAX_TEXT_CHARACTERS) {
                throw new AgentEngineDocxException("DOCX_CONTENT_TOO_LARGE");
            }
        }
    }

    private void validateTable(DocxBlock block) {
        if (block.text() != null || block.rows() == null
                || block.rows().isEmpty()
                || block.rows().size() > MAX_TABLE_ROWS
                || block.rows().get(0) == null
                || block.rows().get(0).isEmpty()
                || block.rows().get(0).size() > MAX_TABLE_COLUMNS) {
            throw new AgentEngineDocxException("DOCX_BLOCK_INVALID");
        }
        int columns = block.rows().get(0).size();
        for (List<String> row : block.rows()) {
            if (row == null || row.size() != columns
                    || row.stream().anyMatch(value -> value == null
                    || value.length() > 10_000)) {
                throw new AgentEngineDocxException("DOCX_BLOCK_INVALID");
            }
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 20_000) {
            throw new AgentEngineDocxException("DOCX_BLOCK_INVALID");
        }
    }

    private static ParagraphAlignment alignment(
            String value, ParagraphAlignment fallback) {
        if (value == null) return fallback;
        return "JUSTIFY".equals(value) ? ParagraphAlignment.BOTH
                : ParagraphAlignment.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static void format(XWPFRun run, String font, int size) {
        run.setFontFamily(font);
        run.setFontSize(size);
    }

    private static String tableRows(XWPFTable table) {
        return encodeRows(table.getRows().stream()
                .map(row -> row.getTableCells().stream()
                        .map(cell -> safe(cell.getText())).toList())
                .toList());
    }

    private static String encodeRows(List<List<String>> rows) {
        return rows.stream().map(row -> row.stream()
                .map(AgentEngineDocxGenerator::safe)
                .reduce((left, right) -> left + "\u001f" + right)
                .orElse(""))
                .reduce((left, right) -> left + "\u001e" + right)
                .orElse("");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\u001e', ' ')
                .replace('\u001f', ' ');
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    record GeneratedDocx(byte[] bytes, String mediaType) {
        GeneratedDocx {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    static final class AgentEngineDocxException extends RuntimeException {
        private final String code;

        AgentEngineDocxException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private record Style(
            String bodyFont, String headingFont, int bodySize,
            double lineSpacing, ParagraphAlignment paragraphAlignment) {
        private static Style from(String value) {
            return "CHINESE_ACADEMIC".equals(value)
                    ? new Style("宋体", "黑体", 12, 1.5,
                    ParagraphAlignment.BOTH)
                    : new Style("Arial", "Arial", 11, 1.15,
                    ParagraphAlignment.LEFT);
        }
    }
}
