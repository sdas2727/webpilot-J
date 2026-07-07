package com.browseragent.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects ReportEvents during an agent run and produces a polished,
 * self-contained HTML report (with screenshots embedded as base64).
 *
 * One instance is reset per task via {@link #startTask}.
 */
@Slf4j
@Service
public class ReportService {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final List<ReportEvent> events = new CopyOnWriteArrayList<>();
    private String currentTask = "";
    private Instant taskStart;
    private Path lastReportPath;
    private TokenUsage tokenUsage;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void startTask(String task) {
        events.clear();
        currentTask = task;
        taskStart = Instant.now();
        lastReportPath = null;
        addEvent(ReportEvent.Type.START, task, null, null);
    }

    // ── Event recording ──────────────────────────────────────────────────────

    public void recordAction(String message) {
        addEvent(ReportEvent.Type.ACTION, message, null, null);
    }

    public void recordSuccess(String message) {
        addEvent(ReportEvent.Type.SUCCESS, message, null, null);
    }

    public void recordError(String message) {
        addEvent(ReportEvent.Type.ERROR, message, null, null);
    }

    public void recordObservation(String message) {
        addEvent(ReportEvent.Type.OBSERVATION, message, null, null);
    }

    public void recordScreenshot(String label, byte[] imageBytes) {
        addEvent(ReportEvent.Type.SCREENSHOT, label, imageBytes, label);
    }

    public void recordComplete(String summary) {
        addEvent(ReportEvent.Type.COMPLETE, summary, null, null);
    }

    public void setTokenUsage(TokenUsage usage) {
        this.tokenUsage = usage;
    }

    private void addEvent(ReportEvent.Type type, String message, byte[] bytes, String label) {
        events.add(ReportEvent.builder()
                .timestamp(Instant.now())
                .type(type)
                .message(message)
                .screenshotBytes(bytes)
                .screenshotLabel(label)
                .build());
    }

    // ── Report generation ────────────────────────────────────────────────────

    /**
     * Generates a self-contained HTML report and writes it to the reports/ folder.
     * @return path to the generated report file
     */
    public Path generateReport() throws IOException {
        Path reportsDir = Paths.get("reports");
        Files.createDirectories(reportsDir);

        String filename = "report_" + System.currentTimeMillis() + ".html";
        Path reportPath = reportsDir.resolve(filename);

        Files.writeString(reportPath, buildHtml());
        lastReportPath = reportPath;
        log.info("Report written to {}", reportPath);
        return reportPath;
    }

    public Path getLastReportPath() {
        return lastReportPath;
    }

    public boolean hasReport() {
        return lastReportPath != null && lastReportPath.toFile().exists();
    }

    // ── HTML builder ─────────────────────────────────────────────────────────

    private String buildHtml() {
        long durationSec = taskStart == null ? 0 :
                (Instant.now().toEpochMilli() - taskStart.toEpochMilli()) / 1000;

        long screenshotCount = events.stream()
                .filter(e -> e.getType() == ReportEvent.Type.SCREENSHOT).count();
        long errorCount = events.stream()
                .filter(e -> e.getType() == ReportEvent.Type.ERROR).count();

        String completionSummary = events.stream()
                .filter(e -> e.getType() == ReportEvent.Type.COMPLETE)
                .map(ReportEvent::getMessage)
                .findFirst().orElse("Task did not complete.");

        StringBuilder timeline = new StringBuilder();
        int screenshotIndex = 0;
        for (ReportEvent ev : events) {
            screenshotIndex = appendEventHtml(timeline, ev, screenshotIndex);
        }

        String tokenSection = buildTokenSection();

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agent Report</title>
<style>
  @import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=Space+Grotesk:wght@400;500;600;700&display=swap');

  :root {
    --bg: #0a0a0f; --surface: #111118; --border: #1e1e2e;
    --accent: #7c6af7; --accent2: #4fd1c5;
    --text: #e2e8f0; --muted: #64748b;
    --success: #4fd1c5; --error: #f87171; --warn: #fbbf24;
    --action: #818cf8; --obs: #94a3b8;
  }

  *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

  body {
    background: var(--bg); color: var(--text);
    font-family: 'Space Grotesk', sans-serif;
    line-height: 1.6; padding: 2rem 1rem;
  }

  body::before {
    content: ''; position: fixed; inset: 0;
    background-image:
      linear-gradient(rgba(124,106,247,.03) 1px, transparent 1px),
      linear-gradient(90deg, rgba(124,106,247,.03) 1px, transparent 1px);
    background-size: 40px 40px; pointer-events: none; z-index: 0;
  }

  .wrap { position: relative; z-index: 1; max-width: 900px; margin: 0 auto; }

  /* ── Header ── */
  .report-header {
    border-bottom: 1px solid var(--border); padding-bottom: 2rem; margin-bottom: 2rem;
  }

  .report-header .eyebrow {
    font-family: 'IBM Plex Mono', monospace;
    font-size: .7rem; letter-spacing: .12em; color: var(--accent);
    text-transform: uppercase; margin-bottom: .5rem;
  }

  h1 { font-size: 1.6rem; font-weight: 700; letter-spacing: -.02em; margin-bottom: .5rem; }

  .task-box {
    background: var(--surface); border: 1px solid var(--border);
    border-left: 3px solid var(--accent); border-radius: 8px;
    padding: .875rem 1.25rem; margin: 1rem 0;
    font-size: .95rem; color: var(--text);
  }

  .meta { font-size: .8rem; color: var(--muted); margin-top: .75rem; }

  /* ── Stats ── */
  .stats {
    display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
    gap: 1rem; margin-bottom: 2rem;
  }

  .stat {
    background: var(--surface); border: 1px solid var(--border);
    border-radius: 12px; padding: 1rem 1.25rem;
  }

  .stat-val {
    font-size: 1.8rem; font-weight: 700;
    font-family: 'IBM Plex Mono', monospace; color: var(--accent);
    line-height: 1;
  }

  .stat-label { font-size: .72rem; color: var(--muted); margin-top: .3rem; text-transform: uppercase; letter-spacing: .06em; }

  /* ── Token section ── */
  .token-section {
    background: linear-gradient(135deg, rgba(124,106,247,.06), rgba(79,209,197,.04));
    border: 1px solid var(--border);
    border-radius: 12px; padding: 1.25rem 1.5rem; margin-bottom: 2rem;
  }

  .token-section .token-label {
    font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
    color: var(--accent); font-weight: 600; margin-bottom: .75rem;
  }

  .token-grid {
    display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
    gap: 1rem;
  }

  .token-item { text-align: center; }
  .token-item .token-val {
    font-size: 1.3rem; font-weight: 700;
    font-family: 'IBM Plex Mono', monospace; color: var(--warn);
  }
  .token-item .token-item-label {
    font-size: .65rem; color: var(--muted); text-transform: uppercase; letter-spacing: .06em; margin-top: .2rem;
  }
  .token-item.cost .token-val { color: var(--accent2); }

  /* ── Result box ── */
  .result-box {
    background: rgba(79,209,197,.07); border: 1px solid rgba(79,209,197,.3);
    border-radius: 12px; padding: 1.25rem 1.5rem; margin-bottom: 2rem;
  }

  .result-box .label {
    font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
    color: var(--accent2); font-weight: 600; margin-bottom: .5rem;
  }

  .result-box p { font-size: .95rem; white-space: pre-wrap; }

  /* ── Timeline ── */
  h2 {
    font-size: 1rem; font-weight: 600; letter-spacing: -.01em;
    color: var(--muted); text-transform: uppercase; font-size: .75rem;
    letter-spacing: .1em; margin-bottom: 1rem;
  }

  .timeline { position: relative; padding-left: 2rem; }

  .timeline::before {
    content: ''; position: absolute; left: .5rem; top: 0; bottom: 0;
    width: 1px; background: var(--border);
  }

  .tl-event {
    position: relative; margin-bottom: 1.25rem;
  }

  .tl-dot {
    position: absolute; left: -1.625rem; top: .3rem;
    width: 10px; height: 10px; border-radius: 50%;
    background: var(--muted); border: 2px solid var(--bg);
    flex-shrink: 0;
  }

  .tl-event.type-start    .tl-dot { background: var(--accent); }
  .tl-event.type-action   .tl-dot { background: var(--action); }
  .tl-event.type-success  .tl-dot { background: var(--success); }
  .tl-event.type-error    .tl-dot { background: var(--error); }
  .tl-event.type-screenshot .tl-dot { background: var(--warn); }
  .tl-event.type-complete .tl-dot { background: var(--accent2); width: 12px; height: 12px; left: -1.75rem; top: .2rem; }
  .tl-event.type-observation .tl-dot { background: #475569; }

  .tl-header {
    display: flex; align-items: baseline; gap: .625rem; margin-bottom: .25rem;
  }

  .tl-time {
    font-family: 'IBM Plex Mono', monospace;
    font-size: .7rem; color: var(--muted); flex-shrink: 0;
  }

  .tl-tag {
    font-size: .65rem; font-weight: 600; text-transform: uppercase;
    letter-spacing: .08em; padding: 1px 7px; border-radius: 99px; flex-shrink: 0;
  }

  .type-action .tl-tag    { background: rgba(129,140,248,.15); color: var(--action); }
  .type-success .tl-tag   { background: rgba(79,209,197,.12); color: var(--success); }
  .type-error .tl-tag     { background: rgba(248,113,113,.12); color: var(--error); }
  .type-screenshot .tl-tag { background: rgba(251,191,36,.12); color: var(--warn); }
  .type-complete .tl-tag  { background: rgba(79,209,197,.12); color: var(--accent2); }
  .type-start .tl-tag     { background: rgba(124,106,247,.15); color: var(--accent); }
  .type-observation .tl-tag { background: rgba(100,116,139,.12); color: var(--obs); }

  .tl-msg {
    font-size: .875rem; color: var(--text); line-height: 1.5;
  }

  .type-observation .tl-msg { color: var(--muted); font-size: .82rem; }
  .type-error .tl-msg { color: var(--error); }

  /* ── Screenshot ── */
  .screenshot-block {
    margin-top: .875rem;
    background: var(--surface); border: 1px solid var(--border);
    border-radius: 10px; overflow: hidden;
  }

  .screenshot-bar {
    padding: .5rem 1rem;
    font-family: 'IBM Plex Mono', monospace; font-size: .7rem;
    color: var(--muted); border-bottom: 1px solid var(--border);
    display: flex; align-items: center; gap: .5rem;
  }

  .screenshot-bar span { color: var(--warn); }

  .screenshot-block img {
    width: 100%; display: block;
    border-radius: 0 0 10px 10px;
  }

  /* ── Footer ── */
  footer {
    margin-top: 3rem; padding-top: 1.5rem; border-top: 1px solid var(--border);
    font-size: .75rem; color: var(--muted); text-align: center;
  }
</style>
</head>
<body>
<div class="wrap">

  <div class="report-header">
    <div class="eyebrow">Browser Agent · Execution Report</div>
    <h1>Task Report</h1>
    <div class="task-box">%s</div>
    <div class="meta">
      Started: %s &nbsp;·&nbsp; Duration: %ds &nbsp;·&nbsp; Steps: %d
    </div>
  </div>

  <div class="stats">
    <div class="stat">
      <div class="stat-val">%d</div>
      <div class="stat-label">Total Steps</div>
    </div>
    <div class="stat">
      <div class="stat-val">%d</div>
      <div class="stat-label">Screenshots</div>
    </div>
    <div class="stat">
      <div class="stat-val">%ds</div>
      <div class="stat-label">Duration</div>
    </div>
    <div class="stat">
      <div class="stat-val" style="color:%s">%s</div>
      <div class="stat-label">Outcome</div>
    </div>
  </div>

  %s

  <div class="result-box">
    <div class="label">✦ Final Result</div>
    <p>%s</p>
  </div>

  <h2>Execution Timeline</h2>
  <div class="timeline">
%s
  </div>

  <footer>Generated by Browser Agent &nbsp;·&nbsp; %s</footer>
</div>
</body>
</html>
""".formatted(
                escHtml(currentTask),
                taskStart != null ? FULL_FMT.format(taskStart) : "–",
                durationSec,
                events.size(),
                // stats row
                events.size(),
                screenshotCount,
                durationSec,
                errorCount > 0 ? "var(--error)" : "var(--success)",
                errorCount > 0 ? "Errors" : "OK",
                // token section
                tokenSection,
                // result
                escHtml(completionSummary),
                // timeline
                timeline.toString(),
                // footer timestamp
                FULL_FMT.format(Instant.now())
        );
    }

    private String buildTokenSection() {
        if (tokenUsage == null || tokenUsage.getTotalTokens() == 0) {
            return "";
        }
        String cost = "$" + String.format("%.5f", tokenUsage.estimatedCost());
        return """
  <div class="token-section">
    <div class="token-label">✦ Token Consumption</div>
    <div class="token-grid">
      <div class="token-item">
        <div class="token-val">%s</div>
        <div class="token-item-label">Input Tokens</div>
      </div>
      <div class="token-item">
        <div class="token-val">%s</div>
        <div class="token-item-label">Output Tokens</div>
      </div>
      <div class="token-item">
        <div class="token-val">%s</div>
        <div class="token-item-label">Total Tokens</div>
      </div>
      <div class="token-item">
        <div class="token-val">%s</div>
        <div class="token-item-label">LLM Calls</div>
      </div>
      <div class="token-item cost">
        <div class="token-val">%s</div>
        <div class="token-item-label">Est. Cost</div>
      </div>
    </div>
  </div>
""".formatted(
                formatNum(tokenUsage.getInputTokens()),
                formatNum(tokenUsage.getOutputTokens()),
                formatNum(tokenUsage.getTotalTokens()),
                formatNum(tokenUsage.getLlmCalls()),
                cost
        );
    }

    private String formatNum(int n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private int appendEventHtml(StringBuilder sb, ReportEvent ev, int screenshotIndex) {
        String typeClass = "type-" + ev.getType().name().toLowerCase();
        String tagLabel  = tagLabel(ev.getType());
        String time      = TIME_FMT.format(ev.getTimestamp());

        sb.append("    <div class=\"tl-event ").append(typeClass).append("\">\n");
        sb.append("      <div class=\"tl-dot\"></div>\n");
        sb.append("      <div class=\"tl-header\">\n");
        sb.append("        <span class=\"tl-time\">").append(time).append("</span>\n");
        sb.append("        <span class=\"tl-tag\">").append(tagLabel).append("</span>\n");
        sb.append("      </div>\n");
        sb.append("      <div class=\"tl-msg\">").append(escHtml(ev.getMessage())).append("</div>\n");

        if (ev.getType() == ReportEvent.Type.SCREENSHOT && ev.getScreenshotBytes() != null) {
            screenshotIndex++;
            String b64 = Base64.getEncoder().encodeToString(ev.getScreenshotBytes());
            sb.append("      <div class=\"screenshot-block\">\n");
            sb.append("        <div class=\"screenshot-bar\"><span>📸</span> Screenshot #")
              .append(screenshotIndex).append(" &nbsp;·&nbsp; ")
              .append(escHtml(ev.getScreenshotLabel() != null ? ev.getScreenshotLabel() : ""))
              .append("</div>\n");
            sb.append("        <img src=\"data:image/png;base64,").append(b64)
              .append("\" alt=\"Screenshot ").append(screenshotIndex).append("\">\n");
            sb.append("      </div>\n");
        }

        sb.append("    </div>\n");
        return screenshotIndex;
    }

    private String tagLabel(ReportEvent.Type type) {
        return switch (type) {
            case START       -> "Start";
            case ACTION      -> "Action";
            case OBSERVATION -> "Read";
            case SCREENSHOT  -> "Screenshot";
            case SUCCESS     -> "OK";
            case ERROR       -> "Error";
            case COMPLETE    -> "Complete";
        };
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
