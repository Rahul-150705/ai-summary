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

    // ── Section markers used to parse the LLM response ───────────────────
    private static final String TITLE_HEADER              = "[TITLE]";
    private static final String MAIN_TOPIC_HEADER         = "[MAIN_TOPIC]";
    private static final String KEY_POINTS_HEADER         = "[KEY_POINTS]";
    private static final String IMPORTANT_DETAILS_HEADER  = "[IMPORTANT_DETAILS]";
    private static final String STRUCTURE_OVERVIEW_HEADER = "[STRUCTURE_OVERVIEW]";
    private static final String CONCLUSIONS_HEADER        = "[CONCLUSIONS]";
    private static final String NOTABLE_QUOTES_HEADER     = "[NOTABLE_QUOTES]";
    private static final String ADDITIONAL_NOTES_HEADER   = "[ADDITIONAL_NOTES]";

    /** Max characters per chunk sent to the LLM in a single call (~1000 tokens). */
    private static final int CHUNK_SIZE = 4000;

    /** Overlap between adjacent chunks (chars). */
    private static final int CHUNK_OVERLAP = 200;

    /** Below this threshold, use single-pass (no map-reduce overhead). */
    private static final int SINGLE_PASS_THRESHOLD = 5000;

    /**
     * Generates a structured summary from the extracted document text.
     * For long documents, uses a Map-Reduce strategy.
     */
    public SummaryResponse generateSummary(String extractedText, String fileName, int pageCount)
            throws IOException, InterruptedException {

        log.info("Generating summary for file: {} ({} chars, ~{} pages)",
                fileName, extractedText.length(), pageCount);

        String rawResponse;

        if (extractedText.length() <= SINGLE_PASS_THRESHOLD) {
            log.info("Short document — using single-pass summarization.");
            String prompt = buildFinalPrompt(extractedText);
            rawResponse = llmClient.sendPrompt(prompt);
        } else {
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

    private String mapReduceSummarize(String fullText) throws IOException, InterruptedException {
        List<String> chunks = splitIntoChunks(fullText, CHUNK_SIZE, CHUNK_OVERLAP);
        log.info("Split document into {} chunks for Map-Reduce.", chunks.size());

        // ── MAP PHASE ─────────────────────────────────────────────────────
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

        // ── REDUCE PHASE ──────────────────────────────────────────────────
        log.info("REDUCE phase: combining {} chunk summaries into final structured summary.",
                chunkSummaries.size());

        String combinedSummaries = String.join("\n\n---\n\n", chunkSummaries);
        String reducePrompt = buildReducePrompt(combinedSummaries);
        return llmClient.sendPrompt(reducePrompt);
    }

    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            if (end < text.length()) {
                int naturalBreak = findNaturalBreak(text, start + (chunkSize / 2), end);
                if (naturalBreak > start) {
                    end = naturalBreak;
                }
            }

            chunks.add(text.substring(start, end).trim());

            start = end - overlap;
            if (start <= 0 && end >= text.length()) break;
            if (start >= text.length()) break;
        }

        return chunks;
    }

    private int findNaturalBreak(String text, int earliest, int end) {
        int idx = text.lastIndexOf("\n\n", end);
        if (idx >= earliest) return idx + 2;

        idx = text.lastIndexOf(". ", end);
        if (idx >= earliest) return idx + 2;

        idx = text.lastIndexOf("\n", end);
        if (idx >= earliest) return idx + 1;

        return end;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROMPTS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * MAP phase prompt — extract key information from a single chunk.
     */
    private String buildChunkPrompt(String chunkText, int chunkNumber, int totalChunks) {
        return """
                You are an expert document analyst. You are reading PART %d of %d of a document.

                Analyze this section thoroughly and extract:
                - Main topics and purpose of this section
                - All key points, findings, or arguments
                - Specific data, statistics, dates, names, or figures
                - Any conclusions or recommendations
                - Any notable or standout quotes
                - Anything unusual, important, or easily overlooked

                Write in clear, full sentences. Be comprehensive — do not skip important details.
                Do not skip anything. If something is unclear, mention it.
                Keep your summary between 300-600 words.

                --- DOCUMENT SECTION %d/%d ---
                %s
                --- END SECTION ---
                """
                .formatted(chunkNumber, totalChunks, chunkNumber, totalChunks, chunkText);
    }

    /**
     * REDUCE phase prompt — combine chunk summaries into the structured output.
     */
    private String buildReducePrompt(String combinedSummaries) {
        return """
                You are an expert document analyst.
                Below are summaries of different sections of a document.
                Your job is to combine them into ONE comprehensive, well-structured analysis.

                Eliminate redundancy. Merge overlapping points. Ensure nothing important is lost.

                Use EXACTLY these section markers on their own line. Start each section on a new line.
                Write thoroughly. Do not skip any section. If something is unclear, mention it.

                [TITLE]
                Write a short, descriptive title for this document.

                [MAIN_TOPIC]
                What is this document about and what is its goal? Write 3-5 sentences explaining the main topic, purpose, and scope of this document.

                [KEY_POINTS]
                List ALL major points, findings, or arguments made in the document as bullet points starting with "- ". Each bullet must be a full sentence. Include at least 8 points.

                [IMPORTANT_DETAILS]
                List specific data, statistics, dates, names, or figures mentioned in the document as bullet points starting with "- ". Be precise and factual. Include at least 6 details.

                [STRUCTURE_OVERVIEW]
                Briefly describe how the document is organized. Mention sections, chapters, or logical divisions. Write 2-4 sentences.

                [CONCLUSIONS]
                What conclusions or recommendations does the document present? List them as bullet points starting with "- ". Include action items if any.

                [NOTABLE_QUOTES]
                Pull out any critical or standout statements from the document as bullet points starting with "- ". Quote them as closely to the original as possible.

                [ADDITIONAL_NOTES]
                Flag anything unusual, important, or easily overlooked as bullet points starting with "- ". Mention unclear sections or gaps if any.

                --- SECTION SUMMARIES ---
                %s
                --- END ---
                """
                .formatted(combinedSummaries);
    }

    /**
     * Single-pass prompt for short documents — full thorough analysis.
     */
    private String buildFinalPrompt(String lectureText) {
        return """
                You are an expert document analyst.
                Analyze the document content below thoroughly and provide a complete, detailed summary.

                Use EXACTLY these section markers on their own line. Start each section on a new line.
                Write thoroughly. Do not skip any section. If something is unclear, mention it.

                [TITLE]
                Write a short, descriptive title for this document.

                [MAIN_TOPIC]
                What is this document about and what is its goal? Write 3-5 sentences explaining the main topic, purpose, and scope of this document.

                [KEY_POINTS]
                List ALL major points, findings, or arguments made in the document as bullet points starting with "- ". Each bullet must be a full sentence. Include at least 8 points.

                [IMPORTANT_DETAILS]
                List specific data, statistics, dates, names, or figures mentioned in the document as bullet points starting with "- ". Be precise and factual. Include at least 6 details.

                [STRUCTURE_OVERVIEW]
                Briefly describe how the document is organized. Mention sections, chapters, or logical divisions. Write 2-4 sentences.

                [CONCLUSIONS]
                What conclusions or recommendations does the document present? List them as bullet points starting with "- ". Include action items if any.

                [NOTABLE_QUOTES]
                Pull out any critical or standout statements from the document as bullet points starting with "- ". Quote them as closely to the original as possible.

                [ADDITIONAL_NOTES]
                Flag anything unusual, important, or easily overlooked as bullet points starting with "- ". Mention unclear sections or gaps if any.

                --- DOCUMENT CONTENT ---
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
     * Falls back gracefully when the LLM ignores the section headers.
     */
    private SummaryResponse parseResponse(String rawResponse, String fileName, int pageCount) {
        log.debug("Parsing LLM response into structured DTO");

        String title             = extractSection(rawResponse, TITLE_HEADER, MAIN_TOPIC_HEADER).trim();
        String mainTopic         = extractSection(rawResponse, MAIN_TOPIC_HEADER, KEY_POINTS_HEADER).trim();
        List<String> keyPoints   = extractBulletPoints(rawResponse, KEY_POINTS_HEADER, IMPORTANT_DETAILS_HEADER);
        List<String> importantDetails = extractBulletPoints(rawResponse, IMPORTANT_DETAILS_HEADER, STRUCTURE_OVERVIEW_HEADER);
        String structureOverview = extractSection(rawResponse, STRUCTURE_OVERVIEW_HEADER, CONCLUSIONS_HEADER).trim();
        List<String> conclusions = extractBulletPoints(rawResponse, CONCLUSIONS_HEADER, NOTABLE_QUOTES_HEADER);
        List<String> notableQuotes = extractBulletPoints(rawResponse, NOTABLE_QUOTES_HEADER, ADDITIONAL_NOTES_HEADER);
        List<String> additionalNotes = extractBulletPoints(rawResponse, ADDITIONAL_NOTES_HEADER, null);

        // ── Fallback: LLM did not use section headers ──────────────────────
        boolean parsingFailed = title.isEmpty() && mainTopic.isEmpty()
                && keyPoints.isEmpty() && importantDetails.isEmpty()
                && structureOverview.isEmpty() && conclusions.isEmpty();

        if (parsingFailed) {
            log.warn("Section headers not found in LLM response — using raw response as fallback");
            String fallbackTitle = "Document Summary — " + fileName.replace(".pdf", "");

            List<String> fallbackKeyPoints = Arrays.stream(rawResponse.split("\n"))
                    .map(String::trim)
                    .filter(l -> l.startsWith("- ") || l.startsWith("• ") || l.startsWith("* "))
                    .map(l -> l.replaceFirst("^[-•*]\\s+", "").trim())
                    .filter(l -> !l.isEmpty())
                    .collect(Collectors.toList());

            String fallbackMarkdown = "# " + fallbackTitle + "\n\n" + rawResponse;

            return SummaryResponse.builder()
                    .title(fallbackTitle)
                    .mainTopic("")
                    .keyPoints(fallbackKeyPoints)
                    .importantDetails(new ArrayList<>())
                    .structureOverview("")
                    .conclusions(new ArrayList<>())
                    .notableQuotes(new ArrayList<>())
                    .additionalNotes(new ArrayList<>())
                    .markdownSummary(fallbackMarkdown)
                    .fileName(fileName)
                    .provider(llmClient.getActiveProvider())
                    .generatedAt(LocalDateTime.now())
                    .pageCount(pageCount)
                    .build();
        }

        if (title.isEmpty()) {
            title = "Document Summary — " + fileName.replace(".pdf", "");
        }

        String markdownSummary = buildMarkdown(title, mainTopic, keyPoints, importantDetails,
                structureOverview, conclusions, notableQuotes, additionalNotes);

        log.info("Parsed summary: title='{}', keyPoints={}, importantDetails={}, conclusions={}",
                title, keyPoints.size(), importantDetails.size(), conclusions.size());

        return SummaryResponse.builder()
                .title(title)
                .mainTopic(mainTopic)
                .keyPoints(keyPoints)
                .importantDetails(importantDetails)
                .structureOverview(structureOverview)
                .conclusions(conclusions)
                .notableQuotes(notableQuotes)
                .additionalNotes(additionalNotes)
                .markdownSummary(markdownSummary)
                .fileName(fileName)
                .provider(llmClient.getActiveProvider())
                .generatedAt(LocalDateTime.now())
                .pageCount(pageCount)
                .build();
    }

    /**
     * Assembles a single Markdown string from all parsed sections.
     */
    private String buildMarkdown(String title, String mainTopic,
            List<String> keyPoints, List<String> importantDetails,
            String structureOverview,
            List<String> conclusions, List<String> notableQuotes,
            List<String> additionalNotes) {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(title).append("\n\n");

        if (!mainTopic.isBlank()) {
            sb.append("## Main Topic & Purpose\n").append(mainTopic).append("\n\n");
        }

        if (!keyPoints.isEmpty()) {
            sb.append("## Key Points & Arguments\n");
            keyPoints.forEach(k -> sb.append("- ").append(k).append("\n"));
            sb.append("\n");
        }

        if (!importantDetails.isEmpty()) {
            sb.append("## Important Details\n");
            importantDetails.forEach(d -> sb.append("- ").append(d).append("\n"));
            sb.append("\n");
        }

        if (!structureOverview.isBlank()) {
            sb.append("## Structure Overview\n").append(structureOverview).append("\n\n");
        }

        if (!conclusions.isEmpty()) {
            sb.append("## Conclusions & Recommendations\n");
            conclusions.forEach(c -> sb.append("- ").append(c).append("\n"));
            sb.append("\n");
        }

        if (!notableQuotes.isEmpty()) {
            sb.append("## Notable Quotes\n");
            notableQuotes.forEach(q -> sb.append("- ").append(q).append("\n"));
            sb.append("\n");
        }

        if (!additionalNotes.isEmpty()) {
            sb.append("## Additional Notes\n");
            additionalNotes.forEach(n -> sb.append("- ").append(n).append("\n"));
            sb.append("\n");
        }

        return sb.toString();
    }

    /** Extracts raw text content between two section headers. */
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

    /** Extracts bullet-point lines from a section, stripping the leading marker. */
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
}