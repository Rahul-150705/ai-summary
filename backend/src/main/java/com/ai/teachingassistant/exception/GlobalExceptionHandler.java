package com.ai.teachingassistant.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * GlobalExceptionHandler provides centralized exception handling across all
 * controllers, ensuring consistent error responses are returned to the client.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles file size exceeded exceptions from Spring multipart upload.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxSizeException(MaxUploadSizeExceededException ex) {
        log.warn("Upload size exceeded: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "File size exceeds the 10MB limit. Please upload a smaller PDF.",
                "MAX_UPLOAD_SIZE_EXCEEDED"
        );
    }

    /**
     * Handles IO exceptions from PDF processing.
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException ex) {
        log.error("IO error during processing: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "File processing error: " + ex.getMessage(),
                "PDF_PROCESSING_ERROR"
        );
    }

    /**
     * Handles thread interruption exceptions from LLM API calls.
     */
    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<Map<String, Object>> handleInterruptedException(InterruptedException ex) {
        Thread.currentThread().interrupt();
        log.error("API call was interrupted: {}", ex.getMessage());
        return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AI service request was interrupted. Please try again.",
                "SERVICE_INTERRUPTED"
        );
    }

    /**
     * Catch-all handler for unexpected exceptions.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.",
                "INTERNAL_SERVER_ERROR"
        );
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String message, String errorCode) {
        return ResponseEntity.status(status).body(Map.of(
                "error", message,
                "errorCode", errorCode,
                "status", status.value(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}