package com.miniredis.compatibility;

/**
 * Result of a single differential step or test execution.
 */
public class DifferentialResult {

    public enum Status {
        MATCH,
        PARTIAL_MATCH,
        MISMATCH,
        ERROR
    }

    public enum Mode {
        LIVE_REFERENCE_MODE,
        GOLDEN_ORACLE_MODE
    }

    private final String testId;
    private final String command;
    private final Status status;
    private final Mode executionMode;
    private final String referenceOutput;
    private final String miniredisOutput;
    private final String failureReason;

    public DifferentialResult(String testId, String command, Status status, Mode executionMode,
                              String referenceOutput, String miniredisOutput, String failureReason) {
        this.testId = testId;
        this.command = command;
        this.status = status;
        this.executionMode = executionMode;
        this.referenceOutput = referenceOutput;
        this.miniredisOutput = miniredisOutput;
        this.failureReason = failureReason;
    }

    public static DifferentialResult match(String testId, String command, Mode mode, String output) {
        return new DifferentialResult(testId, command, Status.MATCH, mode, output, output, null);
    }

    public static DifferentialResult mismatch(String testId, String command, Mode mode,
                                              String referenceOutput, String miniredisOutput, String reason) {
        return new DifferentialResult(testId, command, Status.MISMATCH, mode, referenceOutput, miniredisOutput, reason);
    }

    public static DifferentialResult partialMatch(String testId, String command, Mode mode,
                                                  String referenceOutput, String miniredisOutput, String reason) {
        return new DifferentialResult(testId, command, Status.PARTIAL_MATCH, mode, referenceOutput, miniredisOutput, reason);
    }

    public Mode getExecutionMode() {
        return executionMode;
    }

    public String getTestId() {
        return testId;
    }

    public String getCommand() {
        return command;
    }

    public Status getStatus() {
        return status;
    }

    public String getReferenceOutput() {
        return referenceOutput;
    }

    public String getMiniredisOutput() {
        return miniredisOutput;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public boolean isPass() {
        return status == Status.MATCH || status == Status.PARTIAL_MATCH;
    }

    @Override
    public String toString() {
        return String.format("[%s][%s] Test %s | Cmd: '%s' | Status: %s%s",
                executionMode, isPass() ? "PASS" : "FAIL", testId, command, status,
                failureReason != null ? " (" + failureReason + ")" : "");
    }
}
