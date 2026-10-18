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
        when(gemini.answer(any(), any())).thenReturn(Optional.of("Avoid alcohol with paracetamol."));
        var r = service.chat(user, "can I take dolo with alcohol", null, null).reply();
        assertEquals("Avoid alcohol with paracetamol.", r.reply());
        assertEquals(1, r.suggestions().size());     // Python's chips stay
    }

    @Test
    void geminiDownKeepsPythonsReply() {
        when(python.chat(any())).thenReturn(pythonReply("Sorry, I didn't quite get that.", true));
        when(gemini.answer(any(), any())).thenReturn(Optional.empty());
        assertEquals("Sorry, I didn't quite get that.", service.chat(user, "hmm", null, null).reply().reply());
    }

    @Test
    void pythonDownGeminiAnswers() {
        when(python.chat(any())).thenThrow(new ResourceAccessException("Connection refused"));
        when(gemini.answer(any(), any())).thenReturn(Optional.of("Search for it on the Shop page."));
        var r = service.chat(user, "do you have dolo", null, null).reply();
        assertEquals("Search for it on the Shop page.", r.reply());
        assertFalse(r.offline());
    }

    @Test
    void bothDownSaysOfflineWithoutFailing() {
        when(python.chat(any())).thenThrow(new ResourceAccessException("Connection refused"));
        when(gemini.answer(any(), any())).thenReturn(Optional.empty());
        assertTrue(service.chat(user, "hello", null, null).reply().offline());
    }

    @Test
    void unexpectedPythonErrorStillAnswers() {
        when(python.chat(any())).thenThrow(new IllegalStateException("bad JSON"));
        when(gemini.answer(any(), any())).thenReturn(Optional.empty());
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
    void unreachableGroqIsSkippedAndNeverThrowsAnythingElse() {
        GroqClient client = new GroqClient("test-key", "llama-3.3-70b-versatile", "http://127.0.0.1:9");
        assertTrue(client.isAvailable());
        assertThrows(GroqClient.GroqUnavailableException.class, () -> client.chat("system", "hi", 0.3));
        assertFalse(client.isAvailable());
        assertNotNull(client.unavailableReason());
        assertThrows(GroqClient.GroqUnavailableException.class, () -> client.chat("system", "hi", 0.3));
    }

    @Test
    void groqWithoutKeyIsOff() {
        GroqClient client = new GroqClient("", "llama-3.3-70b-versatile", "http://127.0.0.1:9");
        assertFalse(client.isConfigured());
        assertThrows(GroqClient.GroqUnavailableException.class, () -> client.chat("system", "hi", 0.3));
    }

    @Test
    void groqAnswersFirst() {
        GroqClient groq = mock(GroqClient.class);
        GeminiClient gem = mock(GeminiClient.class);
        when(groq.isAvailable()).thenReturn(true);
        when(gem.isAvailable()).thenReturn(true);
        when(groq.chat(any(), any(), anyDouble())).thenReturn(" From Groq ");
        assertEquals(Optional.of("From Groq"), new ChatFallback(groq, gem, json).answer(user, "can I take dolo with alcohol"));
        verify(gem, never()).generate(any());
    }

    @Test
    void geminiAnswersWhenGroqFails() {
        GroqClient groq = mock(GroqClient.class);
        GeminiClient gem = mock(GeminiClient.class);
        when(groq.isAvailable()).thenReturn(true);
        when(gem.isAvailable()).thenReturn(true);
        when(groq.chat(any(), any(), anyDouble())).thenThrow(new GroqClient.GroqUnavailableException("quota used up"));
        when(gem.generate(any())).thenReturn("From Gemini");
        assertEquals(Optional.of("From Gemini"), new ChatFallback(groq, gem, json).answer(user, "can I take dolo with alcohol"));
    }

    @Test
    void bothLlmsDownIsEmpty() {
        GroqClient groq = mock(GroqClient.class);
        GeminiClient gem = mock(GeminiClient.class);
        assertEquals(Optional.empty(), new ChatFallback(groq, gem, json).answer(user, "can I take dolo with alcohol"));
    }
}
