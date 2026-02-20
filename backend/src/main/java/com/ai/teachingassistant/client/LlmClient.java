package com.ai.teachingassistant.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LlmClient acts as a pluggable AI provider interface using the Strategy pattern.
 * Supports OpenAI GPT-4, Anthropic Claude, and Google Gemini.
 * Provider is selected at runtime via the 'llm.provider' configuration property.
 */
@Slf4j
@Component
public class LlmClient {

    @Value("${llm.provider:openai}")
    private String provider;

    // OpenAI
    @Value("${openai.api.key:}")
    private String openAiApiKey;
    @Value("${openai.api.url:https://api.openai.com/v1/chat/completions}")
    private String openAiApiUrl;
    @Value("${openai.model:gpt-4}")
    private String openAiModel;

    // Claude
    @Value("${claude.api.key:}")
    private String claudeApiKey;
    @Value("${claude.api.url:https://api.anthropic.com/v1/messages}")
    private String claudeApiUrl;
    @Value("${claude.model:claude-sonnet-4-6}")
    private String claudeModel;

    // Gemini
    @Value("${gemini.api.key:}")
    private String geminiApiKey;
    @Value("${gemini.api.url:https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent}")
    private String geminiApiUrl;
    // Ollama (local - free, no API key needed)
    @Value("${ollama.api.url:http://localhost:11434/api/generate}")
    private String ollamaApiUrl;

    @Value("${ollama.model:llama3.2}")
    private String ollamaModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Send a prompt to the configured LLM provider and return the raw text response.
     *
     * @param prompt The constructed prompt string.
     * @return AI-generated text response.
     * @throws IOException If the API call fails.
     */
    public String sendPrompt(String prompt) throws IOException, InterruptedException {
        log.info("Sending prompt to LLM provider: {}", provider);
        return switch (provider.toLowerCase()) {
            case "claude"  -> callClaude(prompt);
            case "gemini"  -> callGemini(prompt);
            case "ollama"  -> callOllama(prompt);
            default        -> callOpenAi(prompt);
        };
    }

    /**
     * Returns which provider is currently active.
     */
    public String getActiveProvider() {
        return provider;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // OpenAI GPT-4
    // ─────────────────────────────────────────────────────────────────────────────
    private String callOpenAi(String prompt) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", openAiModel);
        body.put("max_tokens", 2000);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode systemMsg = objectMapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are an expert educational assistant that summarizes lecture content clearly and concisely.");
        messages.add(systemMsg);

        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);
        body.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(openAiApiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiApiKey)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("OpenAI response status: {}", response.statusCode());

        if (response.statusCode() != 200) {
            throw new IOException("OpenAI API error [" + response.statusCode() + "]: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.at("/choices/0/message/content").asText();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Anthropic Claude
    // ─────────────────────────────────────────────────────────────────────────────
    private String callClaude(String prompt) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", claudeModel);
        body.put("max_tokens", 2000);

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);
        body.set("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(claudeApiUrl))
                .header("Content-Type", "application/json")
                .header("x-api-key", claudeApiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Claude response status: {}", response.statusCode());

        if (response.statusCode() != 200) {
            throw new IOException("Claude API error [" + response.statusCode() + "]: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.at("/content/0/text").asText();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Google Gemini
    // ─────────────────────────────────────────────────────────────────────────────
    private String callGemini(String prompt) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode contentItem = objectMapper.createObjectNode();
        ArrayNode parts = objectMapper.createArrayNode();
        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("text", prompt);
        parts.add(textPart);
        contentItem.set("parts", parts);
        contents.add(contentItem);
        body.set("contents", contents);

        String urlWithKey = geminiApiUrl + "?key=" + geminiApiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlWithKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Gemini response status: {}", response.statusCode());

        if (response.statusCode() != 200) {
            throw new IOException("Gemini API error [" + response.statusCode() + "]: " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        return root.at("/candidates/0/content/parts/0/text").asText();
    }
    // ─────────────────────────────────────────────────────────────────────────────
// Ollama (Local - Free)
// ─────────────────────────────────────────────────────────────────────────────
private String callOllama(String prompt) throws IOException, InterruptedException {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", ollamaModel);
    body.put("prompt", prompt);
    body.put("stream", false);  // get full response at once

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ollamaApiUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))  // local models can be slow
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    log.debug("Ollama response status: {}", response.statusCode());

    if (response.statusCode() != 200) {
        throw new IOException("Ollama API error [" + response.statusCode() + "]: " + response.body());
    }

    JsonNode root = objectMapper.readTree(response.body());
    return root.at("/response").asText();
}
}