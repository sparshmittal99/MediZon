package com.medizon.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medizon.domain.User;
import com.medizon.service.CartService;
import com.medizon.service.MedicineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The shop must keep working whatever happens to the Python assistant, Groq and Gemini. */
class AiOutageTest {

    private final ObjectMapper json = new ObjectMapper();
    private AssistantClient python;
    private ChatFallback gemini;
    private AssistantService service;
    private User user;

    @BeforeEach
    void setUp() {
        python = mock(AssistantClient.class);
        gemini = mock(ChatFallback.class);
        MedicineService medicines = mock(MedicineService.class);
        when(medicines.categories()).thenReturn(List.of());
        service = new AssistantService(python, mock(AssistantTools.class), medicines, mock(CartService.class), gemini, json);
        user = mock(User.class);
        when(user.getFirstName()).thenReturn("Asha");
    }

    private ObjectNode pythonReply(String text, boolean unresolved) {
        ObjectNode r = json.createObjectNode().put("reply", text).put("unresolved", unresolved);
        r.putArray("suggestions").addObject().put("label", "Find a medicine");
        r.putArray("cards");
        return r;
    }

    @Test
    void pythonAnswersWhatItUnderstands() {
        when(python.chat(any())).thenReturn(pythonReply("Here's what I found", false));
        var r = service.chat(user, "paracetamol", null, null).reply();
        assertEquals("Here's what I found", r.reply());
        verifyNoInteractions(gemini);
    }

    @Test
    void geminiAnswersWhatPythonCouldNot() {
        when(python.chat(any())).thenReturn(pythonReply("Sorry, I didn't quite get that.", true));
        when(gemini.answer(any(), any(), any())).thenReturn(Optional.of(new ChatFallback.Answer("Avoid alcohol with paracetamol.", false)));
        var r = service.chat(user, "can I take dolo with alcohol", null, null).reply();
        assertEquals("Avoid alcohol with paracetamol.", r.reply());
        assertEquals(1, r.suggestions().size());     // Python's chips stay
    }

    @Test
    void geminiDownKeepsPythonsReply() {
        when(python.chat(any())).thenReturn(pythonReply("Sorry, I didn't quite get that.", true));
        when(gemini.answer(any(), any(), any())).thenReturn(Optional.empty());
        assertEquals("Sorry, I didn't quite get that.", service.chat(user, "hmm", null, null).reply().reply());
    }

    @Test
    void pythonDownGeminiAnswers() {
        when(python.chat(any())).thenThrow(new ResourceAccessException("Connection refused"));
        when(gemini.answer(any(), any(), any())).thenReturn(Optional.of(new ChatFallback.Answer("Search for it on the Shop page.", false)));
        var r = service.chat(user, "do you have dolo", null, null).reply();
        assertEquals("Search for it on the Shop page.", r.reply());
        assertFalse(r.offline());
    }

    @Test
    void bothDownSaysOfflineWithoutFailing() {
        when(python.chat(any())).thenThrow(new ResourceAccessException("Connection refused"));
        when(gemini.answer(any(), any(), any())).thenReturn(Optional.empty());
        assertTrue(service.chat(user, "hello", null, null).reply().offline());
    }

    @Test
    void unexpectedPythonErrorStillAnswers() {
        when(python.chat(any())).thenThrow(new IllegalStateException("bad JSON"));
        when(gemini.answer(any(), any(), any())).thenReturn(Optional.empty());
        assertTrue(service.chat(user, "hello", null, null).reply().offline());
    }

