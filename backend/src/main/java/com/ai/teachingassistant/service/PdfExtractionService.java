package com.ai.teachingassistant.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/**
 * PdfExtractionService is responsible for extracting raw text content
 * from uploaded PDF lecture files using Apache PDFBox, then cleaning
 * the output by stripping headers, footers, page numbers, and noise.
 */
@Slf4j
@Service
public class PdfExtractionService {

    // ── Regex patterns for cleaning ──────────────────────────────────────
    /** Standalone page numbers: "1", "Page 5", "- 12 -", "Page 3 of 20" */
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
            "^\\s*(?:[-–—]\\s*)?(?:Page\\s+)?\\d+(?:\\s*(?:of|/)\\s*\\d+)?(?:\\s*[-–—])?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** Three or more consecutive blank lines → collapse to two */
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    /** Non-printable / control characters (except newline, tab, carriage return) */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /**
     * Extracts and cleans text from the provided PDF.
     */
    public String extractText(MultipartFile file) throws IOException {
        log.info("Starting PDF text extraction for file: {}", file.getOriginalFilename());
        validateFile(file);

        try (InputStream inputStream = file.getInputStream();
             PDDocument document = PDDocument.load(inputStream)) {

            if (document.isEncrypted()) {
                log.warn("PDF is encrypted: {}", file.getOriginalFilename());
                throw new IOException("Cannot process encrypted PDF files. Please provide an unprotected PDF.");
            }

            int pageCount = document.getNumberOfPages();
            log.info("PDF loaded successfully. Total pages: {}", pageCount);

            if (pageCount == 0) {
                throw new IOException("PDF file has no pages.");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setAddMoreFormatting(true);

            String rawText = stripper.getText(document);

            if (rawText == null || rawText.trim().isEmpty()) {
                throw new IOException("No readable text found in PDF. The file may contain only images or scanned content.");
            }

            // ── Post-process: clean the extracted text ───────────────────
            String cleanedText = cleanExtractedText(rawText, pageCount);

            log.info("Text extraction complete. Raw: {} chars → Cleaned: {} chars",
                    rawText.length(), cleanedText.length());
            return cleanedText;

        } catch (IOException e) {
            log.error("Failed to extract text from PDF: {}", file.getOriginalFilename(), e);
            throw e;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TEXT CLEANING
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Cleans raw PDFBox output:
     * <ol>
     *   <li>Strips control characters</li>
     *   <li>Removes standalone page numbers</li>
     *   <li>Detects and removes repeated headers/footers</li>
     *   <li>Collapses excessive blank lines</li>
     *   <li>Trims leading/trailing whitespace per line</li>
     * </ol>
     */
    private String cleanExtractedText(String rawText, int pageCount) {
        String text = rawText;

        // 1. Remove control characters
        text = CONTROL_CHARS.matcher(text).replaceAll("");

        // 2. Remove standalone page numbers
        text = PAGE_NUMBER_PATTERN.matcher(text).replaceAll("");

        // 3. Detect and remove repeated headers/footers
        text = removeRepeatedHeadersFooters(text, pageCount);

        // 4. Collapse excessive blank lines (3+ → 2)
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");

        // 5. Trim each line and remove lines that are just whitespace
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            // Skip very short lines that are likely noise (1-2 chars)
            if (trimmed.length() <= 2 && !trimmed.isEmpty()
                    && !Character.isLetterOrDigit(trimmed.charAt(0))) {
                continue;
            }
            sb.append(trimmed).append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Detects lines that appear on most "pages" (split by form-feed or
     * large gaps) and removes them — these are likely headers or footers.
     *
     * <p>Strategy: split text into page-like blocks, count line frequency,
     * remove any line appearing in &gt;50% of blocks.</p>
     */
    private String removeRepeatedHeadersFooters(String text, int pageCount) {
        if (pageCount <= 2) return text; // not enough pages to detect patterns

        // Split by form-feed characters (PDFBox sometimes inserts these)
        String[] pages = text.split("\\f");
        if (pages.length <= 1) {
            // No form-feeds — try splitting by large blank gaps
            pages = text.split("\\n{4,}");
        }
        if (pages.length <= 2) return text;

        // Count how often each trimmed line appears across page blocks
        // Only check first 3 and last 3 lines of each page (where headers/footers live)
        Map<String, Integer> lineFrequency = new HashMap<>();
        for (String page : pages) {
            String[] lines = page.trim().split("\n");
            Set<String> pageLines = new HashSet<>();

            // Check first 3 lines
            for (int i = 0; i < Math.min(3, lines.length); i++) {
                String trimmed = lines[i].trim();
                if (!trimmed.isEmpty() && trimmed.length() < 100) {
                    pageLines.add(trimmed);
                }
            }
            // Check last 3 lines
            for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (!trimmed.isEmpty() && trimmed.length() < 100) {
                    pageLines.add(trimmed);
                }
            }

            for (String line : pageLines) {
                lineFrequency.merge(line, 1, Integer::sum);
            }
        }

        // Any line appearing in >50% of pages is likely a header/footer
        int threshold = pages.length / 2;
        Set<String> repeatedLines = new HashSet<>();
        for (Map.Entry<String, Integer> entry : lineFrequency.entrySet()) {
            if (entry.getValue() > threshold) {
                repeatedLines.add(entry.getKey());
                log.debug("Detected repeated header/footer: '{}'", entry.getKey());
            }
        }

        if (repeatedLines.isEmpty()) return text;

        // Remove those lines from the full text
        String[] allLines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : allLines) {
            if (!repeatedLines.contains(line.trim())) {
                sb.append(line).append("\n");
            }
        }

        log.info("Removed {} repeated header/footer patterns", repeatedLines.size());
        return sb.toString();
    }

    /**
     * Returns the number of pages in the PDF without full text extraction.
     *
     * @param file The PDF MultipartFile.
     * @return Number of pages.
     * @throws IOException If the PDF cannot be loaded.
     */
    public int getPageCount(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream();
             PDDocument document = PDDocument.load(inputStream)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * Validates the file before processing.
     *
     * @param file The MultipartFile to validate.
     * @throws IOException If validation fails.
     */
    private void validateFile(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("Uploaded file is empty or null.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new IOException("Only PDF files are supported. Received: " + originalFilename);
        }

        // Max file size: 10 MB
        long maxSizeBytes = 10L * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            throw new IOException("File size exceeds 10MB limit. File size: " + (file.getSize() / 1024 / 1024) + "MB");
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("application/pdf")) {
            log.warn("Unexpected content type: {}. Proceeding with filename-based validation.", contentType);
        }

        log.debug("File validation passed: name={}, size={}KB", originalFilename, file.getSize() / 1024);
    }
}