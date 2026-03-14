package com.jsrc.app;

/**
 * Consistent exit codes for agent consumption.
 * Agents can branch on exit code without parsing output.
 */
public final class ExitCode {

    /** Command succeeded, results found. */
    public static final int OK = 0;

    /** Command succeeded, but no results matched. */
    public static final int NOT_FOUND = 1;

    /** Bad arguments or unknown command. */
    public static final int BAD_USAGE = 2;

    /** I/O or runtime error. */
    public static final int IO_ERROR = 3;

    private ExitCode() {}
}
