package com.jsrc.app.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jsrc.app.parser.model.CallChain;

/**
 * Parameters for printing call chain results.
 * Replaces the 3-overload cascade on OutputFormatter.
 *
 * @param chains       discovered call chains
 * @param methodName   target method name
 * @param signatures   method signature map for enriched display (empty if unavailable)
 * @param deadEndRoots root methods that appear to be dead code (empty if unavailable)
 */
public record CallChainOutput(
        List<CallChain> chains,
        String methodName,
        Map<String, String> signatures,
        Set<String> deadEndRoots
) {
    public CallChainOutput(List<CallChain> chains, String methodName) {
        this(chains, methodName, Map.of(), Set.of());
    }
}
