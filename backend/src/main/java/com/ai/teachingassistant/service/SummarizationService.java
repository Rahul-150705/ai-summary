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
     * Maximum characters per chunk sent to the LLM in a single call.
     * ~8,000 chars ≈ ~2,000 tokens — leaves room for prompt + response within
     * typical context windows.
     */
    private static final int CHUNK_SIZE = 8000;

    /**
     * Overlap between adjacent chunks (chars). Ensures we don't lose context
     * at chunk boundaries (e.g. mid-sentence splits).
     */
    private static final int CHUNK_OVERLAP = 500;

    /**
     * If the text is shorter than this, process it in a single pass (no
     * map-reduce overhead needed).
     */
    private static final int SINGLE_PASS_THRESHOLD = 10000;

    /**
     * Generates a structured summary from the extracted lecture text.
     * For long documents, uses a Map-Reduce strategy:
     * 1. Split text into overlapping chunks
     * 2. Summarize each chunk individually (Map phase)
     * 3. Combine chunk summaries into a final structured summary (Reduce phase)
     */
    public SummaryResponse generateSummary(String extractedText, String fileName, int pageCount)
            throws IOException, InterruptedException {

        log.info("Generating summary for file: {} ({} chars, ~{} pages)",
                fileName, extractedText.length(), pageCount);

        String rawResponse;

        if (extractedText.length() <= SINGLE_PASS_THRESHOLD) {
            // ── Short document: single-pass summarization ────────────────
            log.info("Short document — using single-pass summarization.");
            String prompt = buildFinalPrompt(extractedText);
            rawResponse = llmClient.sendPrompt(prompt);
        } else {
            // ── Long document: Map-Reduce chunked summarization ──────────
            log.info("Long document ({} chars) — using Map-Reduce chunked summarization.",
                    extractedText.length());
            rawResponse = mapReduceSummarize(extractedText);
        }

        log.debug("LLM final response received. Length: {} characters",
                rawResponse != null ? rawResponse.length() : 0);

        if (rawResponse == null || rawResponse.isBlank()) {
            log.error("LLM returned an empty response for file: {}", fileName);
            throw new IOException("AI model returned an empty response. Please try again.");
        }

        return parseResponse(rawResponse, fileName, pageCount);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MAP-REDUCE SUMMARIZATION
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Splits the full text into chunks, summarizes each one (MAP), then
     * combines all chunk summaries into a single final summary (REDUCE).
     */
    private String mapReduceSummarize(String fullText) throws IOException, InterruptedException {
        List<String> chunks = splitIntoChunks(fullText, CHUNK_SIZE, CHUNK_OVERLAP);
        log.info("Split document into {} chunks for Map-Reduce.", chunks.size());

        // ── MAP PHASE: summarize each chunk ──────────────────────────────
        List<String> chunkSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            log.info("MAP phase: summarizing chunk {}/{} ({} chars)",
                    i + 1, chunks.size(), chunks.get(i).length());

            String chunkPrompt = buildChunkPrompt(chunks.get(i), i + 1, chunks.size());
            String chunkSummary = llmClient.sendPrompt(chunkPrompt);

            if (chunkSummary != null && !chunkSummary.isBlank()) {
                chunkSummaries.add(chunkSummary.trim());
            } else {
                log.warn("Chunk {}/{} returned empty summary — skipping.", i + 1, chunks.size());
            }
        }

        if (chunkSummaries.isEmpty()) {
            throw new IOException("All chunk summaries were empty. The AI model may be unavailable.");
        }

        // ── REDUCE PHASE: combine chunk summaries into final output ──────
        log.info("REDUCE phase: combining {} chunk summaries into final structured summary.",
                chunkSummaries.size());

        String combinedSummaries = String.join("\n\n---\n\n", chunkSummaries);
        String reducePrompt = buildReducePrompt(combinedSummaries);
        return llmClient.sendPrompt(reducePrompt);
    }

    /**
     * Splits text into overlapping chunks. Tries to break at paragraph or
     * sentence boundaries rather than mid-word.
     */
    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // Try to find a natural break point near the end (paragraph, then sentence)
            if (end < text.length()) {
                int naturalBreak = findNaturalBreak(text, start + (chunkSize / 2), end);
                if (naturalBreak > start) {
                    end = naturalBreak;
                }
            }

            chunks.add(text.substring(start, end).trim());

            // Move start forward, subtracting overlap for context continuity
            start = end - overlap;
            if (start <= 0 && end >= text.length())
                break;
            if (start >= text.length())
                break;
        }

        return chunks;
    }

    /**
     * Finds the best natural break point (double-newline > period+space >
     * single newline) searching backward from {@code end} to {@code earliest}.
     */
    private int findNaturalBreak(String text, int earliest, int end) {
        // Prefer paragraph boundary (\n\n)
        int idx = text.lastIndexOf("\n\n", end);
        if (idx >= earliest)
            return idx + 2;

        // Next: sentence boundary (. followed by space or newline)
        idx = text.lastIndexOf(". ", end);
        if (idx >= earliest)
            return idx + 2;

        // Fallback: any newline
        idx = text.lastIndexOf("\n", end);
        if (idx >= earliest)
            return idx + 1;

        return end; // no good break found — hard cut
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROMPTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Prompt for the MAP phase — summarize a single chunk.
     * Asks for a concise but thorough summary without the structured headers
     * (those are applied in the REDUCE phase).
     */
    private String buildChunkPrompt(String chunkText, int chunkNumber, int totalChunks) {
        return """
                You are an expert university-level teaching assistant.
                You are reading PART %d of %d of a lecture document.

                Summarize this section thoroughly. Include:
                - All key concepts and ideas mentioned
                - Important definitions and terminology
                - Any examples or case studies
                - Exam-worthy points

                Write in clear, full sentences. Be comprehensive — do not skip important details.
                Keep your summary between 300-600 words.

                --- LECTURE SECTION %d/%d ---
                %s
                --- END SECTION ---
                """
                .formatted(chunkNumber, totalChunks, chunkNumber, totalChunks, chunkText);
    }

    /**
     * Prompt for the REDUCE phase — combine all chunk summaries into the
     * final structured output using the same section markers the frontend
     * expects.
     */
    private String buildReducePrompt(String combinedSummaries) {
        return """
                You are an expert university-level teaching assistant.
                Below are summaries of different sections of a lecture document.
                Your job is to combine them into ONE comprehensive, well-structured summary.

                Eliminate redundancy. Merge overlapping points. Ensure nothing important is lost.

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

                --- SECTION SUMMARIES ---
                %s
                --- END ---
                """
                .formatted(combinedSummaries);
    }

    /**
     * Prompt for short documents — single-pass structured summarization.
     * (Same as the original buildPrompt.)
     */
    private String buildFinalPrompt(String lectureText) {
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

                --- LECTURE CONTENT ---
                %s
                --- END ---
                """
                .formatted(lectureText);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RESPONSE PARSING
    // ═════════════════════════════════════════════════════════════════════════

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
}