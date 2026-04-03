package com.ai.teachingassistant.controller;

import com.ai.teachingassistant.client.PythonRagClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

/**
 * AdvancedRagController — Exposes production-grade RAG features via Python FastAPI.
 */
@Slf4j
@RestController
@RequestMapping("/api/advanced-rag")
@RequiredArgsConstructor
public class AdvancedRagController {

    private final PythonRagClient pythonRagClient;
    private final com.ai.teachingassistant.service.LectureService lectureService;

    /**
     * POST /api/advanced-rag/index
     * Indices a PDF using Python (BGE Embeddings -> Neon pgvector).
     */
    @PostMapping("/index")
    public Mono<ResponseEntity<String>> indexDoc(
            @RequestParam("lectureId") String lectureId,
            @RequestParam("file") MultipartFile file) {
        
        return pythonRagClient.indexPdf(lectureId, file)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.internalServerError().body(e.getMessage())));
    }

    /**
     * POST /api/advanced-rag/query
     * Queries the RAG pipeline (Vector Search -> Reranking -> Ollama).
     */
    @PostMapping("/query")
    public Mono<ResponseEntity<PythonRagClient.QueryResponse>> queryRag(
            @RequestBody PythonRagClient.QueryRequest request,
            java.security.Principal principal) {
        
        String userId = (principal != null) ? principal.getName() : null;
        log.info("Advanced Q&A request from user={}: lectureId={}", userId, request.getLectureId());

        // We fetch the lecture just to get its content fingerprint (hash)
        com.ai.teachingassistant.model.Lecture lecture = lectureService.getLectureById(request.getLectureId(), userId);

        return pythonRagClient.query(request.getQuestion(), lecture.getId())
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.error("Advanced RAG query failed: {}", e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }
}
