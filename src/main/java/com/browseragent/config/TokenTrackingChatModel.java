package com.browseragent.config;

import com.browseragent.agent.TokenUsageTracker;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.output.Response;
import java.util.List;

public class TokenTrackingChatModel implements ChatLanguageModel {

    private final ChatLanguageModel delegate;
    private final TokenUsageTracker tracker;

    public TokenTrackingChatModel(ChatLanguageModel delegate, TokenUsageTracker tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        Response<AiMessage> response = delegate.generate(messages);
        captureTokenUsage(response);
        return response;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                         List<ToolSpecification> toolSpecifications) {
        Response<AiMessage> response = delegate.generate(messages, toolSpecifications);
        captureTokenUsage(response);
        return response;
    }

    private void captureTokenUsage(Response<AiMessage> response) {
        dev.langchain4j.model.output.TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            Integer input = usage.inputTokenCount();
            Integer output = usage.outputTokenCount();
            int inputTokens = input != null ? input : 0;
            int outputTokens = output != null ? output : 0;
            if (inputTokens > 0 || outputTokens > 0) {
                tracker.recordUsage(inputTokens, outputTokens);
            }
        }
    }
}
