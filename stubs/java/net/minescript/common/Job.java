package net.minescript.common;

public abstract class Job {
    protected int jobId;
    protected ScriptConfig.BoundCommand command;

    public void processStderr(String text) {
    }

    public void logJobException(Exception e) {
    }
}
