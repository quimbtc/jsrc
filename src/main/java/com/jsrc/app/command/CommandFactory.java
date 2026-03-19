package com.jsrc.app.command;

/**
 * Single source of truth for command resolution.
 * Used by App.java, BatchCommand, and WatchCommand.
 */
public final class CommandFactory {

    private CommandFactory() {}

    /**
     * Resolves a command by name with an optional argument.
     *
     * @param command command name (e.g. "--overview", "--summary")
     * @param arg     argument (class name, method name, pattern). Null if none.
     * @param mdOutput true if --md flag is set
     * @return Command instance, or null if unknown
     */
    public static Command create(String command, String arg, boolean mdOutput) {
        return switch (command) {
            case "--index" -> new IndexCommand();
            case "--overview" -> new OverviewCommand();
            case "--classes" -> new ClassesCommand();
            case "--smells" -> new SmellsCommand(arg);
            case "--summary" -> arg != null ? new SummaryCommand(arg) : null;
            case "--hierarchy" -> arg != null ? new HierarchyCommand(arg) : null;
            case "--implements" -> arg != null ? new ImplementsCommand(arg) : null;
            case "--deps" -> arg != null ? new DepsCommand(arg) : null;
            case "--annotations" -> arg != null ? new AnnotationsCommand(arg) : null;
            case "--callers" -> arg != null ? new CallersCommand(arg) : null;
            case "--callees" -> arg != null ? new CalleesCommand(arg) : null;
            case "--read" -> arg != null ? new ReadCommand(arg) : null;
            case "--call-chain" -> arg != null ? new CallChainCommand(arg, "./call-chains") : null;
            case "--context" -> arg != null ? new ContextCommand(arg, mdOutput) : null;
            case "--contract" -> arg != null ? new ContractCommand(arg) : null;
            case "--verify" -> null; // special handling needed for --spec
            case "--layer" -> arg != null ? new LayerCommand(arg) : null;
            case "--check" -> new CheckCommand(arg); // arg can be null (all rules)
            case "--endpoints" -> new EndpointsCommand();
            case "--diff" -> new DiffCommand();
            case "--changed" -> new ChangedCommand();
            case "--drift" -> new DriftCommand();
            case "--search" -> arg != null ? new SearchCommand(arg) : null;
            case "--imports" -> arg != null ? new ImportsCommand(arg) : null;
            case "--packages" -> new PackagesCommand();
            case "--explain" -> arg != null ? new ExplainCommand(arg) : null;
            case "--batch" -> new BatchCommand();
            case "--unused" -> new UnusedCommand();
            case "--similar" -> arg != null ? new SimilarCommand(arg) : null;
            case "--watch" -> new WatchCommand();
            case "--stats" -> arg != null ? new MetricsCommand(arg) : null;
            case "--history" -> arg != null ? new HistoryCommand(arg) : null;
            case "--validate" -> arg != null ? new ValidateCommand(arg) : null;
            case "--mini" -> arg != null ? new MiniCommand(arg) : null;
            case "--related" -> arg != null ? new RelatedCommand(arg) : null;
            case "--impact" -> arg != null ? new ImpactCommand(arg) : null;
            case "--scope" -> arg != null ? new ScopeCommand(arg) : null;
            case "--checklist" -> arg != null ? new ChecklistCommand(arg, null) : null;
            case "--type-check" -> arg != null ? new TypeCheckCommand(arg) : null;
            case "--style" -> new StyleCommand();
            case "--patterns" -> new PatternsCommand();
            case "--snippet" -> arg != null ? new SnippetCommand(arg) : null;
            default -> null;
        };
    }

    /**
     * Resolves for method search (non-flag commands).
     */
    public static Command createMethodSearch(String methodInput) {
        return new MethodSearchCommand(methodInput);
    }
}
