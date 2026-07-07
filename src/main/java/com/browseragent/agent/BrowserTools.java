package com.browseragent.agent;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Every public method annotated with @Tool is automatically available to the LLM.
 * LangChain4j calls the chosen method and feeds the result back automatically.
 *
 * Every action is recorded into ReportService so the final HTML report captures
 * the full execution timeline, including inline screenshots.
 */
@Slf4j
@Component
public class BrowserTools {

    private final SimpMessagingTemplate ws;
    private final ReportService reportService;
    private final TestService testService;
    private final boolean headless;

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private boolean taskComplete = false;
    private String taskResult = "";
    private int screenshotCounter = 0;

    public BrowserTools(
            SimpMessagingTemplate ws,
            ReportService reportService,
            TestService testService,
            @Value("${browser.headless:true}") boolean headless) {
        this.ws = ws;
        this.reportService = reportService;
        this.testService = testService;
        this.headless = headless;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    public void startSession() {
        taskComplete = false;
        taskResult = "";
        screenshotCounter = 0;
        playwright = Playwright.create();
        browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(headless)
        );
        page = browser.newPage();
        page.setViewportSize(1280, 800);
        log.info("Browser session started (headless={})", headless);
        emit("🌐 Browser session started");
    }

    public void endSession() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        browser = null;
        playwright = null;
        page = null;
        log.info("Browser session closed");
        emit("🔒 Browser session closed");
    }

    public boolean isTaskComplete() { return taskComplete; }
    public String getTaskResult()   { return taskResult; }
    public Page getPage()           { return page; }

    // ─── Tools (visible to the LLM) ───────────────────────────────────────────

    @Tool("Navigate the browser to a given URL. Returns the page title after loading.")
    public String navigate(String url) {
        String msg = "Navigate to: " + url;
        emit("🔗 " + msg);
        reportService.recordAction(msg);
        try {
            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            dismissCookieConsent();
            String title = page.title();
            String ok = "Loaded page: \"" + title + "\" — " + url;
            emit("✅ " + ok);
            reportService.recordSuccess(ok);
            return "Navigated to " + url + ". Page title: " + title;
        } catch (Exception e) {
            String err = "Navigation failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return "Error navigating: " + e.getMessage();
        }
    }

    private void dismissCookieConsent() {
        String[][] patterns = {
            {"button", "Accept All"}, {"button", "Accept"}, {"button", "Allow All"},
            {"button", "I Agree"},   {"button", "Got it"}, {"button", "Allow"},
            {"button", "Accept Cookies"}, {"button", "Accept All Cookies"},
            {"button", "Consent"},   {"button", "Agree"},  {"button", "Yes"},
            {"button", "Continue"}
        };
        for (String[] p : patterns) {
            try {
                Locator loc = page.locator(p[0] + ":has-text(\"" + p[1] + "\")");
                if (loc.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                    loc.first().click();
                    emit("🍪 Dismissed cookie consent: \"" + p[1] + "\"");
                    return;
                }
            } catch (Exception ignored) {}
        }
        try {
            Locator loc = page.locator("[aria-label*=\"cookie\" i], [aria-label*=\"consent\" i]");
            if (loc.isVisible(new Locator.IsVisibleOptions().setTimeout(500))) {
                loc.first().click();
                emit("🍪 Dismissed cookie consent via aria-label");
            }
        } catch (Exception ignored) {}
    }

    @Tool("Click an element on the page. Use a CSS selector or visible text. " +
          "If the selector does not match, it will be treated as a text description " +
          "and the element will be auto-resolved. " +
          "Examples: '#submit-btn', 'button:has-text(\"Search\")', '.nav-link', 'Log In'")
    public String click(String selector) {
        String msg = "Click element: " + selector;
        emit("🖱️ " + msg);
        reportService.recordAction(msg);
        try {
            String resolved = resolveSelector(selector);
            page.waitForSelector(resolved,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(5000));
            page.click(resolved);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            String ok = resolved.equals(selector)
                    ? "Clicked: " + selector
                    : "Clicked: " + resolved + " (resolved from: " + selector + ")";
            emit("✅ " + ok);
            reportService.recordSuccess(ok);
            return "Clicked element: " + (resolved.equals(selector) ? selector : resolved + " (from: " + selector + ")");
        } catch (Exception e) {
            String err = "Click failed on '" + selector + "': " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return "Error clicking '" + selector + "': " + e.getMessage();
        }
    }

    @Tool("Type text into an input field identified by a CSS selector. " +
          "Clears the field first. If the selector does not match, it will be treated " +
          "as a label or placeholder description and auto-resolved. " +
          "Example: fill('#search', 'Java tutorials') or fill('Email', 'user@example.com')")
    public String fill(String selector, String text) {
        String msg = "Type into " + selector + ": \"" + text + "\"";
        emit("⌨️  " + msg);
        reportService.recordAction(msg);
        try {
            String resolved = resolveSelector(selector);
            page.waitForSelector(resolved,
                    new Page.WaitForSelectorOptions()
                            .setState(WaitForSelectorState.VISIBLE)
                            .setTimeout(5000));
            page.fill(resolved, text);
            String ok = resolved.equals(selector)
                    ? "Filled " + selector
                    : "Filled " + resolved + " (resolved from: " + selector + ")";
            emit("✅ " + ok);
            reportService.recordSuccess(ok);
            return "Filled '" + (resolved.equals(selector) ? selector : resolved + " (from: " + selector + ")") + "' with: " + text;
        } catch (Exception e) {
            String err = "Fill failed on '" + selector + "': " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return "Error filling '" + selector + "': " + e.getMessage();
        }
    }

    @Tool("Press a keyboard key. Examples: 'Enter', 'Tab', 'Escape', 'ArrowDown'")
    public String pressKey(String key) {
        String msg = "Press key: " + key;
        emit("⌨️  " + msg);
        reportService.recordAction(msg);
        try {
            page.keyboard().press(key);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            reportService.recordSuccess("Key pressed: " + key);
            return "Pressed key: " + key;
        } catch (Exception e) {
            reportService.recordError("Key press failed: " + e.getMessage());
            return "Error pressing key: " + e.getMessage();
        }
    }

    @Tool("Get the visible text content of the current page, truncated to 4000 characters. " +
          "Use this to read page content, find information, or verify state. " +
          "Includes text from iframes, Shadow DOM, and SVG elements.")
    public String getPageText() {
        emit("📄 Reading page content...");
        reportService.recordAction("Read page text from: " + page.url());
        try {
            @SuppressWarnings("unchecked")
            List<String> allText = (List<String>) page.evaluate("""
                    () => {
                        const parts = [];
                        // Main page text
                        const main = document.body?.innerText || '';
                        if (main.trim()) parts.push(main);
                        // SVG text elements
                        const svgTexts = Array.from(document.querySelectorAll('svg text'))
                            .map(t => t.textContent.trim()).filter(Boolean);
                        if (svgTexts.length) parts.push('--- SVG labels ---\\n' + svgTexts.join('\\n'));
                        // Shadow DOM text
                        const shadowParts = [];
                        document.querySelectorAll('*').forEach(el => {
                            if (el.shadowRoot) {
                                const st = el.shadowRoot.body?.innerText || el.shadowRoot.innerText || '';
                                if (st.trim()) shadowParts.push('[shadow:' + el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + '] ' + st.trim());
                            }
                        });
                        if (shadowParts.length) parts.push('--- Shadow DOM ---\\n' + shadowParts.join('\\n---\\n'));
                        // iframe text (same-origin only)
                        const frameTexts = [];
                        for (const f of document.querySelectorAll('iframe')) {
                            try {
                                const doc = f.contentDocument || f.contentWindow?.document;
                                if (doc) {
                                    const ft = doc.body?.innerText || '';
                                    if (ft.trim()) frameTexts.push('[iframe:' + (f.name || f.title || f.src) + '] ' + ft.trim());
                                }
                            } catch (e) {}
                        }
                        if (frameTexts.length) parts.push('--- iframes ---\\n' + frameTexts.join('\\n---\\n'));
                        return parts;
                    }
                    """);
            String combined = allText != null ? String.join("\n\n", allText) : "";
            String truncated = combined.length() > 4000 ? combined.substring(0, 4000) + "…" : combined;
            String obs = "Page text extracted (" + combined.length() + " chars) from: " + page.url();
            emit("✅ " + obs);
            reportService.recordObservation(obs + "\n\nContent preview:\n" + truncated.substring(0, Math.min(500, truncated.length())) + "…");
            return "Current URL: " + page.url() + "\n\nPage content:\n" + truncated;
        } catch (Exception e) {
            reportService.recordError("getPageText failed: " + e.getMessage());
            return "Error getting page text: " + e.getMessage();
        }
    }

    @Tool("Get the current page URL and title.")
    public String getCurrentPage() {
        String info = "URL: " + page.url() + " | Title: " + page.title();
        reportService.recordObservation("Current page — " + info);
        return info;
    }

    @Tool("Extract all links from the current page as a list of [text → href].")
    public String getLinks() {
        emit("🔍 Extracting links...");
        reportService.recordAction("Extract links from: " + page.url());
        try {
            @SuppressWarnings("unchecked")
            var links = (java.util.List<String>) page.evaluate(
                "() => Array.from(document.querySelectorAll('a[href]'))" +
                ".slice(0, 30)" +
                ".map(a => a.innerText.trim() + ' → ' + a.href)"
            );
            String obs = "Found " + links.size() + " links on " + page.url();
            reportService.recordObservation(obs + "\n" + String.join("\n", links));
            return "Links on page:\n" + String.join("\n", links);
        } catch (Exception e) {
            reportService.recordError("getLinks failed: " + e.getMessage());
            return "Error getting links: " + e.getMessage();
        }
    }

    @Tool("Scroll the page. direction must be 'up' or 'down'. " +
          "amount is pixels (e.g. 500).")
    public String scroll(String direction, int amount) {
        String msg = "Scroll " + direction + " by " + amount + "px";
        emit("📜 " + msg);
        reportService.recordAction(msg);
        int delta = direction.equalsIgnoreCase("up") ? -amount : amount;
        page.evaluate("window.scrollBy(0, " + delta + ")");
        reportService.recordSuccess(msg + " — done");
        return "Scrolled " + direction + " by " + amount + "px";
    }

    @Tool("Take a screenshot of the current browser state. " +
          "Provide a short label describing what is visible (e.g. 'Search results page'). " +
          "The screenshot will be embedded in the final report.")
    public String takeScreenshot(String label) {
        screenshotCounter++;
        String safeLabel = (label == null || label.isBlank()) ? "Screenshot " + screenshotCounter : label;
        emit("📸 Taking screenshot: " + safeLabel);
        reportService.recordAction("Take screenshot: " + safeLabel);
        try {
            // Capture to bytes (no disk write needed — we embed in the report)
            byte[] imageBytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(false));
            reportService.recordScreenshot(safeLabel, imageBytes);
            String ok = "Screenshot captured: " + safeLabel;
            emit("✅ " + ok);
            return "Screenshot taken and added to report: " + safeLabel;
        } catch (Exception e) {
            String err = "Screenshot failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return "Error taking screenshot: " + e.getMessage();
        }
    }

    @Tool("Run arbitrary JavaScript on the page and return the result as a string. " +
          "Example: 'document.querySelectorAll(\".price\").length'")
    public String runJavaScript(String script) {
        emit("⚡ Running JS: " + script);
        reportService.recordAction("Run JavaScript: " + script);
        try {
            Object result = page.evaluate(script);
            String res = "JS result: " + (result != null ? result.toString() : "null");
            reportService.recordSuccess(res);
            return res;
        } catch (Exception e) {
            reportService.recordError("JS error: " + e.getMessage());
            return "JS error: " + e.getMessage();
        }
    }

    @Tool("""
            Find the best CSS selector for an element you want to interact with on the current page.
            Describe the element by what you can see (label, button text, placeholder, icon name, etc.).
            Returns a reliable CSS selector and the strategy used to find it.
            Call this before click() or fill() when you are unsure of the exact selector.
            Examples: 'Log In button', 'Email input field', 'Save button at bottom of form', 'Close icon'
            """)
    public String findElement(String description) {
        emit("🔍 Finding element: " + description);
        reportService.recordAction("Find element: " + description);
        try {
            ElementLocator.LocatorResult result = ElementLocator.findElement(page, description);
            if (result == null) {
                String hints = ElementLocator.buildInteractiveElementsList(page);
                String msg = "Could not find element matching \"" + description + "\".\n" + hints;
                emit("⚠️ " + msg);
                reportService.recordError(msg);
                return msg;
            }
            String ok = "Found: " + result.selector() + "  [via " + result.strategy() + "]";
            emit("✅ " + ok);
            reportService.recordSuccess(ok);
            return "Selector: " + result.selector()
                    + "\nStrategy: " + result.strategy()
                    + "\nMatched text: " + result.matchedText();
        } catch (Exception e) {
            String err = "findElement failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("Call this tool when the task is fully complete. " +
          "Provide a clear, detailed summary of what was accomplished and any results found.")
    public String completeTask(String summary) {
        emit("🎉 Task completed: " + summary);
        reportService.recordComplete(summary);
        taskComplete = true;
        taskResult = summary;
        return "Task marked complete: " + summary;
    }

    @Tool("Save the current instruction as a reusable test case. " +
          "Provide a short name and the full task description.")
    public String saveTestCase(String name, String taskInstruction) {
        TestCase tc = testService.addTest(name, taskInstruction);
        String ok = "Saved test case \"" + name + "\" (id: " + tc.id() + ")";
        emit("📋 " + ok);
        reportService.recordAction(ok);
        return ok;
    }

    @Tool("List all saved test cases with their IDs and tasks.")
    public String listTestCases() {
        var all = testService.listTests();
        if (all.isEmpty()) return "No saved test cases.";
        StringBuilder sb = new StringBuilder("Saved test cases:\n");
        for (TestCase tc : all) {
            sb.append("  • ").append(tc.id()).append(" — ")
              .append(tc.name()).append(": ")
              .append(tc.task()).append("\n");
        }
        reportService.recordAction("Listed " + all.size() + " test cases");
        return sb.toString();
    }

    // ─── Context tools: iframes, Shadow DOM, SVG, Canvas ─────────────────────

    @Tool("List all iframes on the current page with index, name, title, and src URL. " +
          "Use this to discover elements that may be inside embedded frames.")
    public String listIframes() {
        emit("🔲 Listing iframes…");
        reportService.recordAction("List iframes on page");
        try {
            List<Frame> frames = page.frames();
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Frame f : frames) {
                if (f == f.page().mainFrame()){
                    continue;
                }
                count++;
                String name = f.name();
                String url = f.url();
                String title = "";
                try { title = f.title(); } catch (Exception ignored) { title = "(cross-origin)"; }
                sb.append("  ").append(count).append(". name=\"").append(name)
                  .append("\" title=\"").append(title)
                  .append("\" url=\"").append(url).append("\"\n");
            }
            String result = count > 0
                    ? "Found " + count + " iframe(s):\n" + sb.toString().trim()
                    : "No iframes found on this page.";
            emit("✅ " + (count > 0 ? "Found " + count + " iframe(s)" : "No iframes"));
            reportService.recordSuccess(result);
            return result;
        } catch (Exception e) {
            String err = "listIframes failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("List all elements with open Shadow DOM on the current page. " +
          "Returns the tag name, id, and first-level children of each shadow host. " +
          "Use this when web components might be hiding interactive elements.")
    public String listShadowHosts() {
        emit("🌓 Listing shadow hosts…");
        reportService.recordAction("List shadow DOM hosts");
        try {
            @SuppressWarnings("unchecked")
            List<String> hosts = (List<String>) page.evaluate("""
                    () => {
                        const results = [];
                        const all = document.querySelectorAll('*');
                        for (const el of all) {
                            if (el.shadowRoot) {
                                const tag = el.tagName.toLowerCase();
                                const id = el.id ? '#' + el.id : '';
                                const children = Array.from(el.shadowRoot.querySelectorAll('*'))
                                    .slice(0, 10).map(c => c.tagName.toLowerCase()).join(', ');
                                results.push(tag + id + '  [' + children + ']');
                            }
                        }
                        return results.slice(0, 30);
                    }
                    """);
            String result = (hosts == null || hosts.isEmpty())
                    ? "No shadow DOM hosts found on this page."
                    : "Shadow DOM hosts (" + hosts.size() + "):\n" + String.join("\n", hosts);
            emit("✅ " + (hosts != null ? "Found " + hosts.size() + " shadow host(s)" : "No shadow hosts"));
            reportService.recordSuccess(result);
            return result;
        } catch (Exception e) {
            String err = "listShadowHosts failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("List all SVG elements (charts, graphs, icons, text labels) on the page. " +
          "Returns tag, text content, aria-label, and CSS selector for each. " +
          "Use this to understand chart content and find interactive graph elements.")
    public String listSvgElements() {
        emit("📊 Listing SVG elements…");
        reportService.recordAction("List SVG elements");
        try {
            @SuppressWarnings("unchecked")
            List<String> elements = (List<String>) page.evaluate("""
                    () => {
                        const results = [];
                        const all = document.querySelectorAll('svg, [role="img"]');
                        for (const svg of all) {
                            const tag = svg.tagName.toLowerCase();
                            const id = svg.id ? '#' + svg.id : '';
                            const aria = svg.getAttribute('aria-label') || '';
                            const texts = Array.from(svg.querySelectorAll('text'))
                                .map(t => t.textContent.trim()).filter(Boolean).join(' | ');
                            results.push(tag + id + (aria ? ' aria="' + aria + '"' : '') + (texts ? ' texts="' + texts + '"' : ''));
                        }
                        return results;
                    }
                    """);
            String result = (elements == null || elements.isEmpty())
                    ? "No SVG elements found on this page."
                    : "SVG elements (" + elements.size() + "):\n" + String.join("\n", elements);
            emit("✅ " + (elements != null ? "Found " + elements.size() + " SVG element(s)" : "No SVG elements"));
            reportService.recordSuccess(result);
            return result;
        } catch (Exception e) {
            String err = "listSvgElements failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    @Tool("Get accessibility and context information about canvas elements (charts, graphs) " +
          "on the page. Returns aria-labels, data attributes, and surrounding text for each canvas. " +
          "Use this when you need to understand a canvas-rendered chart or graph.")
    public String getCanvasInfo() {
        emit("🖼️ Reading canvas info…");
        reportService.recordAction("Get canvas info");
        try {
            @SuppressWarnings("unchecked")
            List<String> info = (List<String>) page.evaluate("""
                    () => {
                        const results = [];
                        const canvases = document.querySelectorAll('canvas');
                        for (const c of canvases) {
                            const tag = c.tagName.toLowerCase();
                            const id = c.id ? '#' + c.id : '';
                            const cls = c.className ? '.' + c.className : '';
                            const aria = c.getAttribute('aria-label') || '(none)';
                            const title = c.getAttribute('title') || '(none)';
                            const ds = c.dataset ? Object.keys(c.dataset).map(k => 'data-' + k + '=' + c.dataset[k]).join(', ') : '(none)';
                            const rect = c.getBoundingClientRect();
                            const dims = rect.width + 'x' + rect.height + ' at (' + Math.round(rect.left) + ',' + Math.round(rect.top) + ')';
                            const parent = c.parentElement ? (c.parentElement.tagName.toLowerCase() + (c.parentElement.id ? '#' + c.parentElement.id : '')) : '(none)';
                            const parentText = c.parentElement ? (c.parentElement.textContent || '').trim().slice(0, 120) : '';
                            results.push(tag + id + cls + ' ' + dims + ' aria="' + aria + '" title="' + title + '" data={' + ds + '} parent=<' + parent + '> "' + parentText + '"');
                        }
                        return results;
                    }
                    """);
            String result = (info == null || info.isEmpty())
                    ? "No canvas elements found on this page."
                    : "Canvas elements (" + info.size() + "):\n" + String.join("\n", info);
            emit("✅ " + (info != null ? "Found " + info.size() + " canvas element(s)" : "No canvas elements"));
            reportService.recordSuccess(result);
            return result;
        } catch (Exception e) {
            String err = "getCanvasInfo failed: " + e.getMessage();
            emit("❌ " + err);
            reportService.recordError(err);
            return err;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Resolve a selector string to a valid CSS selector.
     * If the raw selector matches a DOM element, use it as-is.
     * Otherwise, treat it as a text description via ElementLocator.
     * Falls back to searching inside child iframes.
     */
    private String resolveSelector(String input) {
        if (page == null) return input;
        try {
            long count = page.locator(input).count();
            if (count > 0) return input;
        } catch (Exception ignored) {}

        try {
            ElementLocator.LocatorResult result = ElementLocator.findElement(page, input);
            if (result != null) {
                log.info("Resolved '{}' → '{}' via {}", input, result.selector(), result.strategy());
                return result.selector();
            }
        } catch (Exception ignored) {}

        // Try iframes
        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame()) continue;
            try {
                long count = frame.locator(input).count();
                if (count > 0) {
                    log.info("Resolved '{}' in iframe", input);
                    return input;
                }
            } catch (Exception ignored) {}
        }

        return input;
    }

    private void emit(String message) {
        log.info("[BrowserTools] {}", message);
        ws.convertAndSend("/topic/logs", message);
    }
}
