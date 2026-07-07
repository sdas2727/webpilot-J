package com.browseragent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class TokenUsageTracker {

    private final SimpMessagingTemplate ws;

    private final AtomicInteger sessionInputTokens = new AtomicInteger(0);
    private final AtomicInteger sessionOutputTokens = new AtomicInteger(0);
    private final AtomicInteger sessionLlmCalls = new AtomicInteger(0);

    public TokenUsageTracker(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    public void recordUsage(int inputTokens, int outputTokens) {
        sessionInputTokens.addAndGet(inputTokens);
        sessionOutputTokens.addAndGet(outputTokens);
        int calls = sessionLlmCalls.incrementAndGet();

        TokenUsage snapshot = getCurrentUsage();
        log.info("[TokenUsage] Call #{} — input: {}, output: {}, total: {} | Session total: {}",
                calls, inputTokens, outputTokens, inputTokens + outputTokens, snapshot.getTotalTokens());

        ws.convertAndSend("/topic/token-usage", Map.of(
                "inputTokens", snapshot.getInputTokens(),
                "outputTokens", snapshot.getOutputTokens(),
                "totalTokens", snapshot.getTotalTokens(),
                "llmCalls", snapshot.getLlmCalls(),
                "estimatedCost", Math.round(snapshot.estimatedCost() * 100_000.0) / 100_000.0
        ));
    }

    public TokenUsage getCurrentUsage() {
        return TokenUsage.builder()
                .inputTokens(sessionInputTokens.get())
                .outputTokens(sessionOutputTokens.get())
                .totalTokens(sessionInputTokens.get() + sessionOutputTokens.get())
                .llmCalls(sessionLlmCalls.get())
                .build();
    }

    public void resetSession() {
        sessionInputTokens.set(0);
        sessionOutputTokens.set(0);
        sessionLlmCalls.set(0);
    }
}
