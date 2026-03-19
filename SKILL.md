# jsrc — Java Source Code Navigator for Agents

## What is jsrc?

A CLI tool that lets you navigate and inspect large Java codebases without reading source files. It parses code structure (classes, methods, annotations, inheritance, dependencies) and returns compact JSON optimized for LLM context windows.

## When to use jsrc

- You need to understand a Java codebase structure without reading every file
- You need to find a specific class, method, or annotation across thousands of files
- You need to trace call chains or understand class hierarchies
- You need to check dependencies or detect code smells

## When NOT to use jsrc

- Don't read `.java` files directly if jsrc can answer your question
- Don't parse jsrc text output — always use `--json`
- Don't skip `--index` on large codebases (>100 files) — without it, full-parse commands take minutes

## Required setup

```bash
# Java 21+ and Maven required
java -version
mvn --version

# Build jsrc
cd /path/to/jsrc
mvn clean compile
```

## Critical workflow

### Step 1: Index the codebase (do this FIRST)

```bash
jsrc /path/to/codebase --index
```

This parses all Java files and saves a persistent index to `.jsrc/index.json`. Only needed once — subsequent runs auto-refresh changed files.

- First run on 8,000 files: ~14 minutes
- Incremental (after changes): <2 seconds
- All query commands use the index automatically

### Step 2: Orient yourself

```bash
jsrc /path/to/codebase --overview --json
```

Returns: total files, classes, interfaces, methods, package list. ~77ms with index.

### Step 3: Query as needed

Always use `--json`. All commands work with or without explicit source root (defaults to `.`).

## Commands reference

### Navigate

```bash
# List all classes/interfaces
jsrc --classes --json

# Class summary (signatures without bodies)
jsrc --summary ClassName --json

# Class hierarchy (extends, implements, subclasses)
jsrc --hierarchy ClassName --json

# Find implementors of an interface
jsrc --implements InterfaceName --json

# Class dependencies (imports, fields, constructor params)
jsrc --deps ClassName --json

# Find annotated elements (methods + classes)
jsrc --annotations AnnotationName --json
```

### Read source

```bash
# Read a class source
jsrc --read ClassName --json

# Read a specific method source
jsrc --read ClassName.methodName --json
```

### Search

```bash
# Find methods by name
jsrc methodName --json

# Find methods — signatures only (compact, saves tokens)
jsrc methodName --json --signature-only
```

### Call graph

```bash
# Who calls this method?
jsrc --callers methodName --json

# What does this method call?
jsrc --callees methodName --json

# Full call chain trace (generates Mermaid diagrams)
jsrc --call-chain methodName --json
```

### Analyze

```bash
# Detect code smells
jsrc --smells --json

# What changed since last index?
jsrc --diff --json
```

### Introspect

```bash
# List all available commands
jsrc --describe --json

# Detail of a specific command
jsrc --describe --summary --json
```

### Index

```bash
# Build/refresh index
jsrc --index

# Performance metrics for any command
jsrc --overview --json --metrics
```

## Global flags

- `--json` — machine-readable JSON output (always use this)
- `--metrics` — append execution metrics to stderr
- `--signature-only` — compact method output (1 line per method)
- `--fields name,packageName` — limit JSON to specific fields (saves tokens)
- `--config path` — use custom config file instead of `.jsrc.yaml`

## Exit codes

- `0` — OK, results found
- `1` — OK, but no results matched
- `2` — Bad arguments (invalid input, unknown command)
- `3` — I/O error

## Invariants

1. **Always use `--json`** — text output is for humans, not agents
2. **Run `--index` first** on any new codebase — without it, navigation commands parse on-the-fly (slow)
3. **Index auto-refreshes** — if files changed since indexing, jsrc re-parses only those files automatically
4. **stdout = data, stderr = diagnostics** — parse stdout only
5. **`--signature-only`** saves tokens — use it when you don't need full method metadata
6. **`--metrics`** reports timing — use it to verify index is working (should be <1s)

## Output format (JSON)

All JSON output is compact (no pretty-print) to minimize tokens.

### --overview
```json
{"totalFiles":8323,"totalClasses":13335,"totalInterfaces":163,"totalMethods":12680,"totalPackages":124,"packages":["com.app","com.app.service"]}
```

### --classes
```json
[{"name":"OrderService","packageName":"com.app","qualifiedName":"com.app.OrderService","startLine":10,"endLine":50,"isInterface":false,"isAbstract":false,"methodCount":5}]
```

### --summary ClassName
```json
{"name":"OrderService","packageName":"com.app","qualifiedName":"com.app.OrderService","file":"src/main/java/com/app/OrderService.java","modifiers":["public"],"isInterface":false,"methods":[{"name":"create","signature":"public Order create(String name)","startLine":15,"returnType":"Order"}]}
```

