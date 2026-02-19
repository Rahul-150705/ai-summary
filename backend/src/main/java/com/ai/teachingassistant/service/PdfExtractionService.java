package com.ai.teachingassistant.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * PdfExtractionService is responsible for extracting raw text content
 * from uploaded PDF lecture files using Apache PDFBox.
 */
@Slf4j
@Service
public class PdfExtractionService {

    /**
     * Extracts all text content from the provided PDF MultipartFile.
     *
     * @param file The uploaded PDF file.
     * @return A string containing all extracted text from every page.
     * @throws IOException If the PDF is corrupted, unreadable, or invalid.
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
            stripper.setSortByPosition(true);    // handles multi-column layouts
            stripper.setAddMoreFormatting(true);

            String extractedText = stripper.getText(document);

            if (extractedText == null || extractedText.trim().isEmpty()) {
                throw new IOException("No readable text found in PDF. The file may contain only images or scanned content.");
            }

            log.info("Text extraction complete. Characters extracted: {}", extractedText.length());
            return extractedText.trim();

        } catch (IOException e) {
            log.error("Failed to extract text from PDF: {}", file.getOriginalFilename(), e);
            throw e;
        }
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