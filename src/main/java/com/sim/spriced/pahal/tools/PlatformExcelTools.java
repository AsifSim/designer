package com.sim.spriced.pahal.tools;

import com.sim.spriced.pahal.utility.ToolTracker;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class PlatformExcelTools {

    private static final Logger logger = LoggerFactory.getLogger(PlatformExcelTools.class);
    private static final String EXCEL_DIR = System.getProperty("user.dir") + "/generated_excels";

    @Tool(description = "Generates a multi-sheet Excel workbook for DEAM definitions. Each sheet represents a DEAM. Columns represent attributes, and rows represent sample data. 'Virtual Attributes' MUST use standard Excel formulas. Returns the download URL.")
    public String generateDeamExcelWorkbook(
            @ToolParam(description = "A list of sheet objects containing 'sheetName', 'headers' (list of strings), and 'sampleData' (list of rows, where each row is a list of values/formulas)") List<Map<String, Object>> sheets) {

        ToolTracker.track("generateDeamExcelWorkbook", "Generated DEAM Excel workbook with " + sheets.size() + " sheets");
        logger.info("Generating DEAM Excel workbook with {} sheets...", sheets.size());

        try {
            Path dirPath = Paths.get(EXCEL_DIR);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
            }

            String fileName = "DEAM_Architecture_" + UUID.randomUUID().toString().substring(0, 8) + ".xlsx";
            File file = new File(dirPath.toFile(), fileName);

            try (Workbook workbook = new XSSFWorkbook()) {

                CellStyle headerStyle = workbook.createCellStyle();
                Font headerFont = workbook.createFont();
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

                for (Map<String, Object> sheetData : sheets) {
                    String sheetName = (String) sheetData.get("sheetName");
                    Sheet sheet = workbook.createSheet(sheetName != null ? sheetName : "Sheet");

                    List<String> headers = (List<String>) sheetData.get("headers");
                    Map<String, String> colLetterMapping = new HashMap<>();

                    Row headerRow = sheet.createRow(0);
                    if (headers != null) {
                        for (int i = 0; i < headers.size(); i++) {
                            Cell cell = headerRow.createCell(i);
                            cell.setCellValue(headers.get(i));
                            cell.setCellStyle(headerStyle);
                            sheet.setColumnWidth(i, 5500);

                            // Map the header name strictly to the Excel column letter (A, B, C...)
                            colLetterMapping.put(headers.get(i), getColumnLetter(i));
                        }
                    }

                    List<List<Object>> sampleData = (List<List<Object>>) sheetData.get("sampleData");
                    if (sampleData != null) {
                        int rowIndex = 1;
                        for (List<Object> rowData : sampleData) {
                            Row row = sheet.createRow(rowIndex);
                            for (int colIndex = 0; colIndex < rowData.size(); colIndex++) {
                                Cell cell = row.createCell(colIndex);
                                Object val = rowData.get(colIndex);

                                if (val instanceof Number) {
                                    cell.setCellValue(((Number) val).doubleValue());
                                } else if (val instanceof Boolean) {
                                    cell.setCellValue((Boolean) val);
                                } else if (val != null) {
                                    String strVal = val.toString().trim();

                                    // If the AI generated a formula
                                    if (strVal.startsWith("=")) {
                                        String formula = strVal.substring(1); // Remove the '='

                                        // 1. Replace ALL bracketed references [HeaderName] with absolute cell references like A2, B2
                                        if (headers != null) {
                                            for (String header : headers) {
                                                String targetCell = colLetterMapping.get(header) + (rowIndex + 1);
                                                formula = formula.replace("[" + header + "]", targetCell);
                                            }
                                        }

                                        try {
                                            // 2. Safely apply the formula so Excel calculates it live
                                            cell.setCellFormula(formula);
                                        } catch (Exception parseException) {
                                            // Fallback only if the AI used syntax POI fundamentally cannot parse
                                            logger.debug("POI failed to parse formula: {}. Setting as plain text.", formula);
                                            cell.setCellValue(strVal);
                                        }
                                    } else {
                                        cell.setCellValue(strVal);
                                    }
                                }
                            }
                            rowIndex++;
                        }
                    }
                }

                // CRITICAL: Force Excel to evaluate all formulas immediately when the user opens the file.
                workbook.setForceFormulaRecalculation(true);

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    workbook.write(fos);
                }
            }

            String downloadUrl = "/api/v1/architecture/download/" + fileName;
            logger.info("Excel successfully generated at: {}", downloadUrl);
            return "Excel workbook generated successfully! Provide this exact download link to the user: [Download DEAM Excel Workbook](" + downloadUrl + ")";

        } catch (Exception e) {
            logger.error("Failed to generate Excel workbook", e);
            return "Error generating Excel workbook: " + e.getMessage();
        }
    }

    // Convert numeric index to alphabetical column (0->A, 1->B, ..., 26->AA)
    private String getColumnLetter(int colIndex) {
        StringBuilder colName = new StringBuilder();
        while (colIndex >= 0) {
            int remainder = colIndex % 26;
            colName.insert(0, (char) (remainder + 'A'));
            colIndex = (colIndex / 26) - 1;
        }
        return colName.toString();
    }
}