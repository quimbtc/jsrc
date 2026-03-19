#!/usr/bin/env bash
# Completeness audit: compares jsrc output against JavaParser ground truth.
# Usage: ./scripts/audit-completeness.sh /path/to/codebase
#
# Requires: jsrc built (mvn package), Java 21+
# Produces: PASS/FAIL with type and method counts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/../target/jsrc.jar"
AUDIT_JAVA="$SCRIPT_DIR/AuditGroundTruth.java"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <source-root>" >&2
    exit 1
fi

SB="$1"

if [ ! -f "$JAR" ]; then
    echo "Error: $JAR not found. Run 'mvn package -DskipTests' first." >&2
    exit 1
fi

echo "============================================"
echo "Completeness Audit: $(find "$SB" -name '*.java' | wc -l) files"
echo "============================================"

# 1. Index with jsrc
echo "[1/3] Indexing with jsrc..."
java -jar "$JAR" "$SB" --index 2>&1 | tail -1

# 2. Get jsrc counts
echo "[2/3] Reading jsrc overview..."
JSRC_OUTPUT=$(java -jar "$JAR" "$SB" --overview --json 2>/dev/null)
JSRC_FILES=$(echo "$JSRC_OUTPUT" | python3 -c "import json,sys; print(json.load(sys.stdin)['totalFiles'])")
JSRC_TYPES=$(echo "$JSRC_OUTPUT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['totalClasses']+d['totalInterfaces'])")
JSRC_METHODS=$(echo "$JSRC_OUTPUT" | python3 -c "import json,sys; print(json.load(sys.stdin)['totalMethods'])")

# 3. Get ground truth with JavaParser
echo "[3/3] Computing ground truth with JavaParser..."

# Write inline Java program
TMPDIR=$(mktemp -d)
cat > "$TMPDIR/Audit.java" << 'JEOF'
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Audit {
    public static void main(String[] args) throws Exception {
        var config = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var jp = new JavaParser(config);
        Path root = Path.of(args[0]);
        int types = 0, methods = 0, fails = 0;
        List<Path> files = Files.walk(root)
            .filter(p -> p.toString().endsWith(".java"))
            .collect(Collectors.toList());
        for (Path file : files) {
            try {
                var result = jp.parse(Files.readString(file));
                if (!result.getResult().isPresent()) { fails++; continue; }
                var cu = result.getResult().get();
                for (var c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    types++; methods += c.getMethods().size() + c.getConstructors().size();
                }
                for (var r : cu.findAll(RecordDeclaration.class)) {
                    types++; methods += r.getMethods().size();
                }
                for (var e : cu.findAll(EnumDeclaration.class)) {
                    types++; methods += e.getMethods().size() + e.getConstructors().size();
                }
            } catch (Exception e) { fails++; }
        }
        System.out.println("GT_FILES=" + files.size());
        System.out.println("GT_TYPES=" + types);
        System.out.println("GT_METHODS=" + methods);
        System.out.println("GT_FAILS=" + fails);
    }
}
JEOF

JP_JAR=$(find ~/.m2 -name "javaparser-core-3.27.0.jar" 2>/dev/null | head -1)
if [ -z "$JP_JAR" ]; then
    echo "Error: javaparser-core-3.27.0.jar not found in ~/.m2" >&2
    exit 1
fi

javac -cp "$JP_JAR" "$TMPDIR/Audit.java" -d "$TMPDIR"
eval $(java -cp "$TMPDIR:$JP_JAR" Audit "$SB")
rm -rf "$TMPDIR"

# 4. Compare
echo ""
echo "============================================"
echo "RESULTS"
echo "============================================"
printf "%-15s | %8s | %8s | %s\n" "Metric" "jsrc" "Ground Truth" "Match"
printf "%-15s-+-%8s-+-%8s-+-%s\n" "---------------" "--------" "--------" "-----"

PASS=true

check() {
    local label=$1 jsrc=$2 gt=$3
    if [ "$jsrc" = "$gt" ]; then
        printf "%-15s | %8s | %8s | ✅\n" "$label" "$jsrc" "$gt"
    else
        printf "%-15s | %8s | %8s | ❌\n" "$label" "$jsrc" "$gt"
        PASS=false
    fi
}

check "Files" "$JSRC_FILES" "$GT_FILES"
check "Types" "$JSRC_TYPES" "$GT_TYPES"
check "Methods" "$JSRC_METHODS" "$GT_METHODS"

echo ""
if [ "$GT_FAILS" -gt 0 ]; then
    echo "⚠️  Ground truth had $GT_FAILS parse failures"
fi

if [ "$PASS" = true ]; then
    echo "✅ PASS — jsrc matches ground truth exactly"
    exit 0
else
    echo "❌ FAIL — counts don't match"
    exit 1
fi
