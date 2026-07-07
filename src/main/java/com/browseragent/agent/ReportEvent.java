package com.browseragent.agent;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * A single step in the agent's execution timeline.
 * Screenshots are stored as raw bytes so the report can embed them as base64.
 */
@Getter
@Builder
public class ReportEvent {

    public enum Type {
        START,       // task started
        ACTION,      // browser action (navigate, click, fill, …)
        OBSERVATION, // page text / link extraction result
        SCREENSHOT,  // screenshot taken
        SUCCESS,     // action succeeded
        ERROR,       // action failed
        COMPLETE     // task finished
    }

    private final Instant timestamp;
    private final Type type;
    private final String message;

    /** Non-null only for SCREENSHOT events */
    private final byte[] screenshotBytes;
    private final String screenshotLabel;
}
