package com.ai.teachingassistant.service;

import com.ai.teachingassistant.client.PythonRagClient;

import com.ai.teachingassistant.client.LlmClient;
import com.ai.teachingassistant.dto.QuizQuestion;
import com.ai.teachingassistant.dto.QuizResponse;
import com.ai.teachingassistant.dto.QuizSubmitRequest;
import com.ai.teachingassistant.dto.QuizSubmitResponse;
import com.ai.teachingassistant.model.Lecture;
import com.ai.teachingassistant.repository.LectureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QuizService generates MCQ quizzes from a stored lecture's text
 * and evaluates user-submitted answers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

    private final LlmClient llmClient;
    private final LectureRepository lectureRepository;
    private final PythonRagClient pythonRagClient;

    // ── Generate Quiz ────────────────────────────────────────────────────────

    public QuizResponse generateQuiz(String lectureId, String userId, int numQuestions)
            throws IOException, InterruptedException {

        Lecture lecture = lectureRepository.findById(lectureId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Lecture not found: " + lectureId));

        if (userId != null && !userId.equals(lecture.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Access denied to lecture: " + lectureId);
        }

        log.info("Generating {} quiz questions for lecture: {}", numQuestions, lectureId);

        // ── Try RAG context first ────────────────────────────────────────
        String contextText = null;
        try {
            PythonRagClient.QuizContextResponse ragContext =
                    pythonRagClient.getQuizContext(lectureId).block();

            if (ragContext != null && ragContext.getContext() != null
                    && !ragContext.getContext().isBlank()) {
                contextText = ragContext.getContext();
                log.info("Using RAG context ({} chars, {} chunks) for quiz generation",
                        contextText.length(), ragContext.getChunks().size());
            }
        } catch (Exception e) {
            log.warn("RAG context fetch failed, falling back to originalText: {}", e.getMessage());
        }

        // ── Fallback to originalText if RAG fails ───────────────────────
        if (contextText == null || contextText.isBlank()) {
            String originalText = lecture.getOriginalText();
            contextText = originalText != null
                    ? originalText.substring(0, Math.min(12000, originalText.length()))
                    : "";
            log.info("Fallback: using originalText ({} chars) for quiz", contextText.length());
        }

        String prompt = buildQuizPrompt(contextText, numQuestions);
        String rawResponse = llmClient.sendPrompt(prompt);

        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IOException("AI model returned an empty response while generating quiz.");
        }

        List<QuizQuestion> questions = parseQuizResponse(rawResponse);

        if (questions.isEmpty()) {
            throw new IOException("Could not parse quiz questions from AI response. Please try again.");
        }

        log.info("Generated {} questions for lecture: {}", questions.size(), lectureId);

        return QuizResponse.builder()
                .lectureId(lectureId)
                .lectureTitle(lecture.getFileName().replace(".pdf", ""))
                .questions(questions)
                .totalQuestions(questions.size())
                .build();
    }

    // ── Submit & Grade ───────────────────────────────────────────────────────

    public QuizSubmitResponse submitQuiz(String lectureId, String userId,
            List<QuizQuestion> questions,
            QuizSubmitRequest submitRequest) {

        List<String> answers = submitRequest.getAnswers();
        List<QuizSubmitResponse.QuestionResult> results = new ArrayList<>();
        int score = 0;

        for (int i = 0; i < questions.size(); i++) {
            QuizQuestion q = questions.get(i);
            String selected = (i < answers.size()) ? answers.get(i) : null;
            boolean correct = q.getCorrectAnswer().equalsIgnoreCase(selected != null ? selected : "");

            if (correct)
                score++;

            results.add(QuizSubmitResponse.QuestionResult.builder()
                    .index(i)
                    .question(q.getQuestion())
                    .selectedAnswer(selected)
                    .correctAnswer(q.getCorrectAnswer())
                    .correct(correct)
                    .explanation(q.getExplanation())
                    .build());
        }

        int total = questions.size();
        int pct = total > 0 ? (score * 100 / total) : 0;

        String grade;
        if (pct >= 90)
            grade = "Excellent 🏆";
        else if (pct >= 75)
            grade = "Good 👍";
        else if (pct >= 50)
            grade = "Average 📚";
        else
            grade = "Needs Improvement 💪";

        log.info("Quiz submitted for lecture: {}. Score: {}/{} ({}%)", lectureId, score, total, pct);

        return QuizSubmitResponse.builder()
                .score(score)
                .totalQuestions(total)
                .percentage(pct)
                .grade(grade)
                .results(results)
                .build();
    }

    // ── Prompt Builder ───────────────────────────────────────────────────────

    private String buildQuizPrompt(String contextText, int numQuestions) {
        return """
                You are an expert university professor creating a multiple-choice quiz.
                Based ONLY on the lecture content below, generate exactly %d quiz questions.
                Cover different topics and concepts from throughout the content — do not focus on just one section.

                STRICT FORMAT — follow this EXACTLY for each question (no deviations):

                QUESTION 1
                <Write a clear, specific question testing understanding of a concept>
                A) <Option A>
                B) <Option B>
                C) <Option C>
                D) <Option D>
                CORRECT: <A or B or C or D>
                EXPLANATION: <One sentence explaining why the answer is correct, referencing the lecture content>

                QUESTION 2
                ...and so on until QUESTION %d.

                Rules:
                - Questions must be based ONLY on the lecture content below.
                - Test UNDERSTANDING, not just memorization.
                - Each question must have exactly 4 options (A, B, C, D).
                - Wrong options must be plausible but clearly incorrect based on the content.
                - The CORRECT line must contain only a single letter: A, B, C, or D.
                - EXPLANATION must reference specific content from the lecture.
                - Spread questions across different sections/topics in the content.
                - Do NOT add any text outside this format.

                --- LECTURE CONTENT ---
                %s
                --- END ---
                """.formatted(numQuestions, numQuestions, contextText);
    }

    // ── Response Parser ──────────────────────────────────────────────────────

    /**
     * Parses the LLM response into a list of QuizQuestion objects.
     * Uses regex to find each QUESTION block.
     */
    private List<QuizQuestion> parseQuizResponse(String raw) {
        List<QuizQuestion> questions = new ArrayList<>();

        // Match each QUESTION block
        Pattern qPattern = Pattern.compile(
                "QUESTION\\s+(\\d+)\\s*\\n([\\s\\S]*?)(?=QUESTION\\s+\\d+|$)",
                Pattern.CASE_INSENSITIVE);

        Matcher qMatcher = qPattern.matcher(raw);
        int idx = 0;

        while (qMatcher.find()) {
            String block = qMatcher.group(2).trim();
            String[] lines = block.split("\\r?\\n");

            String questionText = "";
            List<String> options = new ArrayList<>();
            String correct = "";
            String explanation = "";

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();

                if (line.toUpperCase().startsWith("A)") || line.toUpperCase().startsWith("A.")) {
                    options.add(line.substring(2).trim());
                } else if (line.toUpperCase().startsWith("B)") || line.toUpperCase().startsWith("B.")) {
                    options.add(line.substring(2).trim());
                } else if (line.toUpperCase().startsWith("C)") || line.toUpperCase().startsWith("C.")) {
                    options.add(line.substring(2).trim());
                } else if (line.toUpperCase().startsWith("D)") || line.toUpperCase().startsWith("D.")) {
                    options.add(line.substring(2).trim());
                } else if (line.toUpperCase().startsWith("CORRECT:")) {
                    correct = line.substring(8).trim().toUpperCase();
                    if (correct.length() > 1)
                        correct = String.valueOf(correct.charAt(0));
                } else if (line.toUpperCase().startsWith("EXPLANATION:")) {
                    explanation = line.substring(12).trim();
                } else if (!line.isEmpty() && questionText.isEmpty()
                        && !line.matches("[A-Da-d][).].*")) {
                    questionText = line;
                }
            }

            if (!questionText.isEmpty() && options.size() == 4 && !correct.isEmpty()) {
                questions.add(QuizQuestion.builder()
                        .index(idx++)
                        .question(questionText)
                        .options(options)
                        .correctAnswer(correct)
                        .explanation(explanation)
                        .build());
            } else {
                log.warn("Skipping malformed question block #{}: q='{}', opts={}, correct='{}'",
                        idx + 1, questionText, options.size(), correct);
            }
        }

        return questions;
    }
}