    @Test
    void unreachablePythonIsSkippedForAWhile() {
        AssistantClient client = new AssistantClient("http://127.0.0.1:9", "");    // nothing listens on port 9
        assertTrue(client.isAvailable());
        assertThrows(ResourceAccessException.class, () -> client.chat(json.createObjectNode()));
        assertFalse(client.isAvailable());
        long start = System.nanoTime();
        assertThrows(ResourceAccessException.class, () -> client.chat(json.createObjectNode()));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 100, "skipped without trying the network again");
    }

    @Test
    void unreachableGeminiIsSkippedAndNeverThrowsAnythingElse() {
        GeminiClient client = new GeminiClient("gemini", "test-key", "gemini-flash-latest", "http://127.0.0.1:9");
        assertTrue(client.isAvailable());
        assertThrows(GeminiClient.GeminiUnavailableException.class, () -> client.generate(json.createObjectNode()));
        assertFalse(client.isAvailable());
        assertNotNull(client.unavailableReason());
        assertThrows(GeminiClient.GeminiUnavailableException.class, () -> client.generate(json.createObjectNode()));
    }

    @Test
    void geminiWithoutKeyIsOff() {
        GeminiClient client = new GeminiClient("gemini", "", "gemini-flash-latest", "http://127.0.0.1:9");
        assertFalse(client.isConfigured());
        assertThrows(GeminiClient.GeminiUnavailableException.class, () -> client.generate(json.createObjectNode()));
    }

    @Test
    void openingTheChatWhilePythonSleepsGreetsInsteadOfOffline() {
        when(python.chat(any())).thenThrow(new ResourceAccessException("Read timed out"));
        when(gemini.isAvailable()).thenReturn(true);
        var greet = json.createObjectNode().put("intent", "greet");
        var r = service.chat(user, "", greet, null).reply();
        assertFalse(r.offline());
        assertTrue(r.reply().startsWith("Hi Asha"));
    }

    @Test
    void openingTheChatWithEverythingDownSaysOffline() {
        when(python.chat(any())).thenThrow(new ResourceAccessException("Read timed out"));
        var greet = json.createObjectNode().put("intent", "greet");
        assertTrue(service.chat(user, "", greet, null).reply().offline());
    }

    @Test
    void aYesToTheLlmsQuestionGoesBackToTheLlmNotPython() {
        when(gemini.answer(any(), any(), any())).thenReturn(Optional.of(new ChatFallback.Answer("Added Paracetamol 650.", true)));
        var r = service.chat(user, "yes", null, null, List.of(new ChatFallback.Turn(false, "Add it to your cart?")), true);
        assertEquals("Added Paracetamol 650.", r.reply().reply());
        assertTrue(r.byLlm());
        verifyNoInteractions(python);
    }

    @Test
    void aYesAfterPythonsOwnReplyStaysWithPython() {
        when(python.chat(any())).thenReturn(pythonReply("Added to cart", false));
        var r = service.chat(user, "yes", null, null, List.of(), false);
        assertEquals("Added to cart", r.reply().reply());
        assertFalse(r.byLlm());
        verifyNoInteractions(gemini);
    }

    @Test
    void aNewRequestAfterTheLlmStillTriesPythonFirst() {
        when(python.chat(any())).thenReturn(pythonReply("Here's what I found", false));
        var r = service.chat(user, "show me cough syrups for children under five", null, null, List.of(), true);
        assertEquals("Here's what I found", r.reply().reply());
        verifyNoInteractions(gemini);
    }

    @Test
    void followUpsAreShortAnswers() {
        for (String m : List.of("yes", "Yes please", "ok", "haan", "no thanks", "add it", "the second one", "yes add paracetamol"))
            assertTrue(AssistantService.isFollowUp(m), m);
        for (String m : List.of("where is my order", "paracetamol", "", "yes I want to know about the side effects of this medicine"))
            assertFalse(AssistantService.isFollowUp(m), m);
    }

    @Test
    void unreachableGroqIsSkippedAndNeverThrowsAnythingElse() {
        GroqClient client = new GroqClient("test-key", "openai/gpt-oss-120b", "http://127.0.0.1:9");
        assertTrue(client.isAvailable());
        assertThrows(GroqClient.GroqUnavailableException.class, () -> client.chat("system", "hi", 0.3));
        assertFalse(client.isAvailable());
        assertNotNull(client.unavailableReason());
        assertThrows(GroqClient.GroqUnavailableException.class, () -> client.chat("system", "hi", 0.3));
    }

    @Test
    void groqMovesToTheNextModelWhenOneRunsOutOfQuota() throws Exception {
        java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer fake = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/chat/completions", ex -> {
            String model = json.readTree(ex.getRequestBody()).path("model").asText();
            asked.add(model);
            byte[] out = (model.equals("big") ? "{}" : "{\"choices\":[{\"message\":{\"content\":\"From small\"}}]}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(model.equals("big") ? 429 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        fake.start();
        try {
            GroqClient client = new GroqClient("test-key", "big, small", "http://127.0.0.1:" + fake.getAddress().getPort());
            assertEquals("From small", client.chat("system", "hi", 0.3));
            assertEquals("From small", client.chat("system", "hi", 0.3));
            assertEquals(List.of("big", "small", "small"), asked);    // the used-up model isn't asked again
            assertTrue(client.isAvailable());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void groqWithoutKeyIsOff() {
        GroqClient client = new GroqClient("", "openai/gpt-oss-120b", "http://127.0.0.1:9");
        assertFalse(client.isConfigured());
        assertThrows(GroqClient.GroqUnavailableException.class, () -> client.chat("system", "hi", 0.3));
    }

    private ObjectNode groqSays(String text) {
        return json.createObjectNode().put("role", "assistant").put("content", text);
    }

    @Test
    void groqAnswersFirst() {
        GroqClient groq = mock(GroqClient.class);
        GeminiClient gem = mock(GeminiClient.class);
        when(groq.isAvailable()).thenReturn(true);
        when(gem.isAvailable()).thenReturn(true);
        when(groq.complete(any(), any(), anyDouble())).thenReturn(groqSays(" From Groq "));
        var a = new ChatFallback(groq, gem, mock(AssistantTools.class), json).answer(user, "can I take dolo with alcohol", List.of());
        assertEquals("From Groq", a.orElseThrow().text());
        verify(gem, never()).generate(any());
    }

    @Test
    void groqLooksUpOrdersAsTheUserThenAnswers() {
        GroqClient groq = mock(GroqClient.class);
        AssistantTools tools = mock(AssistantTools.class);
        when(groq.isAvailable()).thenReturn(true);
        ObjectNode wantsOrders = json.createObjectNode().put("role", "assistant");
        wantsOrders.putArray("tool_calls").addObject().put("id", "call_1").put("type", "function")
                .putObject("function").put("name", "my_orders").put("arguments", "{}");
        ObjectNode wantsCart = json.createObjectNode().put("role", "assistant");
        wantsCart.putArray("tool_calls").addObject().put("id", "call_2").put("type", "function")
                .putObject("function").put("name", "add_to_cart").put("arguments", "{\"medicine_id\": 7, \"quantity\": 2}");
        when(groq.complete(any(), any(), anyDouble())).thenReturn(wantsOrders, wantsCart, groqSays("Your order MZ1 is Shipped; I added 2 packs."));
        when(tools.execute(eq("my_orders"), any(), eq(user))).thenReturn(List.of(java.util.Map.of("order_number", "MZ1", "status", "Shipped")));
        when(tools.execute(eq("add_to_cart"), any(), eq(user))).thenReturn(java.util.Map.of("ok", true));

        var a = new ChatFallback(groq, mock(GeminiClient.class), tools, json).answer(user, "where is my order, and add 2 more", List.of()).orElseThrow();
        assertEquals("Your order MZ1 is Shipped; I added 2 packs.", a.text());
        assertTrue(a.cartChanged());
        verify(tools).execute(eq("add_to_cart"), argThat(args -> args.path("medicine_id").asLong() == 7), eq(user));
        var sent = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
        verify(groq, times(3)).complete(sent.capture(), any(), anyDouble());
        // the same conversation grows: system, user, then a call and its result per round
        assertTrue(sent.getValue().toString().contains("MZ1"));
    }

    @Test
    void aFailingActionGoesBackToGroqAsAnError() {
        GroqClient groq = mock(GroqClient.class);
        AssistantTools tools = mock(AssistantTools.class);
        when(groq.isAvailable()).thenReturn(true);
        ObjectNode wants = json.createObjectNode().put("role", "assistant");
        wants.putArray("tool_calls").addObject().put("id", "c").put("type", "function")
                .putObject("function").put("name", "create_reminder").put("arguments", "{\"text\":\"x\",\"due_at\":\"yesterday\"}");
        when(groq.complete(any(), any(), anyDouble())).thenReturn(wants, groqSays("What time should I remind you?"));
        when(tools.execute(any(), any(), any())).thenThrow(new com.medizon.service.BusinessException("Pick a time in the future."));
        var a = new ChatFallback(groq, mock(GeminiClient.class), tools, json).answer(user, "remind me yesterday", List.of());
        assertEquals("What time should I remind you?", a.orElseThrow().text());
        assertFalse(a.get().cartChanged());
    }

    @Test
    void groqGetsTheEarlierChatForFollowUps() {
        GroqClient groq = mock(GroqClient.class);
        when(groq.isAvailable()).thenReturn(true);
        when(groq.complete(any(), any(), anyDouble())).thenReturn(groqSays("Added Dolo 650."));
        var history = List.of(new ChatFallback.Turn(false, "Hi Asha!"),
                new ChatFallback.Turn(true, "show me fever medicines"),
                new ChatFallback.Turn(false, "Here are some:\n[Showed: Crocin (id 3); Dolo 650 (id 7)]"));
        new ChatFallback(groq, mock(GeminiClient.class), mock(AssistantTools.class), json).answer(user, "add the second one", history);
        var sent = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
        verify(groq).complete(sent.capture(), any(), anyDouble());
        var msgs = sent.getValue();
        assertEquals(4, msgs.size());                     // system, the two earlier turns (greeting dropped), the question
        assertEquals("user", msgs.get(1).path("role").asText());
        assertTrue(msgs.get(2).path("content").asText().contains("Dolo 650 (id 7)"));
        assertEquals("add the second one", msgs.get(3).path("content").asText());
    }

    @Test
    void longChatsAreTrimmed() {
        var many = new java.util.ArrayList<ChatFallback.Turn>();
        for (int i = 0; i < 30; i++) many.add(new ChatFallback.Turn(i % 2 == 0, "x".repeat(2000)));
        var recent = ChatFallback.recent(many);
        assertTrue(recent.size() <= 10);
        assertTrue(recent.get(0).fromUser());
        assertTrue(recent.stream().allMatch(t -> t.text().length() <= 601));
    }

    @Test
    void geminiAnswersWhenGroqFails() {
        GroqClient groq = mock(GroqClient.class);
        GeminiClient gem = mock(GeminiClient.class);
        when(groq.isAvailable()).thenReturn(true);
        when(gem.isAvailable()).thenReturn(true);
        when(groq.complete(any(), any(), anyDouble())).thenThrow(new GroqClient.GroqUnavailableException("quota used up"));
        when(gem.generate(any())).thenReturn("From Gemini");
        var a = new ChatFallback(groq, gem, mock(AssistantTools.class), json).answer(user, "can I take dolo with alcohol", List.of());
        assertEquals("From Gemini", a.orElseThrow().text());
    }

    @Test
    void bothLlmsDownIsEmpty() {
        GroqClient groq = mock(GroqClient.class);
        GeminiClient gem = mock(GeminiClient.class);
        assertEquals(Optional.empty(), new ChatFallback(groq, gem, mock(AssistantTools.class), json).answer(user, "can I take dolo with alcohol", List.of()));
    }
}
