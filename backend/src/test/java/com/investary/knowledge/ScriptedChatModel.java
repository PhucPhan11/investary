package com.investary.knowledge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Chat model for tests: answers with a scripted reply and records every prompt it receives.
 */
public class ScriptedChatModel implements ChatModel {

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private volatile Function<Prompt, String> reply = prompt -> "{\"answer\":\"\",\"evidenceStatus\":\"INSUFFICIENT\"}";

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply.apply(prompt)))));
    }

    public void reply(String value) {
        this.reply = prompt -> value;
    }

    public void reply(Function<Prompt, String> value) {
        this.reply = value;
    }

    public List<Prompt> prompts() {
        return prompts;
    }

    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    public void reset() {
        prompts.clear();
        reply("{\"answer\":\"\",\"evidenceStatus\":\"INSUFFICIENT\"}");
    }
}
