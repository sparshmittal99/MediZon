package com.medizon.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medizon.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The LLM half of the hybrid assistant. The Python assistant answers first (free, instant, and it can act: search,
 * cart, reminders); Gemini only answers what Python couldn't, or everything while Python is down. It can't act, so it
 * points people to the right page instead. Never throws: empty means "keep the Python reply" or "offline".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiChat {

    private static final int MAX_MESSAGE = 1000;
    private static final int MAX_REPLY = 1500;

    private static final String CUSTOMER = """
            You are the chat assistant of MediZon, an online pharmacy in India. You are talking to a customer named %s.
            Rules:
            - You are not a doctor. Give general, widely accepted information only. Never diagnose, never suggest
              starting, stopping or changing a dose of a prescribed medicine, and suggest asking their doctor or our
              pharmacist for anything personal (pregnancy, children, other medicines, alcohol, existing conditions).
            - For emergencies (chest pain, trouble breathing, overdose, severe bleeding, fainting, thoughts of self-harm)
              tell them to call 112 or go to the nearest hospital right now, before anything else.
            - You can't see their orders, cart, stock or prices, and you can't do anything in the app. Never make these up.
              Point them to the page instead: Shop /medicines, Cart /cart, Orders /orders, Prescriptions /prescriptions
              (upload one and its medicines are suggested for the cart automatically; a pharmacist verifies the prescription), Dose reminders /plans,
              Help and support /support.
            - Answer only questions about health, medicines and shopping with MediZon; politely decline anything else.
            - Plain text, no markdown, at most 120 words. Reply in the language the customer used (Hindi and Hinglish are fine).
            """;

    private static final String ADMIN = """
            You are the chat assistant of MediZon, an online pharmacy in India, talking to the store's admin, %s.
            You can't see store data here. For numbers point them to Admin pages: Orders /admin/orders, Sales
            /admin/sales, Catalog /admin/medicines, Prescriptions /admin/prescriptions, Reviews /admin/reviews,
            Monitoring /admin/monitoring. Help with general pharmacy operations and how to use MediZon.
            Never make up figures. Plain text, no markdown, at most 120 words.
            """;

    private final GeminiClient gemini;
    private final ObjectMapper json;

    public boolean isAvailable() { return gemini.isAvailable(); }

    public Optional<String> answer(User user, String message) {
        if (message == null || message.isBlank() || !gemini.isAvailable()) return Optional.empty();
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject()
                .put("text", (user.isAdmin() ? ADMIN : CUSTOMER).formatted(user.getFirstName()));
        body.putArray("contents").addObject().put("role", "user").putArray("parts").addObject()
                .put("text", message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message);
        body.putObject("generationConfig").put("temperature", 0.3);
        try {
            String text = gemini.generate(body).strip();
            return Optional.of(text.length() > MAX_REPLY ? text.substring(0, MAX_REPLY) + "…" : text);
        } catch (RuntimeException e) {
            log.warn("Gemini chat fallback failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
