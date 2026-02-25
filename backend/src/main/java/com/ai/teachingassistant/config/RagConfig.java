package com.ai.teachingassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RagConfig wires the Spring AI ChatClient to Ollama explicitly.
 *
 * Why this is needed:
 * Both spring-ai-openai-spring-boot-starter and spring-ai-ollama-spring-boot-starter
 * are on the classpath. Spring AI cannot automatically decide which ChatModel to use
 * for the ChatClient bean, so we declare it explicitly here — always using Ollama
 * (local, free, no API key needed) for RAG Q&A.
 *
 * The LlmClient (used for summarization) is a separate custom HTTP client and
 * is controlled by the 'llm.provider' property in application.properties / .env.
 */
@Configuration
public class RagConfig {

    /**
     * Creates a ChatClient backed by the local Ollama model (llama3.2).
     *
     * OllamaChatModel is auto-configured by spring-ai-ollama-spring-boot-starter
     * using the properties:
     *   spring.ai.ollama.base-url=http://localhost:11434
     *   spring.ai.ollama.chat.options.model=llama3.2
     *   spring.ai.ollama.chat.options.temperature=0.2
     *
     * @param ollamaChatModel injected automatically by Spring AI Ollama auto-config
     * @return a ChatClient that sends prompts to Ollama
     */
    @Bean
    public ChatClient chatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }
}
