package com.miniredis.compatibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reusable test case model for differential compatibility testing between
 * Redis 7.2 (Reference) and MiniRedis.
 */
public class DifferentialTestCase {

    public enum NormalizationType {
        EXACT,              // Strict byte-for-byte and frame match
        SET_UNORDERED,      // Unordered collection (e.g. SMEMBERS)
        TTL_TOLERANCE,      // Dynamic countdown with acceptable second delta
        INFO_METRIC_SUBSET, // Text key:value pairs where subset must match
        POSITIVE_INTEGER,   // Both return positive integer (e.g. COMMAND COUNT discovery)
        TEXT_EQUIVALENT     // String content matches across simple/bulk wire variants (e.g. PING [msg])
    }

    public static class Step {
        private final String command;
        private final String expectedReferenceResponse; // Expected RESP or human format
        private final NormalizationType normalization;

        public Step(String command, String expectedReferenceResponse, NormalizationType normalization) {
            this.command = command;
            this.expectedReferenceResponse = expectedReferenceResponse;
            this.normalization = normalization;
        }

        public Step(String command, String expectedReferenceResponse) {
            this(command, expectedReferenceResponse, NormalizationType.EXACT);
        }

        public String getCommand() {
            return command;
        }

        public String getExpectedReferenceResponse() {
            return expectedReferenceResponse;
        }

        public NormalizationType getNormalization() {
            return normalization;
        }
    }

    private final String testId;
    private final String category;
    private final String description;
    private final List<String> setupCommands;
    private final List<Step> steps;

    public DifferentialTestCase(String testId, String category, String description) {
        this.testId = testId;
        this.category = category;
        this.description = description;
        this.setupCommands = new ArrayList<>();
        this.steps = new ArrayList<>();
    }

    public DifferentialTestCase withSetup(String setupCommand) {
        this.setupCommands.add(setupCommand);
        return this;
    }

    public DifferentialTestCase addStep(String command, String expectedResponse) {
        this.steps.add(new Step(command, expectedResponse, NormalizationType.EXACT));
        return this;
    }

    public DifferentialTestCase addStep(String command, String expectedResponse, NormalizationType norm) {
        this.steps.add(new Step(command, expectedResponse, norm));
        return this;
    }

    public String getTestId() {
        return testId;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getSetupCommands() {
        return Collections.unmodifiableList(setupCommands);
    }

    public List<Step> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
