package com.browseragent.web;

import com.browseragent.agent.BrowserAgentService;
import com.browseragent.agent.ReportService;
import com.browseragent.agent.TestCase;
import com.browseragent.agent.TestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RestController
@RequiredArgsConstructor
@EnableAsync
@RequestMapping("/api")
public class AgentController {

    private final BrowserAgentService agentService;
    private final ReportService reportService;
    private final TestService testService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * POST /api/run
     * Body: { "task": "Go to Hacker News and list the top 5 headlines" }
     * Returns 202 immediately; progress streams over WebSocket.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> runTask(@RequestBody Map<String, String> body) {
        String task = body.get("task");

        if (task == null || task.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "task must not be empty"));
        }

        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "An agent task is already running. Please wait."));
        }

        log.info("Received task: {}", task);
        agentService.runTask(task).whenComplete((result, ex) -> running.set(false));

        return ResponseEntity.accepted()
                .body(Map.of("status", "running", "task", task));
    }

    /**
     * GET /api/report
     * Returns the last generated HTML report as an inline HTML page.
     * The UI opens this in a new tab when the agent finishes.
     */
    @GetMapping("/report")
    public ResponseEntity<Resource> getReport() {
        if (!reportService.hasReport()) {
            return ResponseEntity.notFound().build();
        }

        Path path = reportService.getLastReportPath();
        Resource resource = new FileSystemResource(path);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                // inline so it opens in the browser rather than downloads
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }

    /**
     * GET /api/report/download
     * Forces the report to download as a file (for saving locally).
     */
    @GetMapping("/report/download")
    public ResponseEntity<Resource> downloadReport() {
        if (!reportService.hasReport()) {
            return ResponseEntity.notFound().build();
        }

        Path path = reportService.getLastReportPath();
        Resource resource = new FileSystemResource(path);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "running", running.get(),
                "reportAvailable", reportService.hasReport()
        ));
    }

    // ── Test cases ───────────────────────────────────────────────────────────

    @GetMapping("/tests")
    public ResponseEntity<java.util.List<TestCase>> listTests() {
        return ResponseEntity.ok(testService.listTests());
    }

    @PostMapping("/tests")
    public ResponseEntity<TestCase> saveTest(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String task = body.get("task");
        if (name == null || name.isBlank() || task == null || task.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(testService.addTest(name, task));
    }

    @DeleteMapping("/tests/{id}")
    public ResponseEntity<Void> deleteTest(@PathVariable String id) {
        return testService.deleteTest(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/tests/{id}/run")
    public ResponseEntity<Map<String, String>> runTest(@PathVariable String id) {
        TestCase tc = testService.getTest(id);
        if (tc == null) return ResponseEntity.notFound().build();
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", "An agent task is already running. Please wait."));
        }
        log.info("Running test case: {} ({})", tc.name(), tc.id());
        agentService.runTask(tc.task()).whenComplete((result, ex) -> running.set(false));
        return ResponseEntity.accepted()
                .body(Map.of("status", "running", "task", tc.task()));
    }
}
