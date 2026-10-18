package com.medizon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medizon.domain.User;
import com.medizon.service.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;

/**
 * The LLM half of the hybrid assistant. The Python assistant answers first (free, instant); an LLM only answers what
 * Python couldn't, or everything while Python is down or asleep. Groq goes first and can act through the same
 * {@link AssistantTools} Python uses (orders, returns, cart, reminders ...), always as the signed-in user. Gemini
 * answers when Groq is down or out of quota; it can't act, so it points people to the right page instead.
 * Never throws: empty means "keep the Python reply" or "offline".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatFallback {

    /** What the LLM said, and whether it changed the cart (so the page updates its badge). */
    public record Answer(String text, boolean cartChanged) {}

    private static final int MAX_MESSAGE = 1000;
    private static final int MAX_REPLY = 1500;
    /** Tool rounds before giving up; each round resends the whole conversation, which costs quota. */
    private static final int MAX_ROUNDS = 4;
    /** Tool results are cut to this many characters, to keep each request well inside Groq's tokens per minute. */
    private static final int MAX_RESULT = 3000;
    private static final Set<String> CART_TOOLS = Set.of("add_to_cart", "refill_plan");
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm");

    private static final String RULES = """
            - You are not a doctor. Give general, widely accepted information only. Never diagnose, never suggest
              starting, stopping or changing a dose of a prescribed medicine, and suggest asking their doctor or our
              pharmacist for anything personal (pregnancy, children, other medicines, alcohol, existing conditions).
            - For emergencies (chest pain, trouble breathing, overdose, severe bleeding, fainting, thoughts of self-harm)
              tell them to call 112 or go to the nearest hospital right now, before anything else.
            - Answer only questions about health, medicines and shopping with MediZon; politely decline anything else.
            - Plain text, no markdown, at most 120 words. Reply in the language the customer used (Hindi and Hinglish are fine).
            """;

    private static final String CUSTOMER = """
            You are the chat assistant of MediZon, an online pharmacy in India. You are talking to a customer named %s.
            Rules:
            """ + RULES + """
            - You can't see their orders, cart, stock or prices, and you can't do anything in the app. Never make these up.
              Point them to the page instead: Shop /medicines, Cart /cart, Orders /orders, Prescriptions /prescriptions
              (upload one and its medicines are suggested for the cart automatically; a pharmacist verifies the prescription), Dose reminders /plans,
              Help and support /support.
            """;

    private static final String CUSTOMER_TOOLS = """
            You are the chat assistant of MediZon, an online pharmacy in India. You are talking to a customer named %s.
            It is now %s (India time).
            Rules:
            """ + RULES + """
            - Use the tools to look things up and to do what they ask: their orders and returns, cart, medicines, prices
              and stock, dose reminders (My medicines) and one-off reminders. Never make up orders, stock, prices or ids:
              look them up first, and say so plainly when nothing is found.
            - Only change something (add to cart, create or complete a reminder, set up or mark a dose, send feedback)
              when they clearly asked for it, then say what you did.
            - Some things only the app does; point to the page: cancelling or returning an order (open it from Orders
              /orders), placing the order (Cart /cart), uploading a prescription (/prescriptions), help (/support).
            """;

    private static final String ADMIN = """
            You are the chat assistant of MediZon, an online pharmacy in India, talking to the store's admin, %s.
            You can't see store data here. For numbers point them to Admin pages: Orders /admin/orders, Sales
            /admin/sales, Catalog /admin/medicines, Prescriptions /admin/prescriptions, Reviews /admin/reviews,
            Monitoring /admin/monitoring. Help with general pharmacy operations and how to use MediZon.
            Never make up figures. Plain text, no markdown, at most 120 words.
            """;

    private static final String ADMIN_TOOLS = """
            You are the chat assistant of MediZon, an online pharmacy in India, talking to the store's admin, %s.
            It is now %s (India time).
            Use the tools for today's store figures (orders to confirm or ship, pending prescriptions, low stock,
            revenue), the catalogue and the admin's own reminders. Never make up figures: look them up. For anything
            else point them to Admin pages: Orders /admin/orders, Sales /admin/sales, Catalog /admin/medicines,
            Prescriptions /admin/prescriptions, Reviews /admin/reviews, Monitoring /admin/monitoring.
            Plain text, no markdown, at most 120 words.
            """;

    private final GroqClient groq;
    private final GeminiClient gemini;
    private final AssistantTools tools;
    private final ObjectMapper json;

    public boolean isAvailable() { return groq.isAvailable() || gemini.isAvailable(); }

    public Optional<Answer> answer(User user, String message) {
        if (message == null || message.isBlank() || !isAvailable()) return Optional.empty();
        String text = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
        boolean[] cartChanged = {false};
        return askGroq(user, text, cartChanged)
                .or(() -> askGemini((user.isAdmin() ? ADMIN : CUSTOMER).formatted(user.getFirstName()), text))
                .map(String::strip)
                .map(a -> new Answer(a.length() > MAX_REPLY ? a.substring(0, MAX_REPLY) + "…" : a, cartChanged[0]));
    }

    /** Groq with tools: runs what it asks for, sends back the results, until it answers in words. */
    private Optional<String> askGroq(User user, String message, boolean[] cartChanged) {
        if (!groq.isAvailable()) return Optional.empty();
        String now = LocalDateTime.now().format(NOW);
        ArrayNode messages = json.createArrayNode();
        messages.addObject().put("role", "system")
                .put("content", (user.isAdmin() ? ADMIN_TOOLS : CUSTOMER_TOOLS).formatted(user.getFirstName(), now));
        messages.addObject().put("role", "user").put("content", message);
        ArrayNode toolList = toolsFor(user);
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                JsonNode reply = groq.complete(messages, toolList, 0.3);
                JsonNode calls = reply.path("tool_calls");
                if (!calls.isArray() || calls.isEmpty()) {
                    String text = reply.path("content").asText("");
                    return text.isBlank() ? Optional.empty() : Optional.of(text);
                }
                ObjectNode asked = messages.addObject().put("role", "assistant");
                if (reply.path("content").isTextual() && !reply.get("content").asText().isBlank()) asked.set("content", reply.get("content"));
                else asked.putNull("content");
                asked.set("tool_calls", calls);
                for (JsonNode call : calls) {
                    String name = call.path("function").path("name").asText();
                    JsonNode result = run(name, call.path("function").path("arguments").asText("{}"), user);
                    if (CART_TOOLS.contains(name) && !result.has("error")) cartChanged[0] = true;
                    String out = result.toString();
                    messages.addObject().put("role", "tool").put("tool_call_id", call.path("id").asText())
                            .put("content", out.length() > MAX_RESULT ? out.substring(0, MAX_RESULT) + "…(cut)" : out);
                }
            }
            log.warn("Groq needed more than {} tool rounds for: {}", MAX_ROUNDS, message);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Groq chat fallback failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** An action the model asked for, as the signed-in user; failures go back to the model as {"error": ...}. */
    private JsonNode run(String name, String arguments, User user) {
        try {
            JsonNode args = json.readTree(arguments.isBlank() ? "{}" : arguments);
            return json.valueToTree(tools.execute(name, args.isObject() ? args : json.createObjectNode(), user));
        } catch (BusinessException e) {
            return json.createObjectNode().put("error", e.getMessage());
        } catch (Exception e) {
            log.warn("Groq action {} failed: {}", name, e.toString());
            return json.createObjectNode().put("error", "That didn't work. Check the details and try again.");
        }
    }

    private Optional<String> askGemini(String system, String message) {
        if (!gemini.isAvailable()) return Optional.empty();
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        body.putArray("contents").addObject().put("role", "user").putArray("parts").addObject().put("text", message);
        body.putObject("generationConfig").put("temperature", 0.3);
        try {
            return Optional.of(gemini.generate(body));
        } catch (RuntimeException e) {
            log.warn("Gemini chat fallback failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ the actions, as the model sees them

    /** Kept short: every request carries them. Admins' review_data is left out, it's far too big for one request. */
    private ArrayNode toolsFor(User user) {
        ArrayNode t = json.createArrayNode();
        tool(t, "search_medicines", "Find medicines in the catalogue with price and stock.",
                "query", "string", "Medicine name, salt or brand", true,
                "category", "string", "Optional category", false);
        if (user.isAdmin()) {
            tool(t, "store_tasks", "Today's store figures: orders to confirm, ship and in transit, pending "
                    + "prescriptions, low stock, low reviews, feedback, orders and revenue today.");
        } else {
            tool(t, "medicine_details", "Details of one medicine by id (from search_medicines).",
                    "id", "integer", "Medicine id", true);
            tool(t, "add_to_cart", "Add a medicine to the cart.",
                    "medicine_id", "integer", "Medicine id from search_medicines", true,
                    "quantity", "integer", "Packs, 1 to 20", false);
            tool(t, "cart_summary", "What's in the cart, with totals.");
            tool(t, "my_orders", "The customer's recent orders with status, items and link.");
            tool(t, "my_returns", "The customer's return requests with status and refund.");
            tool(t, "my_plans", "The customer's medicines with dose reminders (My medicines).");
            tool(t, "todays_doses", "Today's doses from My medicines and whether each was taken.");
            ObjectNode plan = tool(t, "create_plan", "Set up dose reminders for a medicine the customer takes.",
                    "medicine_id", "integer", "Medicine id from search_medicines", true,
                    "dose", "integer", "Units per dose, default 1", false,
                    "units_remaining", "integer", "Units they have left, optional", false);
            ObjectNode params = (ObjectNode) plan.path("function").path("parameters");
            ((ObjectNode) params.get("properties")).putObject("times").put("type", "array")
                    .put("description", "Reminder times, HH:mm, 1 to 6").putObject("items").put("type", "string");
            ((ArrayNode) params.get("required")).add("times");
            tool(t, "mark_dose", "Mark a dose from todays_doses as taken or skipped.",
                    "plan_id", "integer", "plan_id from todays_doses", true,
                    "time", "string", "Dose time, HH:mm", true,
                    "status", "string", "TAKEN or SKIPPED", true);
            tool(t, "refill_plan", "Add a refill of a My medicines entry to the cart.",
                    "plan_id", "integer", "Plan id from my_plans", true);
            tool(t, "submit_feedback", "Send the customer's feedback or complaint to the MediZon team.",
                    "message", "string", "The feedback", true,
                    "category", "string", "Optional, e.g. Delivery, App, Medicines", false,
                    "rating", "integer", "Optional, 1 to 5", false);
        }
        tool(t, "create_reminder", "Set a one-off reminder.",
                "text", "string", "What to remind about", true,
                "due_at", "string", "When, as yyyy-MM-ddTHH:mm in India time", true);
        tool(t, "list_reminders", "Open one-off reminders.");
        tool(t, "complete_reminder", "Mark a reminder done.",
                "id", "integer", "Reminder id from list_reminders", true);
        return t;
    }

    /** Adds a function tool; {@code params} are groups of name, type, description, required. */
    private ObjectNode tool(ArrayNode tools, String name, String description, Object... params) {
        ObjectNode t = tools.addObject().put("type", "function");
        ObjectNode fn = t.putObject("function").put("name", name).put("description", description);
        ObjectNode schema = fn.putObject("parameters").put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (int i = 0; i < params.length; i += 4) {
            props.putObject((String) params[i]).put("type", (String) params[i + 1]).put("description", (String) params[i + 2]);
            if ((Boolean) params[i + 3]) required.add((String) params[i]);
        }
        return t;
    }
}
