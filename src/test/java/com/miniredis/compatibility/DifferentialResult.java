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

    private final String testId;
    private final String command;
    private final Status status;
    private final String referenceOutput;
    private final String miniredisOutput;
    private final String failureReason;

    public DifferentialResult(String testId, String command, Status status,
                              String referenceOutput, String miniredisOutput, String failureReason) {
        this.testId = testId;
        this.command = command;
        this.status = status;
        this.referenceOutput = referenceOutput;
        this.miniredisOutput = miniredisOutput;
        this.failureReason = failureReason;
    }

    public static DifferentialResult match(String testId, String command, String output) {
        return new DifferentialResult(testId, command, Status.MATCH, output, output, null);
    }

    public static DifferentialResult mismatch(String testId, String command,
                                              String referenceOutput, String miniredisOutput, String reason) {
        return new DifferentialResult(testId, command, Status.MISMATCH, referenceOutput, miniredisOutput, reason);
    }

    public static DifferentialResult partialMatch(String testId, String command,
                                                  String referenceOutput, String miniredisOutput, String reason) {
        return new DifferentialResult(testId, command, Status.PARTIAL_MATCH, referenceOutput, miniredisOutput, reason);
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
        return String.format("[%s] Test %s | Cmd: '%s' | Status: %s%s",
                isPass() ? "PASS" : "FAIL", testId, command, status,
                failureReason != null ? " (" + failureReason + ")" : "");
    }
}
