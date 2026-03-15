package com.jsrc.app.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jsrc.app.parser.model.CallChain;
import com.jsrc.app.parser.model.MethodCall;

/**
 * Generates Mermaid sequence diagram files (.mmd) from {@link CallChain} data.
 * Each chain becomes a separate diagram showing the call flow from root to target.
 */
public class MermaidDiagramGenerator {

    private static final Logger logger = LoggerFactory.getLogger(MermaidDiagramGenerator.class);
    private final java.util.Map<String, String> signatures;

    public MermaidDiagramGenerator() {
        this(java.util.Map.of());
    }

    /**
     * @param signatures map of "ClassName.methodName" → "(ParamType1, ParamType2)"
     */
    public MermaidDiagramGenerator(java.util.Map<String, String> signatures) {
        this.signatures = signatures;
    }

    /**
     * Generates a Mermaid sequence diagram string for a single call chain.
     */
    public String generate(CallChain chain) {
        StringBuilder sb = new StringBuilder();

        sb.append("%% ").append(chain.summary()).append("\n");
        sb.append("sequenceDiagram\n");

        // Entry point actor first (leftmost position in diagram)
        MethodCall firstStep = chain.steps().getFirst();
        String rootClass = firstStep.caller().className();
        String rootMethod = firstStep.caller().methodName();
        boolean hasEntry = !"?".equals(rootClass);
        if (hasEntry) {
            sb.append("    actor Entry\n");
        }

        Set<String> participants = collectParticipants(chain);
        for (String participant : participants) {
            sb.append("    participant ").append(participant).append("\n");
        }

        if (hasEntry) {
            sb.append("    Entry->>").append(rootClass).append(": ")
              .append(rootMethod).append(resolveParams(rootClass, rootMethod)).append("\n");
        }

        List<MethodCall> steps = chain.steps();
        for (int i = 0; i < steps.size(); i++) {
            MethodCall step = steps.get(i);
            String from = step.caller().className();
            String to = step.callee().className();
            String method = step.callee().methodName();
            String params = resolveParams(to, method);

            // Skip unresolved "?" participants — bridge the gap
            if ("?".equals(to) && i + 1 < steps.size()) {
                String nextFrom = steps.get(i + 1).caller().className();
                sb.append("    ").append(from).append("->>").append(nextFrom)
                  .append(": ").append(method).append(params).append("\n");
                continue;
            }
            if ("?".equals(from) && i > 0) {
                continue;
            }

            sb.append("    ").append(from).append("->>").append(to)
              .append(": ").append(method).append(params).append("\n");
        }

        return sb.toString();
    }

    /**
     * Writes all chains to individual .mmd files in the given output directory.
     *
     * @param chains    call chains to write
     * @param outputDir directory to create files in (created if absent)
     * @param methodName target method name, used for file naming
     * @return paths of generated files
     */
    public List<Path> writeAll(List<CallChain> chains, Path outputDir, String methodName) throws IOException {
        Files.createDirectories(outputDir);

        List<Path> generated = new ArrayList<>();
        for (int i = 0; i < chains.size(); i++) {
            String fileName = methodName + "_chain_" + (i + 1) + ".mmd";
            Path file = outputDir.resolve(fileName);
            String content = generate(chains.get(i));
            Files.writeString(file, content);
            generated.add(file);
            logger.debug("Written diagram: {}", file);
        }
        return generated;
    }

    /**
     * Resolves parameter types for a method from the signatures map.
     * Returns "()" if no signature found.
     */
    private String resolveParams(String className, String methodName) {
        // Fallback key without param count
        return signatures.getOrDefault(className + "." + methodName, "()");
    }

    private Set<String> collectParticipants(CallChain chain) {
        Set<String> participants = new LinkedHashSet<>();
        for (MethodCall step : chain.steps()) {
            if (!"?".equals(step.caller().className())) {
                participants.add(step.caller().className());
            }
            if (!"?".equals(step.callee().className())) {
                participants.add(step.callee().className());
            }
        }
        return participants;
    }
}
