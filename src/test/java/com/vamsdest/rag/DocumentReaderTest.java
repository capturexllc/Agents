package com.vamsdest.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class DocumentReaderTest {

    @Test
    void excelRowsBecomeLabelledSections() throws Exception {
        List<DocumentReader.Section> sections = DocumentReader.read("rules.xlsx", workbook());

        assertEquals(2, sections.size());
        assertEquals("sheet 'Rules' row 2", sections.get(0).location());
        assertEquals("Topic: Refunds; Rule: Full refund within 24 hours; Applies to: Flights", sections.get(0).text());
        assertEquals("Topic: Hotels; Rule: Pay in advance", sections.get(1).text());
    }

    @Test
    void pdfTextIsReadPerPage() throws Exception {
        List<DocumentReader.Section> sections = DocumentReader.read("policy.pdf",
                pdf("Cancellation policy: free within 48 hours."));

        assertEquals(1, sections.size());
        assertEquals("page 1", sections.get(0).location());
        assertTrue(sections.get(0).text().contains("free within 48 hours"));
    }

    @Test
    void unsupportedTypesAreRejected() {
        assertFalse(DocumentReader.supports("notes.docx"));
        assertThrows(IllegalArgumentException.class, () -> DocumentReader.read("notes.docx", new byte[0]));
    }

    private static byte[] workbook() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Rules");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Topic");
            header.createCell(1).setCellValue("Rule");
            header.createCell(2).setCellValue("Applies to");
            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("Refunds");
            first.createCell(1).setCellValue("Full refund within 24 hours");
            first.createCell(2).setCellValue("Flights");
            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("Hotels");
            second.createCell(1).setCellValue("Pay in advance");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
