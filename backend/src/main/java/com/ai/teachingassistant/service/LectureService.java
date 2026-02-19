package com.ai.teachingassistant.service;

import com.ai.teachingassistant.dto.SummaryResponse;
import com.ai.teachingassistant.model.Lecture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * LectureService orchestrates the full processing workflow:
 * PDF extraction → AI summarization → structured response assembly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureService {

    private final PdfExtractionService pdfExtractionService;
    private final SummarizationService summarizationService;

    /**
     * Main orchestration method. Accepts the uploaded PDF, extracts text,
     * generates an AI summary, and returns the SummaryResponse DTO.
     *
     * @param file The uploaded PDF MultipartFile.
     * @return Populated SummaryResponse DTO.
     * @throws IOException          If PDF extraction fails.
     * @throws InterruptedException If the LLM API call is interrupted.
     */
    public SummaryResponse processLecture(MultipartFile file) throws IOException, InterruptedException {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "unknown.pdf";

        log.info("Processing lecture: file='{}', size={}KB", fileName, file.getSize() / 1024);
        long startTime = System.currentTimeMillis();

        // Step 1: Extract text from PDF
        String extractedText = pdfExtractionService.extractText(file);

        // Step 2: Get page count for metadata
        int pageCount = countPagesFromText(extractedText);

        // Step 3: Build internal Lecture model for audit/logging
        Lecture lecture = Lecture.builder()
                .id(UUID.randomUUID().toString())
                .fileName(fileName)
                .originalText(extractedText)
                .fileSizeBytes(file.getSize())
                .pageCount(pageCount)
                .processedAt(LocalDateTime.now())
                .build();

        log.info("Lecture model created: id={}, pages={}", lecture.getId(), lecture.getPageCount());

        // Step 4: Generate AI summary
        SummaryResponse response = summarizationService.generateSummary(
                extractedText, fileName, pageCount);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Lecture processing complete in {}ms for file '{}'", elapsed, fileName);

        return response;
    }

    /**
     * Estimates page count based on form-feed characters or line breaks in extracted text.
     * Actual page count is retrieved by PdfExtractionService during extraction.
     */
    private int countPagesFromText(String text) {
        // Form feed character (\f) is emitted by PDFBox between pages
        long formFeeds = text.chars().filter(c -> c == '\f').count();
        return (int) (formFeeds > 0 ? formFeeds + 1 : 1);
    }
}