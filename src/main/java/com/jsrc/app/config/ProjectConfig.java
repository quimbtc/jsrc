package com.jsrc.app.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Project configuration loaded from {@code .jsrc.yaml}.
 * Uses SnakeYAML for robust parsing.
 */
public record ProjectConfig(
        List<String> sourceRoots,
        List<String> excludes,
        String javaVersion,
        ArchitectureConfig architecture
) {
    private static final Logger logger = LoggerFactory.getLogger(ProjectConfig.class);
    private static final String CONFIG_FILE = ".jsrc.yaml";

    public static ProjectConfig load(Path directory) {
        Path configFile = directory.resolve(CONFIG_FILE);
        if (!Files.exists(configFile)) return null;
        try {
            return parse(Files.readString(configFile));
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configFile, e.getMessage());
            return null;
        }
    }

    public static ProjectConfig loadFrom(Path configPath) {
        if (!Files.exists(configPath)) {
            logger.warn("Config file not found: {}", configPath);
            return null;
        }
        try {
            return parse(Files.readString(configPath));
        } catch (IOException e) {
            logger.error("Error reading {}: {}", configPath, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ProjectConfig parse(String yamlContent) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlContent);
        if (root == null) root = Map.of();

        List<String> sourceRoots = getStringList(root, "sourceRoots");
        List<String> excludes = getStringList(root, "excludes");
        String javaVersion = getString(root, "javaVersion", "");
        ArchitectureConfig arch = parseArchitecture((Map<String, Object>) root.get("architecture"));

        return new ProjectConfig(sourceRoots, excludes, javaVersion, arch);
    }

    @SuppressWarnings("unchecked")
    private static ArchitectureConfig parseArchitecture(Map<String, Object> archMap) {
        if (archMap == null) return ArchitectureConfig.empty();

        List<ArchitectureConfig.LayerDef> layers = new ArrayList<>();
        List<Map<String, Object>> layersList = (List<Map<String, Object>>) archMap.get("layers");
        if (layersList != null) {
            for (Map<String, Object> lm : layersList) {
                layers.add(new ArchitectureConfig.LayerDef(
                        getString(lm, "name", ""),
                        getString(lm, "pattern", ""),
                        getStringList(lm, "annotations")));
            }
        }

        List<ArchitectureConfig.RuleDef> rules = new ArrayList<>();
        List<Map<String, Object>> rulesList = (List<Map<String, Object>>) archMap.get("rules");
        if (rulesList != null) {
            for (Map<String, Object> rm : rulesList) {
                rules.add(new ArchitectureConfig.RuleDef(
                        getString(rm, "id", ""),
                        getString(rm, "description", ""),
                        getString(rm, "from", ""),
                        getString(rm, "layer", ""),
                        getString(rm, "denyImport", ""),
                        getString(rm, "require", ""),
                        getString(rm, "denyAnnotation", "")));
            }
        }

        List<String> endpoints = getStringList(archMap, "endpoints");

        List<ArchitectureConfig.InvokerDef> invokers = new ArrayList<>();
        List<Map<String, Object>> invokersList = (List<Map<String, Object>>) archMap.get("invokers");
        if (invokersList != null) {
            for (Map<String, Object> im : invokersList) {
                List<String> suffixes = getStringList(im, "callerSuffixes");
                if (suffixes.isEmpty()) {
                    invokers.add(new ArchitectureConfig.InvokerDef(
                            getString(im, "method", ""),
                            getInt(im, "targetArg", 0),
                            getString(im, "resolveClass", "")));
                } else {
                    invokers.add(new ArchitectureConfig.InvokerDef(
                            getString(im, "method", ""),
                            getInt(im, "targetArg", 0),
                            getString(im, "resolveClass", ""),
                            suffixes));
                }
            }
        }

        List<String> chainStopMethods = getStringList(archMap, "chainStopMethods");

        return new ArchitectureConfig(
                List.copyOf(layers), List.copyOf(rules),
                List.copyOf(endpoints), List.copyOf(invokers),
                List.copyOf(chainStopMethods));
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .toList();
        }
        return List.of();
    }

    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }
}
