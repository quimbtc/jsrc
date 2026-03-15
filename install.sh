#!/bin/bash
set -e

# jsrc installer — works on Linux and macOS
# Usage: ./install.sh [install-dir]

INSTALL_DIR="${1:-$HOME/.jsrc}"
BIN_DIR="$HOME/bin"
LIB_DIR="$HOME/lib"
MIN_JAVA_VERSION=22

echo "=== jsrc installer ==="
echo ""

# ---- Check if already installed ----
if command -v jsrc &>/dev/null; then
    echo "jsrc is already installed: $(which jsrc)"
    echo "Testing current installation..."
    if jsrc --describe --json &>/dev/null; then
        echo "✓ jsrc is working. Use 'jsrc --describe --json' to see commands."
        read -p "Reinstall/update anyway? [y/N] " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            echo "Aborted."
            exit 0
        fi
    else
        echo "⚠ jsrc found but not working. Reinstalling..."
    fi
fi

# ---- Detect OS ----
OS=$(uname -s)
ARCH=$(uname -m)
echo "Platform: $OS $ARCH"

if [[ "$OS" != "Linux" && "$OS" != "Darwin" ]]; then
    echo "Error: Unsupported OS: $OS (requires Linux or macOS)"
    exit 1
fi

# ---- Check/install Java ----
check_java() {
    if command -v java &>/dev/null; then
        JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '(?<=version ")(\d+)' || java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
        if [[ "$JAVA_VER" -ge "$MIN_JAVA_VERSION" ]]; then
            echo "✓ Java $JAVA_VER found"
            return 0
        fi
    fi
    return 1
}

