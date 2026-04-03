package com.ai.teachingassistant.client;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PythonRagClient — Bridges Java Spring Boot with the Python FastAPI RAG Service.
 * Handles PDF indexing and advanced query with Reranking.
 */
@Slf4j
@Service
public class PythonRagClient {

    private final WebClient webClient;

    public PythonRagClient(WebClient.Builder webClientBuilder, 
                           @Value("${python.rag.url:http://localhost:8000}") String pythonBaseUrl) {
        this.webClient = webClientBuilder.baseUrl(pythonBaseUrl).build();
    }

    /**
     * Uploads a PDF to Python service for Chunker -> Embedding -> Vector DB storage.
     */
    public Mono<String> indexPdf(String lectureId, MultipartFile file) {
        log.info("Delegating PDF indexing to Python for lectureId={}", lectureId);
        
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("lecture_id", lectureId);
        builder.part("file", file.getResource());

        return webClient.post()
                .uri("/add")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(res -> log.info("Python Indexing Success: {}", res))
                .doOnError(err -> log.error("Python Indexing Failed: {}", err.getMessage()));
    }

    /**
     * Calls Python service for Vector Search -> Reranking -> Ollama Generation.
     */
    public Mono<QueryResponse> query(String question, String lectureId) {
        log.info("Sending advanced RAG query to Python: '{}'", question);

        QueryRequest request = QueryRequest.builder()
                .question(question)
                .lectureId(lectureId)
                .build();

        return webClient.post()
                .uri("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(QueryResponse.class);
    }

    /**
     * Calls Python service for Vector Search -> Reranking for Quiz Generation Context.
     */
    public Mono<QuizContextResponse> getQuizContext(String lectureId) {
        log.info("Fetching quiz context from Python for lectureId={}", lectureId);

        QueryRequest request = QueryRequest.builder()
                .question("main topics key concepts important facts definitions")
                .lectureId(lectureId)
                .build();

        return webClient.post()
                .uri("/quiz-context")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(QuizContextResponse.class)
                .doOnError(err -> log.error("Quiz context fetch failed: {}", err.getMessage()));
    }

    // --- DTOs ---

    @Data
    @Builder
    public static class QueryRequest {
        private String question;
        
        @JsonProperty("lecture_id")
        private String lectureId;
    }

    @Data
    public static class QueryResponse {
        private String answer;
        private List<String> chunks;
    }

    @Data
    public static class QuizContextResponse {
        private List<String> chunks;
        private String context;
    }
}
