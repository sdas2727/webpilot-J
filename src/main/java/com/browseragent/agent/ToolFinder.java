package com.browseragent.agent;

import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolFinder {

    private record ToolInfo(String name, String description, String signature, String category) {}

    private final List<ToolInfo> tools = new ArrayList<>();

    public ToolFinder(BrowserTools browserTools, SalesforceTools salesforceTools) {
        indexTools(browserTools);
        indexTools(salesforceTools);
        log.info("ToolFinder indexed {} tools from BrowserTools and SalesforceTools", tools.size());
    }

    private void indexTools(Object bean) {
        String category = bean.getClass().getSimpleName().replace("Tools", "");
        for (Method m : bean.getClass().getMethods()) {
            Tool ann = m.getAnnotation(Tool.class);
            if (ann == null) continue;
            String name = ann.name().isBlank() ? m.getName() : ann.name();
            String sig = name + "(" + buildParams(m) + ")";
            tools.add(new ToolInfo(name, String.join(" ", ann.value()), sig, category));
        }
    }

    private static String buildParams(Method m) {
        return Arrays.stream(m.getParameters())
                .map(p -> p.getType().getSimpleName())
                .collect(Collectors.joining(", "));
    }

    @Tool("""
            Find and recommend the most relevant browser or Salesforce tools for a given task.
            Describe what you want to do in natural language — the tool index will be searched
            and the best-matching tools will be returned with their signatures and descriptions.
            Example: findTool("log into Salesforce and create an Account record")
            """)
    public String findTool(String taskDescription) {
        if (tools.isEmpty()) return "No tools indexed.";

        String[] queryWords = tokenize(taskDescription);
        if (queryWords.length == 0) {
            return "Please describe the task more specifically.";
        }

        List<ScoredTool> scored = new ArrayList<>();
        for (ToolInfo t : tools) {
            int s = score(t, queryWords);
            if (s > 0) {
                scored.add(new ScoredTool(t, s));
            }
        }

        if (scored.isEmpty()) {
            return "No matching tools found for \"" + taskDescription
                    + "\". Try describing the task differently.";
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        StringBuilder result = new StringBuilder("Top matching tools:\n\n");
        int limit = Math.min(4, scored.size());
        for (int i = 0; i < limit; i++) {
            ToolInfo t = scored.get(i).tool;
            result.append(i + 1).append(". ").append(t.signature).append("\n")
                    .append("   ").append(truncate(t.description, 140)).append("\n")
                    .append("   [").append(t.category).append("]").append("\n\n");
        }
        return result.toString().trim();
    }

    private static int score(ToolInfo t, String[] queryWords) {
        String haystack = (t.name + " " + t.description + " " + t.category).toLowerCase();
        int score = 0;
        for (String qw : queryWords) {
            if (haystack.contains(qw)) {
                score++;
            }
        }
        return score;
    }

    private static String[] tokenize(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim()
                .split("\\s+");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s == null ? "" : s;
        return s.substring(0, max) + "…";
    }

    private record ScoredTool(ToolInfo tool, int score) {}
}
