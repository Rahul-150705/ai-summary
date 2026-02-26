package com.ai.teachingassistant.config;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * WebClientConfig — provides a pre-configured WebClient bean for
 * reactive HTTP calls to Ollama's streaming API.
 *
 * <h3>WHY WebClient (not RestTemplate)?</h3>
 * <ul>
 * <li>RestTemplate is <b>synchronous and blocking</b>. It reads the entire
 * response body before returning — no way to process chunks as they
 * arrive.</li>
 * <li>WebClient supports <b>reactive streaming</b> via
 * {@code Flux<DataBuffer>}.
 * When Ollama sends NDJSON lines one-by-one with {@code stream: true},
 * WebClient delivers each line as soon as it arrives — enabling true
 * real-time token-by-token streaming to the frontend.</li>
 * <li>WebClient is also <b>non-blocking</b>: it doesn't hold a thread while
 * waiting for new chunks, so the server can handle thousands of concurrent
 * streaming sessions without running out of threads.</li>
 * </ul>
 */
@Configuration
public class WebClientConfig {

    @Value("${ollama.api.url:http://localhost:11434/api/generate}")
    private String ollamaBaseUrl;

    @Bean
    public WebClient ollamaWebClient() {
        // Netty HTTP client with generous timeouts for slow local models
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000) // 10s connect
                .responseTimeout(Duration.ofMinutes(10)); // 10 min response

        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024)) // 2MB buffer per chunk
                .build();
    }
}
