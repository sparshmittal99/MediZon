package com.medizon.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medizon.ai.AssistantService;
import com.medizon.ai.ChatFallback;
import com.medizon.dto.AssistantReply;
import com.medizon.service.CurrentUser;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON endpoints for the chat widget. The conversation state and a short transcript live in the HTTP
 * session, so the chat carries on as the user moves between pages.
 */
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantApi {

    private static final String STATE = "assistantState";
    /** The last reply came from the LLM fallback (Groq or Gemini), not the Python assistant. */
    private static final String LLM_LAST = "assistantLlmLast";
    private static final String TRANSCRIPT = "assistantTranscript";
    private static final int MAX_MESSAGES = 40;
    private static final int MAX_LENGTH = 1000;

    private final AssistantService assistant;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper;

    /** {@code label} is the text of a pressed button, shown as the user's message. */
    public record ChatRequest(String message, JsonNode payload, String label) {}

    @PostMapping("/chat")
    public ResponseEntity<AssistantReply> chat(@RequestBody ChatRequest req, HttpSession session) {
        String msg = req.message() == null ? "" : req.message().trim();
        boolean hasPayload = req.payload() != null && req.payload().isObject() && req.payload().size() > 0;
        if (msg.isEmpty() && !hasPayload) return ResponseEntity.badRequest().body(AssistantReply.text("Type a message first."));
        if (msg.length() > MAX_LENGTH) msg = msg.substring(0, MAX_LENGTH);

        AssistantService.Result result = assistant.chat(currentUser.get(), msg, req.payload(), state(session), llmHistory(session),
                Boolean.TRUE.equals(session.getAttribute(LLM_LAST)));
        if (result.state() != null && result.state().isObject()) session.setAttribute(STATE, result.state().toString());
        else session.removeAttribute(STATE);
        if (result.byLlm()) session.setAttribute(LLM_LAST, true);
        else session.removeAttribute(LLM_LAST);

        String shown = !msg.isEmpty() ? msg : req.label();
        List<String> transcript = transcript(session);
        if (shown != null && !shown.isBlank() && !isGreeting(req.payload())) {
            transcript.add(mapper.createObjectNode().put("from", "user").put("text", shown).toString());
        }
        AssistantReply reply = result.reply();
        ObjectNode bot = mapper.createObjectNode().put("from", "bot").put("text", reply.reply());
        bot.set("cards", reply.cards());
        bot.set("suggestions", reply.suggestions());
        transcript.add(bot.toString());
        while (transcript.size() > MAX_MESSAGES) transcript.remove(0);
        session.setAttribute(TRANSCRIPT, transcript);
        return ResponseEntity.ok(reply);
    }

    @GetMapping("/history")
    public Map<String, Object> history(HttpSession session) {
        ArrayNode messages = mapper.createArrayNode();
        for (String json : transcript(session)) {
            try {
                messages.add(mapper.readTree(json));
            } catch (JsonProcessingException ignored) { }
        }
        return Map.of("messages", messages);
    }

    @PostMapping("/reset")
    public Map<String, Boolean> reset(HttpSession session) {
        session.removeAttribute(STATE);
        session.removeAttribute(TRANSCRIPT);
        session.removeAttribute(LLM_LAST);
        return Map.of("ok", true);
    }

    private JsonNode state(HttpSession session) {
        Object s = session.getAttribute(STATE);
        if (!(s instanceof String json)) return null;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** The transcript as the LLM fallback reads it; a reply's medicine cards are listed so "the second one" resolves. */
    private List<ChatFallback.Turn> llmHistory(HttpSession session) {
        List<ChatFallback.Turn> turns = new ArrayList<>();
        for (String json : transcript(session)) {
            try {
                JsonNode m = mapper.readTree(json);
                StringBuilder text = new StringBuilder(m.path("text").asText(""));
                List<String> shown = new ArrayList<>();
                for (JsonNode c : m.path("cards")) {
                    if (!c.path("title").asText("").isBlank()) {
                        shown.add(c.path("title").asText() + (c.hasNonNull("id") ? " (id " + c.get("id").asText() + ")" : ""));
                    }
                }
                if (!shown.isEmpty()) text.append("\n[Showed: ").append(String.join("; ", shown)).append("]");
                turns.add(new ChatFallback.Turn("user".equals(m.path("from").asText()), text.toString()));
            } catch (JsonProcessingException ignored) { }
        }
        return turns;
    }

    @SuppressWarnings("unchecked")
    private List<String> transcript(HttpSession session) {
        Object t = session.getAttribute(TRANSCRIPT);
        return t instanceof List<?> ? new ArrayList<>((List<String>) t) : new ArrayList<>();
    }

    private static boolean isGreeting(JsonNode payload) {
        return payload != null && "greet".equals(payload.path("intent").asText());
    }
}
