package com.browseragent.config;

import com.browseragent.agent.BrowserAgentService;
import com.browseragent.agent.BrowserTools;
import com.browseragent.agent.SalesforceTools;
import com.browseragent.agent.TokenUsageTracker;
import com.browseragent.agent.ToolFinder;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

    // ── Anthropic ──────────────────────────────────────────────────────────────
    @Value("${anthropic.api.key:}")
    private String anthropicKey;

    @Value("${agent.model:claude-sonnet-4-20250514}")
    private String anthropicModel;

    // ── OpenAI ─────────────────────────────────────────────────────────────────
    @Value("${openai.api.key:}")
    private String openAiKey;

    @Value("${openai.model:gpt-4o}")
    private String openAiModel;

    // ── Azure AI Foundry ───────────────────────────────────────────────────────
    @Value("${azure.openai.endpoint:}")
    private String azureEndpoint;

    @Value("${azure.openai.api.key:}")
    private String azureApiKey;

    @Value("${azure.openai.deployment:gpt-4o}")
    private String azureDeployment;

    @Value("${azure.openai.api.version:2024-08-01-preview}")
    private String azureApiVersion;

    /** Priority: Anthropic → Azure AI Foundry → OpenAI */
    @Bean
    public ChatLanguageModel chatLanguageModel(TokenUsageTracker tokenUsageTracker) {
        ChatLanguageModel raw;
        if (!anthropicKey.isBlank()) {
            raw = AnthropicChatModel.builder()
                    .apiKey(anthropicKey)
                    .modelName(anthropicModel)
                    .maxTokens(4096)
                    .build();
        } else if (!azureEndpoint.isBlank() && !azureApiKey.isBlank()) {
            raw = AzureOpenAiChatModel.builder()
                    .endpoint(azureEndpoint)
                    .apiKey(azureApiKey)
                    .deploymentName(azureDeployment)
                    .serviceVersion(azureApiVersion)
                    .maxTokens(4096)
                    .build();
        } else if (!openAiKey.isBlank()) {
            raw = OpenAiChatModel.builder()
                    .apiKey(openAiKey)
                    .modelName(openAiModel)
                    .build();
        } else {
            throw new IllegalStateException("""
                No LLM provider configured. Set ONE of the following:
                  Anthropic:        ANTHROPIC_API_KEY
                  Azure AI Foundry: AZURE_OPENAI_ENDPOINT + AZURE_OPENAI_API_KEY + AZURE_OPENAI_DEPLOYMENT
                  OpenAI:           OPENAI_API_KEY
                """);
        }
        return new TokenTrackingChatModel(raw, tokenUsageTracker);
    }

    @Bean
    public BrowserAgentService.AgentAssistant agentAssistant(
            ChatLanguageModel model,
            BrowserTools browserTools,
            SalesforceTools salesforceTools,
            ToolFinder toolFinder) {
        return AiServices.builder(BrowserAgentService.AgentAssistant.class)
                .chatLanguageModel(model)
                .tools(browserTools, salesforceTools, toolFinder)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(40))
                .build();
    }
}
