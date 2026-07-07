package com.browseragent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private int llmCalls;

    public double estimatedCost() {
        return (inputTokens / 1_000_000.0 * 3.0) + (outputTokens / 1_000_000.0 * 15.0);
    }

    public static TokenUsage sum(TokenUsage a, TokenUsage b) {
        return TokenUsage.builder()
                .inputTokens(a.inputTokens + b.inputTokens)
                .outputTokens(a.outputTokens + b.outputTokens)
                .totalTokens(a.totalTokens + b.totalTokens)
                .llmCalls(a.llmCalls + b.llmCalls)
                .build();
    }
}
