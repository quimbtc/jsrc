package com.jsrc.app.command;
public class TestForCommand implements Command {
    private final String arg;
    public TestForCommand(String arg) { this.arg = arg; }
    @Override public int execute(CommandContext ctx) {
        ctx.formatter().printResult(java.util.Map.of("error", "Not yet implemented"));
        return 0;
    }
}
