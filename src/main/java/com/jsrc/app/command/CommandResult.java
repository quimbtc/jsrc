package com.jsrc.app.command;

/**
 * Result of a command execution.
 *
 * @param resultCount number of results found
 * @param output      captured stdout output (for batch/watch)
 */
public record CommandResult(int resultCount, String output) {
    public static CommandResult of(int count) {
        return new CommandResult(count, null);
    }
}
