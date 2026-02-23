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

@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private final LlmClient llmClient;

    // Delimiters used to parse sections from LLM response
    private static final String TITLE_HEADER = "[TITLE]";
    private static final String OVERVIEW_HEADER = "[OVERVIEW]";
    private static final String KEY_CONCEPTS_HEADER = "[KEY_CONCEPTS]";
    private static final String DEFINITIONS_HEADER = "[DEFINITIONS]";
    private static final String DETAILED_HEADER = "[DETAILED_EXPLANATION]";
    private static final String EXAM_POINTS_HEADER = "[EXAM_POINTS]";
    private static final String FURTHER_READING_HEADER = "[FURTHER_READING]";

    /**
     * Generates a structured summary from the extracted lecture text.
     */
    public SummaryResponse generateSummary(String extractedText, String fileName, int pageCount)
            throws IOException, InterruptedException {

        log.info("Generating summary for file: {}", fileName);

        String prompt = buildPrompt(extractedText);
        log.debug("Prompt constructed. Length: {} characters", prompt.length());

        String rawResponse = llmClient.sendPrompt(prompt);
        log.debug("LLM raw response received. Length: {} characters", rawResponse.length());

        if (rawResponse == null || rawResponse.isBlank()) {
            log.error("LLM returned an empty response for file: {}", fileName);
            throw new IOException("AI model returned an empty response. Please try again.");
        }

        return parseResponse(rawResponse, fileName, pageCount);
    }

    /**
     * Constructs an enhanced prompt that requests a comprehensive, detailed
     * summary.
     */
    private String buildPrompt(String lectureText) {
        return """
                You are an expert university-level teaching assistant.
                Read the lecture content below and produce a detailed, well-structured summary.

                Use EXACTLY these section markers on their own line. Start each section on a new line.
                Write in full sentences. Do not skip any section.

                [TITLE]
                Write a short, descriptive title for this lecture.

                [OVERVIEW]
                Write 4-5 sentences summarising what this lecture is about, its main goals and key arguments.

                [KEY_CONCEPTS]
                List at least 8 key concepts as bullet points starting with "- ". Each bullet must be a full sentence.

                [DEFINITIONS]
                List at least 6 important terms as bullet points starting with "- Term: definition".

                [DETAILED_EXPLANATION]
                Write 3 to 5 paragraphs (separated by blank lines) that deeply explain the most important ideas, with examples.

                [EXAM_POINTS]
                List at least 8 exam-focused takeaways as bullet points starting with "- ".

                [FURTHER_READING]
                List 2-3 recommended resources (books, websites, or topics) as bullet points starting with "- ".

                --- LECTURE CONTENT ---
                %s
                --- END ---
                """
                .formatted(truncateText(lectureText, 12000));
    }

    /**
     * Parses the structured LLM response into a SummaryResponse DTO.
     * Falls back gracefully when the LLM ignores the section headers
     * (common with local Ollama models).
     */
    private SummaryResponse parseResponse(String rawResponse, String fileName, int pageCount) {
        log.debug("Parsing LLM response into structured DTO");

        String title = extractSection(rawResponse, TITLE_HEADER, OVERVIEW_HEADER).trim();
        String overview = extractSection(rawResponse, OVERVIEW_HEADER, KEY_CONCEPTS_HEADER).trim();
        List<String> keyPoints = extractBulletPoints(rawResponse, KEY_CONCEPTS_HEADER, DEFINITIONS_HEADER);
        List<String> definitions = extractBulletPoints(rawResponse, DEFINITIONS_HEADER, DETAILED_HEADER);
        String detailedExplanation = extractSection(rawResponse, DETAILED_HEADER, EXAM_POINTS_HEADER).trim();
        List<String> examPoints = extractBulletPoints(rawResponse, EXAM_POINTS_HEADER, FURTHER_READING_HEADER);
        List<String> furtherReading = extractBulletPoints(rawResponse, FURTHER_READING_HEADER, null);

        // ── Fallback: LLM did not use section headers ────────────────────────
        boolean parsingFailed = title.isEmpty() && overview.isEmpty()
                && keyPoints.isEmpty() && definitions.isEmpty()
                && detailedExplanation.isEmpty() && examPoints.isEmpty();

        if (parsingFailed) {
            log.warn("Section headers not found in LLM response - using raw response as fallback");
            String fallbackTitle = "Lecture Summary - " + fileName.replace(".pdf", "");

            // Pull any bullet lines from the raw response as keyPoints
            List<String> fallbackKeyPoints = Arrays.stream(rawResponse.split("\n"))
                    .map(String::trim)
                    .filter(l -> l.startsWith("- ") || l.startsWith("\u2022 ") || l.startsWith("* "))
                    .map(l -> l.replaceFirst("^[-\u2022*]\\s+", "").trim())
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());

            String fallbackMarkdown = "# " + fallbackTitle + "\n\n" + rawResponse;

            return SummaryResponse.builder()
                    .title(fallbackTitle)
                    .overview("")
                    .keyPoints(fallbackKeyPoints)
                    .definitions(new ArrayList<>())
                    .detailedExplanation(rawResponse)
                    .examPoints(new ArrayList<>())
                    .furtherReading(new ArrayList<>())
                    .markdownSummary(fallbackMarkdown)
                    .fileName(fileName)
                    .provider(llmClient.getActiveProvider())
                    .generatedAt(LocalDateTime.now())
                    .pageCount(pageCount)
                    .build();
        }

        if (title.isEmpty()) {
            title = "Lecture Summary - " + fileName.replace(".pdf", "");
        }

        String markdownSummary = buildMarkdown(title, overview, keyPoints, definitions,
                detailedExplanation, examPoints, furtherReading);

        log.info("Parsed summary: title='{}', keyPoints={}, definitions={}, examPoints={}",
                title, keyPoints.size(), definitions.size(), examPoints.size());

        return SummaryResponse.builder()
                .title(title)
                .overview(overview)
                .keyPoints(keyPoints)
                .definitions(definitions)
                .detailedExplanation(detailedExplanation)
                .examPoints(examPoints)
                .furtherReading(furtherReading)
                .markdownSummary(markdownSummary)
                .fileName(fileName)
                .provider(llmClient.getActiveProvider())
                .generatedAt(LocalDateTime.now())
                .pageCount(pageCount)
                .build();
    }

    /**
     * Assembles a single Markdown string from all parsed sections.
     * The frontend can render this directly with any Markdown library.
     */
    private String buildMarkdown(String title, String overview,
            List<String> keyPoints, List<String> definitions,
            String detailedExplanation,
            List<String> examPoints, List<String> furtherReading) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(title).append("\n\n");

        if (!overview.isBlank()) {
            sb.append("## Overview\n").append(overview).append("\n\n");
        }

        if (!keyPoints.isEmpty()) {
            sb.append("## Key Concepts\n");
            keyPoints.forEach(k -> sb.append("- ").append(k).append("\n"));
            sb.append("\n");
        }

        if (!definitions.isEmpty()) {
            sb.append("## Definitions\n");
            definitions.forEach(d -> sb.append("- ").append(d).append("\n"));
            sb.append("\n");
        }

        if (!detailedExplanation.isBlank()) {
            sb.append("## Detailed Explanation\n").append(detailedExplanation).append("\n\n");
        }

        if (!examPoints.isEmpty()) {
            sb.append("## Exam-Focused Takeaways\n");
            examPoints.forEach(e -> sb.append("- ").append(e).append("\n"));
            sb.append("\n");
        }

        if (!furtherReading.isEmpty()) {
            sb.append("## Further Reading\n");
            furtherReading.forEach(r -> sb.append("- ").append(r).append("\n"));
            sb.append("\n");
        }

        return sb.toString();
    }

    /** Extracts raw text content between two section headers. */
    private String extractSection(String text, String startHeader, String endHeader) {
        int startIdx = text.indexOf(startHeader);
        if (startIdx == -1)
            return "";
        startIdx += startHeader.length();

        if (endHeader != null) {
            int endIdx = text.indexOf(endHeader, startIdx);
            if (endIdx == -1)
                return text.substring(startIdx).trim();
            return text.substring(startIdx, endIdx).trim();
        }
        return text.substring(startIdx).trim();
    }

    /** Extracts bullet-point lines from a section, stripping the leading marker. */
    private List<String> extractBulletPoints(String text, String startHeader, String endHeader) {
        String section = extractSection(text, startHeader, endHeader);
        if (section.isEmpty())
            return new ArrayList<>();

        return Arrays.stream(section.split("\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* "))
                .map(line -> line.replaceFirst("^[-•*]\\s+", "").trim())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    /** Truncates text to a max character count to respect LLM token limits. */
    private String truncateText(String text, int maxChars) {
        if (text.length() <= maxChars)
            return text;
        log.warn("Lecture text truncated from {} to {} characters for LLM context limit.", text.length(), maxChars);
        return text.substring(0, maxChars) + "\n\n[... content truncated due to length ...]";
    }
}