package com.ai.teachingassistant.service;

import com.ai.teachingassistant.client.LlmClient;
import com.ai.teachingassistant.dto.SummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * SummarizationService constructs AI prompts from extracted lecture text,
 * sends them to the configured LLM provider, and parses the structured response.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private final LlmClient llmClient;

    // Delimiters used to parse sections from LLM response
    private static final String KEY_CONCEPTS_HEADER   = "[KEY_CONCEPTS]";
    private static final String DEFINITIONS_HEADER    = "[DEFINITIONS]";
    private static final String EXAM_POINTS_HEADER    = "[EXAM_POINTS]";
    private static final String TITLE_HEADER          = "[TITLE]";

    /**
     * Generates a structured summary from the extracted lecture text.
     *
     * @param extractedText  Raw text from PDF extraction.
     * @param fileName       Original PDF file name.
     * @param pageCount      Number of pages in the PDF.
     * @return Populated SummaryResponse DTO.
     */
    public SummaryResponse generateSummary(String extractedText, String fileName, int pageCount)
            throws IOException, InterruptedException {

        log.info("Generating summary for file: {}", fileName);

        String prompt = buildPrompt(extractedText);
        log.debug("Prompt constructed. Length: {} characters", prompt.length());

        String rawResponse = llmClient.sendPrompt(prompt);
        log.debug("LLM raw response received. Length: {} characters", rawResponse.length());

        return parseResponse(rawResponse, fileName, pageCount);
    }

    /**
     * Constructs the prompt sent to the LLM with clear structured output instructions.
     *
     * @param lectureText  The full extracted PDF text.
     * @return Formatted prompt string.
     */
    private String buildPrompt(String lectureText) {
        return """
                You are an expert educational assistant. Analyze the following lecture content and generate a structured summary.
                
                IMPORTANT: Format your response EXACTLY as shown below using these section headers.
                Do NOT add any extra text outside these sections.
                
                [TITLE]
                <Write a concise title summarizing the lecture topic>
                
                [KEY_CONCEPTS]
                - <Key concept 1>
                - <Key concept 2>
                - <Key concept 3>
                (Add as many as relevant, minimum 5)
                
                [DEFINITIONS]
                - <Term>: <Clear definition>
                - <Term>: <Clear definition>
                (Add all important terms from the lecture)
                
                [EXAM_POINTS]
                - <Exam-focused takeaway 1>
                - <Exam-focused takeaway 2>
                - <Exam-focused takeaway 3>
                (Add at least 5 exam-relevant points)
                
                --- LECTURE CONTENT START ---
                %s
                --- LECTURE CONTENT END ---
                """.formatted(truncateText(lectureText, 12000));
    }

    /**
     * Parses the structured LLM response into a SummaryResponse DTO.
     *
     * @param rawResponse  The raw text from the LLM.
     * @param fileName     Original PDF file name.
     * @param pageCount    Number of pages.
     * @return Populated SummaryResponse.
     */
    private SummaryResponse parseResponse(String rawResponse, String fileName, int pageCount) {
        log.debug("Parsing LLM response into structured DTO");

        String title       = extractSection(rawResponse, TITLE_HEADER, KEY_CONCEPTS_HEADER).trim();
        List<String> keyPoints  = extractBulletPoints(rawResponse, KEY_CONCEPTS_HEADER, DEFINITIONS_HEADER);
        List<String> definitions = extractBulletPoints(rawResponse, DEFINITIONS_HEADER, EXAM_POINTS_HEADER);
        List<String> examPoints  = extractBulletPoints(rawResponse, EXAM_POINTS_HEADER, null);

        if (title.isEmpty()) {
            title = "Lecture Summary - " + fileName.replace(".pdf", "");
        }

        log.info("Parsed summary: title='{}', keyPoints={}, definitions={}, examPoints={}",
                title, keyPoints.size(), definitions.size(), examPoints.size());

        return SummaryResponse.builder()
                .title(title)
                .keyPoints(keyPoints)
                .definitions(definitions)
                .examPoints(examPoints)
                .fileName(fileName)
                .provider(llmClient.getActiveProvider())
                .generatedAt(LocalDateTime.now())
                .pageCount(pageCount)
                .build();
    }

    /**
     * Extracts raw text content between two section headers.
     */
    private String extractSection(String text, String startHeader, String endHeader) {
        int startIdx = text.indexOf(startHeader);
        if (startIdx == -1) return "";
        startIdx += startHeader.length();

        if (endHeader != null) {
            int endIdx = text.indexOf(endHeader, startIdx);
            if (endIdx == -1) return text.substring(startIdx).trim();
            return text.substring(startIdx, endIdx).trim();
        }
        return text.substring(startIdx).trim();
    }

    /**
     * Extracts bullet-point lines from a section, stripping the leading "- " marker.
     */
    private List<String> extractBulletPoints(String text, String startHeader, String endHeader) {
        String section = extractSection(text, startHeader, endHeader);
        if (section.isEmpty()) return new ArrayList<>();

        return Arrays.stream(section.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* "))
                .map(line -> line.replaceFirst("^[-•*]\\s+", "").trim())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Truncates text to a max character count to respect LLM token limits.
     */
    private String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        log.warn("Lecture text truncated from {} to {} characters for LLM context limit.", text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[... content truncated due to length ...]";
    }
}