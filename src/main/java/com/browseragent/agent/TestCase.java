package com.browseragent.agent;

import java.time.Instant;

public record TestCase(
    String id,
    String name,
    String task,
    Instant createdAt
) {}
