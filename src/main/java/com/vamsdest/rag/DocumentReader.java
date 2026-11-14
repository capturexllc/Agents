package com.vamsdest.rag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Turns an uploaded document into text sections. PDFs give one section per page. Excel workbooks give one section per
 * data row, written as "Header: value; Header: value", so each rule or policy row stays whole when it is chunked.
 */
public final class DocumentReader {

    /** A piece of a document with a human-readable location, such as "page 3" or "sheet 'Rules' row 4". */
    public record Section(String location, String text) {
    }

    private DocumentReader() {
    }

    public static boolean supports(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".xlsx");
    }

    public static List<Section> read(String fileName, byte[] bytes) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return readPdf(bytes);
        }
        if (lower.endsWith(".xlsx")) {
            return readWorkbook(bytes);
        }
        throw new IllegalArgumentException("Unsupported document type: " + fileName);
    }

    static List<Section> readPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<Section> sections = new ArrayList<>();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                if (!text.isEmpty()) {
                    sections.add(new Section("page " + page, text));
                }
            }
            return sections;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read PDF", exception);
        }
    }

    static List<Section> readWorkbook(byte[] bytes) {
        DataFormatter format = new DataFormatter();
        List<Section> sections = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (Sheet sheet : workbook) {
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) {
                    continue;
                }
                List<String> headers = new ArrayList<>();
                for (int column = 0; column < header.getLastCellNum(); column++) {
                    headers.add(format.formatCellValue(header.getCell(column)).strip());
                }
                for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    StringBuilder line = new StringBuilder();
                    for (int column = 0; column < headers.size(); column++) {
                        String value = format.formatCellValue(row.getCell(column)).strip();
                        if (value.isEmpty()) {
                            continue;
                        }
                        if (!line.isEmpty()) {
                            line.append("; ");
                        }
                        line.append(headers.get(column).isEmpty() ? "Column " + (column + 1) : headers.get(column))
                                .append(": ").append(value);
                    }
                    if (!line.isEmpty()) {
                        sections.add(new Section("sheet '" + sheet.getSheetName() + "' row " + (rowIndex + 1),
                                line.toString()));
                    }
                }
            }
            return sections;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read workbook", exception);
        }
    }
}
