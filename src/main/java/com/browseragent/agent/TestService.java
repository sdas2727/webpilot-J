package com.browseragent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class TestService {

    private static final Path TESTS_DIR = Paths.get("tests");
    private static final Path TESTS_FILE = TESTS_DIR.resolve("tests.json");

    private final ObjectMapper mapper;
    private final List<TestCase> tests = new ArrayList<>();

    public TestService() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void load() {
        try {
            Files.createDirectories(TESTS_DIR);
            if (Files.exists(TESTS_FILE)) {
                List<TestCase> loaded = mapper.readValue(
                        TESTS_FILE.toFile(),
                        new TypeReference<List<TestCase>>() {});
                tests.addAll(loaded);
                log.info("Loaded {} test cases from {}", tests.size(), TESTS_FILE);
            }
        } catch (IOException e) {
            log.warn("Could not load test cases: {}", e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(TESTS_DIR);
            mapper.writerWithDefaultPrettyPrinter().writeValue(TESTS_FILE.toFile(), tests);
        } catch (IOException e) {
            log.error("Failed to save test cases: {}", e.getMessage());
        }
    }

    public List<TestCase> listTests() {
        return List.copyOf(tests);
    }

    public TestCase addTest(String name, String task) {
        TestCase tc = new TestCase(UUID.randomUUID().toString(), name, task, Instant.now());
        tests.add(tc);
        persist();
        log.info("Saved test case: {} ({})", name, tc.id());
        return tc;
    }

    public boolean deleteTest(String id) {
        boolean removed = tests.removeIf(tc -> tc.id().equals(id));
        if (removed) persist();
        return removed;
    }

    public TestCase getTest(String id) {
        return tests.stream()
                .filter(tc -> tc.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}
