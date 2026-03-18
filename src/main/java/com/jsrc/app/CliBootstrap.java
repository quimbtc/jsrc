package com.jsrc.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.jsrc.app.config.ProjectConfig;
import com.jsrc.app.exception.BadUsageException;
import com.jsrc.app.output.FieldsFilter;
import com.jsrc.app.util.InputValidator;

/**
 * Parses raw CLI arguments into a structured {@link ParsedArgs} record.
 * Handles flag extraction, config path resolution, and source root detection.
 */
public final class CliBootstrap {

    private CliBootstrap() {}

    /**
     * Parse raw CLI args into a structured result.
     *
     * @param args raw command-line arguments
     * @return parsed arguments, or null if usage error (already printed)
     */
    public static ParsedArgs parse(String[] args) {
        List<String> argList = new ArrayList<>(List.of(args));

        boolean jsonOutput = argList.remove("--json");
        boolean mdOutput = argList.remove("--md");
        boolean signatureOnly = argList.remove("--signature-only");
        boolean showMetrics = argList.remove("--metrics");

        Set<String> fields = extractFields(argList);
        String configPath = extractOption(argList, "--config");

        // --help and --describe are handled before root/command resolution
        if (argList.contains("--help") || argList.isEmpty()) {
            return new ParsedArgs(jsonOutput, mdOutput, signatureOnly, showMetrics,
                    fields, configPath, null, "--help", argList);
        }
        if (argList.contains("--describe")) {
            return new ParsedArgs(jsonOutput, mdOutput, signatureOnly, showMetrics,
                    fields, configPath, null, "--describe", argList);
        }

        // Load config for root resolution
        ProjectConfig config = configPath != null
                ? ProjectConfig.loadFrom(Path.of(configPath))
                : ProjectConfig.load(Path.of("."));

        String rootPath;
        String command;
        if (argList.size() >= 2 && argList.get(0).startsWith("--")) {
            rootPath = resolveRoot(config);
            command = argList.get(0);
        } else if (argList.size() >= 2) {
            rootPath = argList.get(0);
            command = argList.get(1);
        } else if (argList.size() == 1) {
            rootPath = resolveRoot(config);
            command = argList.get(0);
        } else {
            throw new BadUsageException("Usage: jsrc [source-root] <command> [args] [flags]");
        }

        // Validate
        String pathError = InputValidator.validatePath(rootPath, "Source root");
        if (pathError != null) {
            throw new BadUsageException(pathError);
        }
        String cmdError = InputValidator.validateCommand(command);
        if (cmdError != null) {
            throw new BadUsageException(cmdError);
        }

        return new ParsedArgs(jsonOutput, mdOutput, signatureOnly, showMetrics,
                fields, configPath, rootPath, command, argList);
    }

    private static Set<String> extractFields(List<String> argList) {
        int idx = argList.indexOf("--fields");
        if (idx >= 0 && idx + 1 < argList.size()) {
            Set<String> fields = FieldsFilter.parseFields(argList.get(idx + 1));
            argList.remove(idx + 1);
            argList.remove(idx);
            return fields;
        }
        return null;
    }

    private static String extractOption(List<String> argList, String flag) {
        int idx = argList.indexOf(flag);
        if (idx >= 0 && idx + 1 < argList.size()) {
            String value = argList.get(idx + 1);
            argList.remove(idx + 1);
            argList.remove(idx);
            return value;
        }
        return null;
    }

    private static String resolveRoot(ProjectConfig config) {
        if (config != null && !config.sourceRoots().isEmpty()) {
            return config.sourceRoots().getFirst();
        }
        return ".";
    }

    private static void printUsage() {
        System.err.println("Usage: jsrc [source-root] <command> [args] [flags]");
        System.err.println("Run 'jsrc --describe --json' for all commands.");
    }
}
