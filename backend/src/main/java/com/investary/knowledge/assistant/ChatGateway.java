package com.investary.knowledge.assistant;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.AiExceptions.AiTimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the configured chat model with a timeout and maps provider failures to API errors. Messages
 * never contain prompts or source text. Shared by the research assistant and portfolio intelligence.
 */
public final class ChatGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatGateway.class);
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");

    private final ObjectProvider<ChatModel> chatModels;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatGateway(ObjectProvider<ChatModel> chatModels, Duration timeout) {
        this.chatModels = chatModels;
        this.timeout = timeout;
    }

    public boolean isAvailable() {
        return chatModels.getIfAvailable() != null;
    }

    /**
     * @throws AiNotConfiguredException when no chat model is configured
     */
    public String call(Prompt prompt) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new AiNotConfiguredException("chat");
        }
        Future<ChatResponse> future = executor.submit(() -> model.call(prompt));
        try {
            ChatResponse response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new AiProviderException("The AI model returned an empty answer");
            }
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiTimeoutException(timeout.toSeconds());
        } catch (ExecutionException e) {
            log.warn("Chat model call failed: {}", e.getCause().toString());
            throw new AiProviderException("The AI model request failed (" + e.getCause().getClass().getSimpleName()
                    + "). Check that the provider is running and the model is available.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Interrupted while waiting for the AI model");
        }
    }

    /** Model output without {@code <think>} reasoning blocks. */
    public static String withoutThinking(String raw) {
        return THINKING.matcher(raw).replaceAll("").strip();
    }

    /**
     * Reads the outermost JSON object of a model reply; models sometimes wrap it in code fences or text.
     */
    public static Optional<JsonNode> jsonObject(String raw, JsonMapper json) {
        String text = withoutThinking(raw);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode node = json.readTree(text.substring(start, end + 1));
            return node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Model output was not valid JSON: {}", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
