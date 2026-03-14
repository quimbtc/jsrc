package com.jsrc.app.parser.model;

import java.util.List;

/**
 * An ordered sequence of method calls from a root (entry point)
 * down to a target method.
 *
 * @param steps ordered list from root caller to target callee
 */
public record CallChain(List<MethodCall> steps) {

    public CallChain {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("CallChain must have at least one step");
        }
        steps = List.copyOf(steps);
    }

    public MethodReference root() {
        return steps.getFirst().caller();
    }

    public MethodReference target() {
        return steps.getLast().callee();
    }

    /**
     * Human-readable summary: root() -> ... -> target()
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(root().displayName());
        for (MethodCall step : steps) {
            sb.append(" -> ").append(step.callee().displayName());
        }
        return sb.toString();
    }

    public int depth() {
        return steps.size();
    }
}