### method search
```json
[{"name":"process","className":"Service","file":"Service.java","startLine":10,"endLine":25,"signature":"public void process(String input)","returnType":"void","modifiers":["public"],"parameters":[{"type":"String","name":"input"}]}]
```

### --metrics (stderr)
```json
{"command":"--overview","elapsedMs":77,"filesScanned":8323,"resultsFound":13335}
```

## Performance (with index)

| Command | 51 files | 1,621 files | 8,323 files |
|---------|----------|-------------|-------------|
| --overview | 1.7s | 41ms | 77ms |
| --classes | 1.5s | 146ms | 227ms |
| --annotations | 1.7s | 304ms | 857ms |
| --summary | 671ms | 39ms | 85ms |
| method search | 646ms | — | — |

Without index, full-parse commands on 8,323 files take 12+ minutes.

## Playbooks — What command to use when

### Decision tree

```
What do you need to do?
│
├─ FIX A BUG (have stacktrace/error)
│  1. jsrc --read Class.method --json     ← read the failing method
│  2. jsrc --mini Class --json            ← understand the class (compact)
│  3. jsrc --impact Class.method --json   ← who else is affected?
│  4. jsrc --validate Class.fix --json    ← verify fix before writing
│
├─ ADD/EXTEND A FEATURE
│  1. jsrc --scope "keywords" --json      ← find WHERE the feature lives
│  2. jsrc --mini TopMatch --json         ← understand the class (compact)
│  3. jsrc --read Class.existingMethod --json  ← see the PATTERN to follow
│  4. jsrc --related Class --json         ← what else to read?
│  5. jsrc --checklist Class.method --json ← plan the change
│  6. jsrc --validate Class.newMethod --json  ← verify names before writing
│
├─ UNDERSTAND A CODEBASE (new to you)
│  1. jsrc --overview --json              ← how big? how many packages?
│  2. jsrc --classes --json               ← list all types
│  3. jsrc --scope "keyword" --json       ← find area of interest
│  4. jsrc --mini Class --json            ← quick summary of key classes
│  5. jsrc --related Class --json         ← explore neighborhood
│
├─ REVIEW/AUDIT CODE
│  1. jsrc --smells Class --json          ← code smells
│  2. jsrc --deps Class --json            ← dependency analysis
│  3. jsrc --hierarchy Class --json       ← inheritance tree
│  4. jsrc --check --json                 ← architecture rule violations
│
├─ CHANGE A METHOD SIGNATURE
│  1. jsrc --impact Class.method --json   ← how many callers?
│  2. jsrc --callers Class.method --json  ← exact caller list
│  3. jsrc --checklist Class.method --json ← step-by-step plan
│
└─ VERIFY BEFORE WRITING CODE
   1. jsrc --validate Class.method --json ← does it exist?
   2. jsrc --type-check Class.method --json ← return type correct?
```

### Token budget guide (for small models)

| Model size | Budget | Strategy |
|------------|--------|----------|
| 4K tokens  | ~2,800 usable | Use --mini (not --summary), --read method (not class), max 4-5 calls |
| 8K tokens  | ~5,600 usable | Can use --summary for 1-2 classes, --related for context |
| 16K+ tokens | ~11K+ usable | Full flexibility, can --read classes, use --context |

### Rules for small models (≤8K)

1. **NEVER `cat` a Java file** — use `jsrc --read Class.method` for specific methods
2. **NEVER `jsrc --summary`** on large classes — use `jsrc --mini` instead (10× smaller)
3. **NEVER `jsrc --context`** — too expensive. Use --mini + --read method
4. **ALWAYS start with --scope** when you don't know where code is
5. **ALWAYS --validate** method names before generating code
6. **Read methods, not classes** — `--read Class.method` not `--read Class`

## AI Agent Commands (new)

Commands designed specifically for AI agent workflows:

```bash
# Anti-hallucination: verify method exists, suggest closest if not
jsrc --validate Class.method --json
jsrc --validate Class.method(Type1,Type2) --json

# Ultra-compact summary (<500 chars) for small context windows
jsrc --mini ClassName --json

# Related classes ranked by coupling score
jsrc --related ClassName --json

# Change impact: transitive callers + risk level
jsrc --impact Class.method --json

# Task planner: find relevant classes by keywords
jsrc --scope "keyword1 keyword2" --json

# Step-by-step change guide
jsrc --checklist Class.method --json

# Return type verification
jsrc --type-check Class.method --json
```

## Configuration (.jsrc.yaml)

Optional. Place in project root:

```yaml
sourceRoots:
  - src/main/java
  - src/generated/java
excludes:
  - "**/test/**"
  - "**/generated/**"
javaVersion: "21"
```

With config, source root argument is optional — jsrc uses `sourceRoots[0]` or pwd.
