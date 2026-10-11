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
import java.util.Set;

/**
 * Talks to the Python assistant. It sends the message with the conversation state; when the assistant asks
 * for actions (search, add to cart, create a reminder ...) they run here through {@link AssistantTools} as the
 * signed-in user, and the results go back until the assistant has its reply.
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
    private final ObjectMapper mapper;

    public record Result(AssistantReply reply, JsonNode state) {}

    public Result chat(User user, String message, JsonNode payload, JsonNode state) {
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
                    AssistantReply reply = new AssistantReply(res.path("reply").asText(""), res.path("cards"),
                            res.path("suggestions"), res.path("navigate").isTextual() ? res.path("navigate").asText() : null,
                            cartCount, false);
                    return new Result(reply, res.path("state"));
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
        } catch (RestClientException e) {
            log.warn("Assistant service unreachable: {}", e.getMessage());
            return new Result(AssistantReply.unavailable(), state);
        }
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
