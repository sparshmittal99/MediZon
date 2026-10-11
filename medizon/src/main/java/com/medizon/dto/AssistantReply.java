package com.medizon.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * What the chat widget renders: the reply text, optional medicine/info cards, quick-reply chips,
 * a page to open, and the new cart count when the cart changed.
 */
public record AssistantReply(String reply, JsonNode cards, JsonNode suggestions, String navigate, Long cartCount,
                             boolean offline) {

    public static AssistantReply text(String reply) {
        return new AssistantReply(reply, JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(),
                null, null, false);
    }

    public static AssistantReply unavailable() {
        return new AssistantReply("The assistant is offline right now. Please try again in a minute.",
                JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.arrayNode(), null, null, true);
    }
}
