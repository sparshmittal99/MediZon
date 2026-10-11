package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the MediZon Assistant service (the Python app in /medizon-assistant). */
@Component
public class AssistantClient {

    private final RestClient http;

    public AssistantClient(@Value("${medizon.assistant.url:http://localhost:8001}") String baseUrl,
                           @Value("${medizon.assistant.token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(15_000);
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (!token.isBlank()) builder.defaultHeader("X-Assistant-Token", token);
        this.http = builder.build();
    }

    public JsonNode chat(ObjectNode request) {
        return http.post()
                .uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }
}