install_java() {
    echo "Java $MIN_JAVA_VERSION+ not found. Installing via SDKMAN..."
    if ! command -v sdk &>/dev/null; then
        if [[ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
            source "$HOME/.sdkman/bin/sdkman-init.sh"
        else
            echo "Installing SDKMAN..."
            curl -s "https://get.sdkman.io" | bash
            source "$HOME/.sdkman/bin/sdkman-init.sh"
        fi
    fi
    sdk install java 22.0.2-tem -y || sdk install java 22.0.2-sem -y
    echo "✓ Java 22 installed"
}

if ! check_java; then
    install_java
fi

# ---- Check/install Maven ----
if command -v mvn &>/dev/null; then
    echo "✓ Maven found: $(mvn --version 2>&1 | head -1)"
else
    echo "Maven not found. Installing via SDKMAN..."
    source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
    sdk install maven -y
    echo "✓ Maven installed"
fi

# ---- Check build tools ----
if ! command -v gcc &>/dev/null && ! command -v cc &>/dev/null; then
    echo "Error: C compiler required (gcc or cc)"
    if [[ "$OS" == "Darwin" ]]; then
        echo "Run: xcode-select --install"
    else
        echo "Run: sudo apt install build-essential"
    fi
    exit 1
fi
echo "✓ C compiler found"

if ! command -v git &>/dev/null; then
    echo "Error: git required"
    exit 1
fi
echo "✓ Git found"

# ---- Native library extension ----
if [[ "$OS" == "Darwin" ]]; then
    LIB_EXT="dylib"
    SHARED_FLAG="-dynamiclib"
else
    LIB_EXT="so"
    SHARED_FLAG="-shared"
fi

# ---- Compile native libraries ----
mkdir -p "$LIB_DIR"

if [[ -f "$LIB_DIR/libtree-sitter.$LIB_EXT" ]]; then
    echo "✓ libtree-sitter.$LIB_EXT already exists"
else
    echo "Building libtree-sitter..."
    TMPDIR=$(mktemp -d)
    git clone --depth 1 https://github.com/tree-sitter/tree-sitter.git "$TMPDIR/tree-sitter" 2>/dev/null
    cd "$TMPDIR/tree-sitter"
    make -j$(nproc 2>/dev/null || sysctl -n hw.ncpu) 2>/dev/null
    cp "libtree-sitter.$LIB_EXT" "$LIB_DIR/" 2>/dev/null || \
        cp libtree-sitter.so "$LIB_DIR/" 2>/dev/null || \
        cp libtree-sitter.dylib "$LIB_DIR/" 2>/dev/null
    TS_INCLUDE="$TMPDIR/tree-sitter/lib/include"
    echo "✓ libtree-sitter built"
fi

if [[ -f "$LIB_DIR/libtree-sitter-java.$LIB_EXT" ]]; then
    echo "✓ libtree-sitter-java.$LIB_EXT already exists"
else
    echo "Building libtree-sitter-java..."
    if [[ -z "$TS_INCLUDE" ]]; then
        TMPDIR=$(mktemp -d)
        git clone --depth 1 https://github.com/tree-sitter/tree-sitter.git "$TMPDIR/tree-sitter" 2>/dev/null
        TS_INCLUDE="$TMPDIR/tree-sitter/lib/include"
    fi
    TS_JAVA_DIR=$(mktemp -d)
    git clone --depth 1 https://github.com/tree-sitter/tree-sitter-java.git "$TS_JAVA_DIR/tree-sitter-java" 2>/dev/null
    cd "$TS_JAVA_DIR/tree-sitter-java"
    gcc $SHARED_FLAG -fPIC -I"$TS_INCLUDE" -o "libtree-sitter-java.$LIB_EXT" src/parser.c
    cp "libtree-sitter-java.$LIB_EXT" "$LIB_DIR/"
    echo "✓ libtree-sitter-java built"
fi

# ---- Install jsrc ----
echo ""
echo "Installing jsrc to $INSTALL_DIR..."

if [[ -d "$INSTALL_DIR/.git" ]]; then
    echo "Updating existing installation..."
    cd "$INSTALL_DIR"
    git pull --rebase 2>/dev/null || true
else
    if [[ -d "$INSTALL_DIR" ]]; then
        echo "Directory exists, using as-is"
    else
        git clone https://github.com/quimbtc/jsrc.git "$INSTALL_DIR" 2>/dev/null
    fi
    cd "$INSTALL_DIR"
fi

echo "Building jsrc..."
source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
mvn clean compile -q

echo "Running tests..."
if mvn test -q 2>/dev/null; then
    echo "✓ All tests passing"
else
    echo "⚠ Some tests failed (jsrc may still work)"
fi

# ---- Create wrapper script ----
mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/jsrc" << WRAPPER
#!/bin/bash
# jsrc — Java Source Code Navigator
# Generated by install.sh

# Ensure Java is available
source "\$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true

cd "$INSTALL_DIR"
exec mvn -q exec:java \\
    -Dexec.args="\$*" \\
    -Djava.library.path="$LIB_DIR" \\
    2>/dev/null
WRAPPER
chmod +x "$BIN_DIR/jsrc"

# ---- Verify ----
echo ""
echo "=== Installation complete ==="
echo ""
echo "  Install dir:  $INSTALL_DIR"
echo "  Native libs:  $LIB_DIR"
echo "  CLI wrapper:  $BIN_DIR/jsrc"
echo ""

# Check if ~/bin is in PATH
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    echo "Add to your shell profile (~/.zshrc or ~/.bashrc):"
    echo ""
    echo "  export PATH=\"\$HOME/bin:\$PATH\""
    echo ""
fi

# Verify installation works
echo "Verifying installation..."
if "$BIN_DIR/jsrc" --describe --json &>/dev/null; then
    echo "✓ jsrc is working!"
else
    echo "⚠ jsrc installed but verification failed."
    echo "  Try: source ~/.sdkman/bin/sdkman-init.sh && jsrc --describe --json"
fi

echo ""
echo "Quick start:"
echo ""
echo "  jsrc --describe --json          # list all commands"
echo "  cd /path/to/java/project"
echo "  jsrc --index                    # index the codebase (one-time)"
echo "  jsrc --overview --json          # explore"
echo ""
