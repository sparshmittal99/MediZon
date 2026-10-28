package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medizon.domain.User;
import com.medizon.dto.AssistantReply;
import com.medizon.service.BusinessException;
import com.medizon.service.CartService;
import com.medizon.service.MedicineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Talks to the Python assistant. It sends the message with the conversation state; when the assistant asks
 * for actions (search, add to cart, create a reminder ...) they run here through {@link AssistantTools} as the
 * signed-in user, and the results go back until the assistant has its reply.
 *
 * <p>Hybrid: Python answers first. When it couldn't really answer ("unresolved"), or is down or asleep,
 * {@link ChatFallback} (Groq, then Gemini) answers instead; when both are down or out of quota too, the Python reply
 * (or "offline") stands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final int MAX_ROUNDS = 4;
    private static final Set<String> CART_TOOLS = Set.of("add_to_cart", "refill_plan");

    private final AssistantClient client;
    private final AssistantTools tools;
    private final MedicineService medicineService;
    private final CartService cartService;
    private final ChatFallback llm;
    private final ObjectMapper mapper;

    /** {@code byLlm}: the LLM wrote this reply, so a short answer to it ("yes") should go back to the LLM. */
    public record Result(AssistantReply reply, JsonNode state, boolean byLlm) {
        public Result(AssistantReply reply, JsonNode state) { this(reply, state, false); }
    }

    /** "yes", "no", "ok", "haan", "add it", "the second one": an answer to the last question, not a new request. */
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "^(yes|yeah|yep|yup|ya|yah|ok|okay|okk?|sure|please|pls|haan?|haa|ha|ji|hmm+|no|nope|nah|nahi|nahin|na|"
            + "theek|thik|go|do|add|that|this|the|first|second|third|fourth|last|both|all|it|confirm|done)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final int FOLLOW_UP_WORDS = 5;

    public Result chat(User user, String message, JsonNode payload, JsonNode state) {
        return chat(user, message, payload, state, List.of(), false);
    }

    /**
     * {@code history} is the chat so far, for the LLM; Python keeps its own {@code state}. {@code llmAskedLast}: the
     * previous reply came from the LLM, so a short follow-up goes straight back to it; Python never saw that question.
     */
    public Result chat(User user, String message, JsonNode payload, JsonNode state, List<ChatFallback.Turn> history,
                       boolean llmAskedLast) {
        if (user.isStaff() && !user.isAdmin()) {
            return new Result(AssistantReply.text("The assistant helps customers and the admin. Your account's pages are in the menu above."), state);
        }
        // Sales figures and order look-ups for the admin: only the LLM has those tools
        if (llmAskedLast && isFollowUp(message) || ChatFallback.aboutSales(user, message)) {
            Optional<ChatFallback.Answer> answer = llm.answer(user, message, history);
            if (answer.isPresent()) return new Result(reply(user, answer.get()), state, true);
        }
        ObjectNode req = mapper.createObjectNode();
        req.put("role", user.isAdmin() ? "admin" : "customer");
        req.putObject("user").put("first_name", user.getFirstName());
        req.put("message", message == null ? "" : message);
        req.set("payload", payload != null && payload.isObject() ? payload : mapper.createObjectNode());
        req.set("state", state != null && state.isObject() ? state : mapper.createObjectNode());
        req.set("categories", mapper.valueToTree(medicineService.categories()));
        req.put("now", LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString());

        boolean cartChanged = false;
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                JsonNode res = client.chat(req);
                JsonNode calls = res.path("tool_calls");
                if (!calls.isArray() || calls.isEmpty()) {
                    Long cartCount = cartChanged ? cartService.count(user) : null;
                    String text = res.path("reply").asText("");
                    JsonNode cards = res.path("cards");
                    boolean byLlm = false;
                    if (res.path("unresolved").asBoolean(false)) {
                        // Python couldn't answer: let the LLM, keeping Python's suggestion chips
                        Optional<ChatFallback.Answer> answer = llm.answer(user, message, history);
                        if (answer.isPresent()) {
                            text = answer.get().text();
                            cards = mapper.createArrayNode();
                            if (answer.get().cartChanged()) cartCount = cartService.count(user);
                            byLlm = true;
                        }
                    }
                    AssistantReply reply = new AssistantReply(text, cards,
                            res.path("suggestions"), res.path("navigate").isTextual() ? res.path("navigate").asText() : null,
                            cartCount, false);
                    return new Result(reply, res.path("state"), byLlm);
                }
                ObjectNode results = mapper.createObjectNode();
                for (JsonNode call : calls) {
                    String name = call.path("name").asText();
                    results.set(name, run(name, call.path("args"), user));
                    if (CART_TOOLS.contains(name) && !results.get(name).has("error")) cartChanged = true;
                }
                req.set("state", res.path("state"));
                req.set("tool_results", results);
                req.set("payload", mapper.createObjectNode());
            }
            log.warn("Assistant needed more than {} rounds for: {}", MAX_ROUNDS, message);
            return new Result(AssistantReply.text("That took too many steps. Could you ask in a simpler way?"), null);
        } catch (RuntimeException e) {     // unreachable, a bad answer, anything: chat must never break the page
            if (e instanceof RestClientException) log.warn("Assistant service unreachable: {}", e.getMessage());
            else log.error("Assistant chat failed", e);
            // Python is down or asleep: the LLM answers, and Groq can act too; offline only when all are down
            if (message != null && !message.isBlank()) {
                return llm.answer(user, message, history).map(a -> new Result(reply(user, a), state, true))
                        .orElseGet(() -> new Result(AssistantReply.unavailable(), state));
            }
            return new Result(withoutText(user, payload), state);
        }
    }

    private AssistantReply reply(User user, ChatFallback.Answer a) {
        return a.cartChanged()
                ? new AssistantReply(a.text(), mapper.createArrayNode(), mapper.createArrayNode(), null, cartService.count(user), false)
                : AssistantReply.text(a.text());
    }

    static boolean isFollowUp(String message) {
        if (message == null || message.isBlank()) return false;
        String m = message.strip();
        return m.split("\\s+").length <= FOLLOW_UP_WORDS && FOLLOW_UP.matcher(m).matches();
    }

    /**
     * Opening the chat sends a silent greeting and buttons send a payload, both without text, so the LLM has nothing
     * to answer: say hello (or that buttons need a minute) ourselves while it can still take typed questions.
     */
    private AssistantReply withoutText(User user, JsonNode payload) {
        if (!llm.isAvailable()) return AssistantReply.unavailable();
        if ("greet".equals(payload == null ? null : payload.path("intent").asText(null))) {
            return AssistantReply.text(user.isAdmin()
                    ? "Hi %s! Ask me anything about running MediZon.".formatted(user.getFirstName())
                    : "Hi %s! Ask me about medicines, how to take them, or how to use MediZon.".formatted(user.getFirstName()));
        }
        return AssistantReply.text("Buttons need a minute while the assistant starts up. Please try again shortly, or type your question.");
    }

    private JsonNode run(String name, JsonNode args, User user) {
        try {
            return mapper.valueToTree(tools.execute(name, args, user));
        } catch (BusinessException e) {
            return mapper.createObjectNode().put("error", e.getMessage());
        } catch (Exception e) {
            log.error("Assistant action {} failed", name, e);
            return mapper.createObjectNode().put("error", "Something went wrong doing that. Please try again.");
        }
    }
}
