package com.jsrc.app.command;

import com.jsrc.app.architecture.RuleEngine;
import com.jsrc.app.exception.BadUsageException;

public class CheckCommand implements Command {
    private final String ruleId;

    public CheckCommand(String ruleId) {
        this.ruleId = ruleId;
    }

    @Override
    public int execute(CommandContext ctx) {
        if (ctx.config() == null || ctx.config().architecture().rules().isEmpty()) {
            throw new BadUsageException("No architecture rules defined in .jsrc.yaml");
        }
        var allClasses = ctx.getAllClasses();
        var engine = new RuleEngine(ctx.config().architecture());
        var violations = ruleId != null
                ? engine.evaluateRule(ruleId, allClasses, ctx.javaFiles())
                : engine.evaluate(allClasses, ctx.javaFiles());
        ctx.formatter().printViolations(violations);
        return violations.size();
    }
}
