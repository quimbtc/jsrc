package com.jsrc.app.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.MethodCall;
import com.jsrc.app.parser.model.MethodReference;

/**
 * Traces all call chains from roots to a target method using reverse DFS
 * on the call graph built by {@link CallGraphBuilder}.
 * <p>
 * A "root" is a method that has no callers within the analyzed codebase
 * (e.g. {@code main}, test methods, event handlers).
 */
public class CallChainTracer {

    private static final Logger logger = LoggerFactory.getLogger(CallChainTracer.class);

    private final CallGraphBuilder graph;
    private final int maxDepth;
    private final Set<String> stopMethods;

    public CallChainTracer(CallGraphBuilder graph) {
        this(graph, 20, Set.of());
    }

    public CallChainTracer(CallGraphBuilder graph, int maxDepth, Set<String> stopMethods) {
        if (graph == null) {
            throw new IllegalArgumentException("CallGraphBuilder must not be null");
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        this.graph = graph;
        this.maxDepth = maxDepth;
        this.stopMethods = stopMethods != null ? stopMethods : Set.of();
    }

    /**
     * Finds all call chains ending at methods with the given name.
     * Each chain traces from a root (no callers) down to the target.
     *
     * @param methodName target method name
     * @return all unique call chains, ordered root-to-target
     */
    public List<CallChain> traceToRoots(String methodName) {
        Set<MethodReference> targets = graph.findMethodsByName(methodName);
        if (targets.isEmpty()) {
            logger.warn("No methods named '{}' found in call graph", methodName);
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<CallChain> allChains = new ArrayList<>();
        for (MethodReference target : targets) {
            for (CallChain chain : traceToRoots(target)) {
                if (seen.add(chain.summary())) {
                    allChains.add(chain);
                }
            }
        }

        logger.info("Found {} unique call chain(s) for '{}'", allChains.size(), methodName);
        return allChains;
    }

    /**
     * Finds all call chains ending at the given target method reference.
     */
    public List<CallChain> traceToRoots(MethodReference target) {
        List<CallChain> result = new ArrayList<>();
        LinkedList<MethodCall> currentPath = new LinkedList<>();
        Set<MethodReference> visited = new HashSet<>();
        visited.add(target);
        dfs(target, currentPath, visited, result);
        return result;
    }

    private void dfs(MethodReference current, LinkedList<MethodCall> currentPath,
                     Set<MethodReference> visited, List<CallChain> result) {
        if (currentPath.size() >= maxDepth) {
            logger.debug("Max depth {} reached at {}", maxDepth, current.displayName());
            if (!currentPath.isEmpty()) {
                result.add(new CallChain(new ArrayList<>(currentPath)));
            }
            return;
        }

        // Stop at configured stop methods (e.g. event handlers) — treat as root
        if (!stopMethods.isEmpty() && isStopMethod(current)) {
            if (!currentPath.isEmpty()) {
                result.add(new CallChain(new ArrayList<>(currentPath)));
            }
            return;
        }

        Set<MethodCall> callerCalls = findCallers(current);

        if (callerCalls.isEmpty()) {
            if (!currentPath.isEmpty()) {
                result.add(new CallChain(new ArrayList<>(currentPath)));
            }
            return;
        }

        boolean anyBranchFollowed = false;
        for (MethodCall call : callerCalls) {
            MethodReference caller = call.caller();
            if (visited.contains(caller)) {
                continue;
            }

            anyBranchFollowed = true;
            visited.add(caller);
            currentPath.addFirst(call);
            dfs(caller, currentPath, visited, result);
            currentPath.removeFirst();
            visited.remove(caller);
        }

        if (!anyBranchFollowed && !currentPath.isEmpty()) {
            result.add(new CallChain(new ArrayList<>(currentPath)));
        }
    }

    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString", "equals", "hashCode", "getClass", "notify", "notifyAll", "wait", "clone", "finalize");

    /**
     * Finds all calls where the given method is the callee, including
     * fuzzy matches for interface/implementation boundaries.
     * <p>
     * Skips fuzzy matching for ubiquitous Object methods to prevent
     * combinatorial explosion.
     */
    private Set<MethodCall> findCallers(MethodReference target) {
        Set<MethodCall> direct = graph.getCallersOf(target);
        if (!direct.isEmpty()) {
            return direct;
        }

        if (OBJECT_METHODS.contains(target.methodName())) {
            return Set.of();
        }

        // Fuzzy match: same method name + compatible param count.
        // Only match across classes if target class is unresolved ("?"),
        // otherwise require same class name to avoid mixing callers
        // from unrelated methods with the same name.
        Set<MethodCall> fuzzy = new HashSet<>();
        boolean unresolvedTarget = "?".equals(target.className()) || target.className() == null;
        for (MethodReference registered : graph.getAllCallerIndexKeys()) {
            if (!registered.methodName().equals(target.methodName())) continue;
            if (!matchesParameterCount(registered, target)) continue;
            if (!unresolvedTarget && !registered.className().equals(target.className())) continue;
            fuzzy.addAll(graph.getCallersOf(registered));
        }
        return fuzzy;
    }

    /**
     * Checks if the method matches a configured stop method pattern.
     * Supports: exact ("actionPerformed"), prefix ("getBtn*"), suffix ("*Btn"),
     * and contains ("get*Btn*") patterns using simple glob matching.
     */
    private boolean isStopMethod(MethodReference method) {
        String name = method.methodName();
        for (String stop : stopMethods) {
            if (globMatch(stop, name)) return true;
        }
        return false;
    }

    private static boolean globMatch(String pattern, String text) {
        if (!pattern.contains("*")) return pattern.equals(text);
        String regex = pattern.replace("*", ".*");
        return text.matches(regex);
    }

    private boolean matchesParameterCount(MethodReference a, MethodReference b) {
        if (a.parameterCount() < 0 || b.parameterCount() < 0) return true;
        return a.parameterCount() == b.parameterCount();
    }
}
