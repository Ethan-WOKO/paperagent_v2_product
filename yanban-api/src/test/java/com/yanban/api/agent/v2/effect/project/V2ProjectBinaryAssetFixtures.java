package com.yanban.api.agent.v2.effect.project;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

final class V2ProjectBinaryAssetFixtures {
    private static final String EXTERNAL_RELATIONSHIP =
            "urn:paperagent:test:external";

    private V2ProjectBinaryAssetFixtures() {
    }

    static byte[] pdf(String... pages) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDDocumentInformation information = new PDDocumentInformation();
            information.setTitle("Bounded research report");
            information.setAuthor("PaperAgent fixture");
            document.setDocumentInformation(information);
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content =
                        new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static byte[] docx(String paragraph) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getProperties().getCoreProperties()
                    .setTitle("Frozen DOCX report");
            document.createParagraph().createRun().setText(paragraph);
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("metric");
            table.getRow(0).getCell(1).setText("0.95");
            document.getPackage().addExternalRelationship(
                    "https://example.invalid/reference",
                    EXTERNAL_RELATIONSHIP);
            document.write(output);
            return output.toByteArray();
        }
    }

    static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet summary = workbook.createSheet("Summary");
            Row header = summary.createRow(0);
            header.createCell(0).setCellValue("epoch");
            header.createCell(1).setCellValue("accuracy");
            Row first = summary.createRow(1);
            first.createCell(0).setCellValue(1);
            first.createCell(1).setCellValue(0.91);
            Row second = summary.createRow(2);
            second.createCell(0).setCellValue(2);
            second.createCell(1).setCellFormula("B2+0.04");
            workbook.createSheet("Hidden");
            workbook.setSheetHidden(1, true);
            workbook.getPackage().addExternalRelationship(
                    "https://example.invalid/workbook",
                    EXTERNAL_RELATIONSHIP);
            var macro = workbook.getPackage().createPart(
                    PackagingURIHelper.createPartName(
                            "/xl/vbaProject.bin"),
                    "application/vnd.ms-office.vbaProject");
            try (var macroOutput = macro.getOutputStream()) {
                macroOutput.write(new byte[]{0x01, 0x02, 0x03});
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    static byte[] xlsxGrid(int rows, int columns) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Grid");
            for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                Row row = sheet.createRow(rowIndex);
                for (int column = 0; column < columns; column++) {
                    row.createCell(column).setCellValue(
                            "cell-" + rowIndex + "-" + column + "-"
                                    + "x".repeat(100));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
