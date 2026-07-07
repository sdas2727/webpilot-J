package com.browseragent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserAgentService {

    private final AgentAssistant assistant;
    private final BrowserTools browserTools;
    private final ReportService reportService;
    private final TokenUsageTracker tokenUsageTracker;
    private final SimpMessagingTemplate ws;

    public interface AgentAssistant {
        @SystemMessage("""
            You are an expert web automation agent with access to a real browser.
            Your job is to complete tasks given by the user by interacting with websites.

            Strategy:
            1. Start by navigating to the relevant URL for the task.
            2. Read the page content to understand what's there.
            3. Interact step by step: click, fill, scroll, press keys as needed.
            4. After each major step, call takeScreenshot() with a descriptive label so
               the report captures exactly what the browser looks like at that point.
            5. When the task is fully done, call completeTask() with a clear summary.

            Screenshot guidelines:
            - Take a screenshot after navigating to the initial page.
            - Take a screenshot after performing a search or submitting a form.
            - Take a screenshot when you find important information.
            - Take a screenshot at the final state before completing.
            - Label each screenshot descriptively (e.g. "Search results for Java Spring Boot").

            Rules:
            - Always verify the result of navigation before taking further actions.
            - To interact with an element, describe it by visible text or label.
              Both click() and fill() will auto-resolve text descriptions to elements.
              Example: click("Log In") or fill("Email address", "user@test.com") — no CSS needed.
            - If auto-resolve fails, call findElement() with a description to get
              the correct CSS selector, then pass that selector to click() or fill().
            - If a selector still doesn't work, try getPageText() first to see what's on screen.
            - If an element is not found on the page, it may be inside an iframe.
              Call listIframes() to see embedded frames, then use iframe-selectors from findElement().
            - Some elements live inside web components (Shadow DOM).
              Call listShadowHosts() to discover them — click/fill auto-resolve already pierces shadow roots.
            - For charts and graphs, call listSvgElements() (SVG-based charts) or
              getCanvasInfo() (canvas-based charts) to understand chart content and find selectors.
            - Never make up information — only report what you actually see on the page.
            - Call completeTask() exactly once when finished, never before.
            - If you are unsure which tool to use, call findTool() with a short
              description of what you want to do — it will suggest the best tools.
            """)
        String chat(@UserMessage String userTask);
    }

    @Async
    public CompletableFuture<String> runTask(String task) {
        emit("🚀 Starting task: " + task);
        tokenUsageTracker.resetSession();
        reportService.startTask(task);
        browserTools.startSession();
        try {
            String finalResponse = assistant.chat(task);
            String result = browserTools.isTaskComplete()
                    ? browserTools.getTaskResult()
                    : finalResponse;

            TokenUsage tokenUsage = tokenUsageTracker.getCurrentUsage();
            reportService.setTokenUsage(tokenUsage);

            emitTokenUsage(tokenUsage);
            emit("✅ Agent finished. Generating report…");

            // Generate the HTML report
            try {
                Path reportPath = reportService.generateReport();
                emit("📋 Report saved: " + reportPath.getFileName());
                ws.convertAndSend("/topic/report-ready", reportPath.getFileName().toString());
            } catch (Exception e) {
                log.error("Report generation failed", e);
                emit("⚠️ Report generation failed: " + e.getMessage());
            }

            ws.convertAndSend("/topic/done", result);
            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("Agent error", e);
            String errorMsg = "❌ Agent error: " + e.getMessage();
            emit(errorMsg);

            TokenUsage tokenUsage = tokenUsageTracker.getCurrentUsage();
            if (tokenUsage.getTotalTokens() > 0) {
                reportService.setTokenUsage(tokenUsage);
                emitTokenUsage(tokenUsage);
            }

            // Still try to generate a partial report
            try {
                reportService.recordError("Agent aborted: " + e.getMessage());
                Path reportPath = reportService.generateReport();
                ws.convertAndSend("/topic/report-ready", reportPath.getFileName().toString());
            } catch (Exception re) {
                log.warn("Could not generate partial report", re);
            }

            ws.convertAndSend("/topic/done", errorMsg);
            return CompletableFuture.failedFuture(e);
        } finally {
            browserTools.endSession();
        }
    }

    private void emitTokenUsage(TokenUsage usage) {
        ws.convertAndSend("/topic/token-usage", Map.of(
                "inputTokens", usage.getInputTokens(),
                "outputTokens", usage.getOutputTokens(),
                "totalTokens", usage.getTotalTokens(),
                "llmCalls", usage.getLlmCalls(),
                "estimatedCost", Math.round(usage.estimatedCost() * 100_000.0) / 100_000.0
        ));
    }

    private void emit(String msg) {
        log.info("[Agent] {}", msg);
        ws.convertAndSend("/topic/logs", msg);
    }
}
