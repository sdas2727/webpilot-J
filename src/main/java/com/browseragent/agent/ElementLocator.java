package com.browseragent.agent;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class ElementLocator {

    public record LocatorResult(String selector, String strategy, String matchedText, double confidence) {}

    private static final String[] INTERACTIVE_TAGS = {"button", "a", "input", "select", "textarea", "[tabindex]"};

    /**
     * Find the best CSS selector for an element matching the given description.
     * Tries multiple strategies in order of reliability.
     */
    public static LocatorResult findElement(Page page, String description) {
        String clean = description.trim().replaceAll("\\s+", " ");

        List<LocatorResult> candidates = new ArrayList<>();

        // 1 — Try Playwright getByRole (most accessible, most reliable)
        candidates.addAll(tryByRole(page, clean));

        // 2 — Try getByLabel (best for form fields)
        candidates.addAll(tryByLabel(page, clean));

        // 3 — Try getByPlaceholder (common for inputs)
        candidates.addAll(tryByPlaceholder(page, clean));

        // 4 — Try :has-text() with common tags
        candidates.addAll(tryHasText(page, clean));

        // 5 — Try aria-label attribute
        candidates.addAll(tryAriaLabel(page, clean));

        // 6 — JavaScript DOM scan (most flexible fallback, includes shadow DOM + SVG)
        candidates.addAll(tryDomScan(page, clean));

        // 7 — Search inside child iframes
        candidates.addAll(tryFrames(page, clean));

        return candidates.stream()
                .max(Comparator.comparingDouble(LocatorResult::confidence))
                .orElse(null);
    }

    // ─── Strategy: getByRole ──────────────────────────────────────────────────

    private static List<LocatorResult> tryByRole(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        String lower = text.toLowerCase();

        for (RoleHint hint : ROLE_HINTS) {
            if (!lower.contains(hint.keyword)) continue;
            try {
                var locator = page.getByRole(hint.role,
                        new Page.GetByRoleOptions().setName(text).setExact(false));
                if (locator.count() > 0) {
                    String matched = locator.first().textContent();
                    String selector = hint.role.name().toLowerCase() + ":has-text(\"" + escCss(text) + "\")";
                    results.add(new LocatorResult(
                            selector,
                            "getByRole(" + hint.role.name().toLowerCase() + ")",
                            matched != null ? matched.trim() : text,
                            0.95));
                }
            } catch (Exception ignored) {}
        }

        // Also try without keyword filter — just use name
        for (RoleHint hint : ROLE_HINTS) {
            try {
                var locator = page.getByRole(hint.role,
                        new Page.GetByRoleOptions().setName(text).setExact(false));
                if (locator.count() > 0) {
                    String matched = locator.first().textContent();
                    results.add(new LocatorResult(
                            hint.role.name().toLowerCase() + ":has-text(\"" + escCss(text) + "\")",
                            "getByRole(" + hint.role.name().toLowerCase() + ")",
                            matched != null ? matched.trim() : text,
                            0.90));
                    break;
                }
            } catch (Exception ignored) {}
        }

        return results;
    }

    private record RoleHint(AriaRole role, String keyword) {}
    private static final List<RoleHint> ROLE_HINTS = List.of(
            new RoleHint(AriaRole.BUTTON, "button"),
            new RoleHint(AriaRole.LINK, "link"),
            new RoleHint(AriaRole.TEXTBOX, "input"),
            new RoleHint(AriaRole.TEXTBOX, "field"),
            new RoleHint(AriaRole.COMBOBOX, "dropdown"),
            new RoleHint(AriaRole.COMBOBOX, "select"),
            new RoleHint(AriaRole.CHECKBOX, "checkbox"),
            new RoleHint(AriaRole.RADIO, "radio"),
            new RoleHint(AriaRole.HEADING, "heading"),
            new RoleHint(AriaRole.TAB, "tab"),
            new RoleHint(AriaRole.MENUITEM, "menu"),
            new RoleHint(AriaRole.OPTION, "option")
    );

    // ─── Strategy: getByLabel ─────────────────────────────────────────────────

    private static List<LocatorResult> tryByLabel(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        try {
            var locator = page.getByLabel(text, new Page.GetByLabelOptions().setExact(false));
            if (locator.count() > 0) {
                String tag = locator.first().evaluate("el => el.tagName.toLowerCase()").toString();
                results.add(new LocatorResult(
                        tag + "[aria-label*=\"" + escCss(text) + "\"]",
                        "getByLabel",
                        text,
                        0.95));
            }
        } catch (Exception ignored) {}

        // Partial label match via JS
        try {
            @SuppressWarnings("unchecked")
            List<String> matches = (List<String>) page.evaluate("""
                    (text) => {
                        const labels = document.querySelectorAll('label');
                        const results = [];
                        for (const lbl of labels) {
                            const forId = lbl.getAttribute('for');
                            const lblText = lbl.textContent.trim().toLowerCase();
                            if (forId && lblText.includes(text.toLowerCase())) {
                                const input = document.getElementById(forId);
                                if (input) {
                                    const tag = input.tagName.toLowerCase();
                                    const id = input.id ? '#' + input.id : '';
                                    results.push(tag + id);
                                }
                            }
                        }
                        return results;
                    }
                    """, text);
            for (String sel : matches) {
                results.add(new LocatorResult(sel, "label[for]", text, 0.90));
            }
        } catch (Exception ignored) {}

        return results;
    }

    // ─── Strategy: getByPlaceholder ───────────────────────────────────────────

    private static List<LocatorResult> tryByPlaceholder(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        try {
            var locator = page.getByPlaceholder(text, new Page.GetByPlaceholderOptions().setExact(false));
            if (locator.count() > 0) {
                results.add(new LocatorResult(
                        "input[placeholder*=\"" + escCss(text) + "\"]",
                        "placeholder",
                        text,
                        0.90));
            }
        } catch (Exception ignored) {}
        return results;
    }

    // ─── Strategy: :has-text() CSS — direct Playwright locator ────────────────

    private static List<LocatorResult> tryHasText(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        String[] tags = {"button", "a", "span", "div", "label", "h1", "h2", "h3", "li", "td"};

        for (String tag : tags) {
            try {
                String sel = tag + ":has-text(\"" + escCss(text) + "\")";
                var locator = page.locator(sel).first();
                if (locator.count() > 0) {
                    String matched = locator.textContent();
                    results.add(new LocatorResult(
                            sel,
                            "has-text",
                            matched != null ? matched.trim() : text,
                            0.80));
                }
            } catch (Exception ignored) {}
        }
        return results;
    }

    // ─── Strategy: aria-label attribute ───────────────────────────────────────

    private static List<LocatorResult> tryAriaLabel(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        try {
            String sel = "[aria-label*=\"" + escCss(text) + "\"]";
            var locator = page.locator(sel).first();
            if (locator.count() > 0) {
                String tag = locator.evaluate("el => el.tagName.toLowerCase()").toString();
                results.add(new LocatorResult(
                        tag + sel,
                        "aria-label",
                        text,
                        0.80));
            }
        } catch (Exception ignored) {}
        return results;
    }

    // ─── Strategy: JavaScript DOM scan — universal fallback ───────────────────
    // Traverses open Shadow DOM and includes SVG elements.

    private static List<LocatorResult> tryDomScan(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        String[] keywords = text.toLowerCase().split("\\s+");

        try {
            @SuppressWarnings("unchecked")
            List<List<String>> elements = (List<List<String>>) page.evaluate("""
                    () => {
                        function findElements(root, sel) {
                            let res = [];
                            root.querySelectorAll(sel).forEach(el => res.push(el));
                            root.querySelectorAll('*').forEach(el => {
                                if (el.shadowRoot) res = res.concat(findElements(el.shadowRoot, sel));
                            });
                            return res;
                        }
                        const candidates = [];
                        const selectors = 'button, a, input, select, textarea, [role="button"], [role="link"], [role="textbox"], [role="combobox"], [role="checkbox"], [role="radio"], [role="tab"], [role="menuitem"], [role="option"], [tabindex]:not([tabindex="-1"]), svg, [role="img"]';
                        const all = findElements(document, selectors);
                        for (const el of all) {
                            const texts = [];
                            const inner = (el.textContent || '').trim();
                            if (inner) texts.push(inner);
                            const val = el.value || '';
                            if (val) texts.push(val);
                            const ph = el.getAttribute('placeholder') || '';
                            if (ph) texts.push(ph);
                            const aria = el.getAttribute('aria-label') || '';
                            if (aria) texts.push(aria);
                            const title = el.getAttribute('title') || '';
                            if (title) texts.push(title);
                            const alt = el.getAttribute('alt') || '';
                            if (alt) texts.push(alt);
                            const label = el.closest('label');
                            if (label) { const lt = (label.textContent || '').trim(); if (lt) texts.push(lt); }
                            // SVG text child elements
                            if (el.tagName.toLowerCase() === 'svg') {
                                const svgTexts = Array.from(el.querySelectorAll('text')).map(t => t.textContent.trim()).filter(Boolean).join(' ');
                                if (svgTexts) texts.push(svgTexts);
                            }

                            const tag = el.tagName.toLowerCase();
                            const id = el.id ? '#' + el.id : '';
                            let cls = '';
                            for (const c of el.classList) {
                                if (!c.startsWith('slds') && !c.startsWith('ui') && !c.startsWith('_')) {
                                    cls += '.' + c;
                                    break;
                                }
                            }
                            const type = el.getAttribute('type') ? '[type="' + el.getAttribute('type') + '"]' : '';
                            const selector = tag + id + cls + type;

                            const combined = texts.join(' ').toLowerCase();
                            candidates.push([selector, combined, tag, el.id]);
                        }
                        return candidates;
                    }
                    """);

            if (elements == null) return results;

            for (List<String> el : elements) {
                String selector = el.get(0);
                String elText = el.get(1);
                String tag = el.get(2);

                int matchCount = 0;
                for (String kw : keywords) {
                    if (elText.contains(kw)) matchCount++;
                }

                if (matchCount > 0) {
                    double confidence = 0.50 + (matchCount / (double) keywords.length) * 0.30;
                    if (Stream.of("button", "a", "input", "select", "textarea").anyMatch(t -> t.equals(tag))) {
                        confidence += 0.05;
                    }
                    String disp = elText.length() > 80 ? elText.substring(0, 80) + "…" : elText;
                    results.add(new LocatorResult(selector, "dom-scan", disp, Math.min(confidence, 0.95)));
                }
            }
        } catch (Exception e) {
            log.debug("DOM scan failed: {}", e.getMessage());
        }

        return results;
    }

    // ─── Strategy: iframe search — same logic applied to each child frame ─────

    private static List<LocatorResult> tryFrames(Page page, String text) {
        List<LocatorResult> results = new ArrayList<>();
        String[] keywords = text.toLowerCase().split("\\s+");

        for (Frame frame : page.frames()) {
            if (frame == page.mainFrame()) continue;
            try {
                @SuppressWarnings("unchecked")
                List<List<String>> elements = (List<List<String>>) frame.evaluate("""
                        (text) => {
                            function findElements(root, sel) {
                                let res = [];
                                root.querySelectorAll(sel).forEach(el => res.push(el));
                                root.querySelectorAll('*').forEach(el => {
                                    if (el.shadowRoot) res = res.concat(findElements(el.shadowRoot, sel));
                                });
                                return res;
                            }
                            const candidates = [];
                            const selectors = 'button, a, input, select, textarea, [role="button"], [role="link"], [role="textbox"], [role="combobox"], [role="checkbox"], [role="radio"], [tabindex]:not([tabindex="-1"]), svg, [role="img"]';
                            const all = findElements(document, selectors);
                            for (const el of all) {
                                const texts = [];
                                const inner = (el.textContent || '').trim();
                                if (inner) texts.push(inner);
                                const val = el.value || '';
                                if (val) texts.push(val);
                                const ph = el.getAttribute('placeholder') || '';
                                if (ph) texts.push(ph);
                                const aria = el.getAttribute('aria-label') || '';
                                if (aria) texts.push(aria);
                                const title = el.getAttribute('title') || '';
                                if (title) texts.push(title);
                                const label = el.closest('label');
                                if (label) { const lt = (label.textContent || '').trim(); if (lt) texts.push(lt); }
                                if (el.tagName.toLowerCase() === 'svg') {
                                    const svgTexts = Array.from(el.querySelectorAll('text')).map(t => t.textContent.trim()).filter(Boolean).join(' ');
                                    if (svgTexts) texts.push(svgTexts);
                                }
                                const tag = el.tagName.toLowerCase();
                                const id = el.id ? '#' + el.id : '';
                                const selector = tag + id;
                                const combined = texts.join(' ').toLowerCase();
                                candidates.push([selector, combined, tag]);
                            }
                            return candidates;
                        }
                        """, text);

                if (elements == null) continue;
                for (List<String> el : elements) {
                    String selector = el.get(0);
                    String elText = el.get(1);
                    String tag = el.get(2);
                    int matchCount = 0;
                    for (String kw : keywords) {
                        if (elText.contains(kw)) matchCount++;
                    }
                    if (matchCount > 0) {
                        double confidence = 0.50 + (matchCount / (double) keywords.length) * 0.30;
                        confidence *= 0.90;
                        String disp = elText.length() > 80 ? elText.substring(0, 80) + "…" : elText;
                        results.add(new LocatorResult("iframe " + selector, "iframe dom-scan", disp, Math.min(confidence, 0.90)));
                    }
                }
            } catch (Exception ignored) {}
        }
        return results;
    }

    // ─── Build list of visible interactive elements for hints ─────────────────
    // Includes open Shadow DOM and SVG elements.

    @SuppressWarnings("unchecked")
    public static String buildInteractiveElementsList(Page page) {
        try {
            List<String> items = (List<String>) page.evaluate("""
                    () => {
                        function findElements(root, sel) {
                            let res = [];
                            root.querySelectorAll(sel).forEach(el => res.push(el));
                            root.querySelectorAll('*').forEach(el => {
                                if (el.shadowRoot) res = res.concat(findElements(el.shadowRoot, sel));
                            });
                            return res;
                        }
                        const sel = 'button, a, input, select, textarea, [role="button"], [role="link"], [role="textbox"], [role="combobox"], svg, [role="img"]';
                        const all = findElements(document, sel);
                        const visible = [];
                        for (const el of all) {
                            const rect = el.getBoundingClientRect();
                            if (rect.width === 0 || rect.height === 0) continue;
                            const style = window.getComputedStyle(el);
                            if (style.display === 'none' || style.visibility === 'hidden') continue;
                            const text = (el.textContent || el.value || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.title || '').trim();
                            if (!text && el.tagName !== 'INPUT' && el.tagName !== 'SVG') continue;
                            const tag = el.tagName.toLowerCase();
                            const id = el.id ? '#' + el.id : '';
                            const type = el.getAttribute('type') ? ' type="' + el.getAttribute('type') + '"' : '';
                            visible.push(tag + id + type + '  "' + text.slice(0, 60) + '"');
                        }
                        return visible.slice(0, 30);
                    }
                    """);

            if (items == null || items.isEmpty()) return "No visible interactive elements found on the page.";

            StringBuilder sb = new StringBuilder("Visible interactive elements on this page:\n");
            for (int i = 0; i < items.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(items.get(i)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "Could not scan page elements: " + e.getMessage();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String escCss(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
