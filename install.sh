#!/bin/bash
set -e

# jsrc installer — works on Linux and macOS
# Tries native binary first (fast, no Java needed), falls back to JAR build.
# Usage: ./install.sh [install-dir]
#        ./install.sh --jar           # force JAR mode (skip native binary)

INSTALL_DIR="${1:-$HOME/.jsrc}"
BIN_DIR="$HOME/bin"
LIB_DIR="$HOME/lib"
MIN_JAVA_VERSION=22
GITHUB_REPO="joadpe/jsrc"
FORCE_JAR=false

if [[ "$1" == "--jar" ]]; then
    FORCE_JAR=true
    INSTALL_DIR="$HOME/.jsrc"
fi

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

# ---- Map to binary name ----
get_native_binary_name() {
    if [[ "$OS" == "Linux" && "$ARCH" == "x86_64" ]]; then
        echo "jsrc-linux-x64"
    elif [[ "$OS" == "Darwin" && "$ARCH" == "arm64" ]]; then
        echo "jsrc-macos-arm64"
    elif [[ "$OS" == "Darwin" && "$ARCH" == "x86_64" ]]; then
        echo "jsrc-macos-x64"
    else
        echo ""
    fi
}

# ---- Native library extension ----
if [[ "$OS" == "Darwin" ]]; then
    LIB_EXT="dylib"
    SHARED_FLAG="-dynamiclib"
else
    LIB_EXT="so"
    SHARED_FLAG="-shared"
fi

# ---- Compile native Tree-sitter libraries ----
ensure_native_libs() {
    mkdir -p "$LIB_DIR"

    if [[ -f "$LIB_DIR/libtree-sitter.$LIB_EXT" ]]; then
        echo "✓ libtree-sitter.$LIB_EXT already exists"
    else
        # Check build tools
        if ! command -v gcc &>/dev/null && ! command -v cc &>/dev/null; then
            echo "Error: C compiler required to build Tree-sitter (gcc or cc)"
            if [[ "$OS" == "Darwin" ]]; then
                echo "Run: xcode-select --install"
            else
                echo "Run: sudo apt install build-essential"
            fi
            exit 1
        fi

        echo "Building libtree-sitter..."
        local TMPDIR
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
            local TMPDIR
            TMPDIR=$(mktemp -d)
            git clone --depth 1 https://github.com/tree-sitter/tree-sitter.git "$TMPDIR/tree-sitter" 2>/dev/null
            TS_INCLUDE="$TMPDIR/tree-sitter/lib/include"
        fi
        local TS_JAVA_DIR
        TS_JAVA_DIR=$(mktemp -d)
        git clone --depth 1 https://github.com/tree-sitter/tree-sitter-java.git "$TS_JAVA_DIR/tree-sitter-java" 2>/dev/null
        cd "$TS_JAVA_DIR/tree-sitter-java"
        gcc $SHARED_FLAG -fPIC -I"$TS_INCLUDE" -o "libtree-sitter-java.$LIB_EXT" src/parser.c
        cp "libtree-sitter-java.$LIB_EXT" "$LIB_DIR/"
        echo "✓ libtree-sitter-java built"
    fi
}

# ---- Try native binary installation ----
try_native_install() {
    local BINARY_NAME
    BINARY_NAME=$(get_native_binary_name)

    if [[ -z "$BINARY_NAME" ]]; then
        echo "No native binary available for $OS $ARCH"
        return 1
    fi

    echo ""
    echo "Checking for pre-built native binary ($BINARY_NAME)..."

    # Get latest release URL
    local RELEASE_URL
    RELEASE_URL=$(curl -sfL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" \
        | grep "browser_download_url.*$BINARY_NAME" \
        | head -1 \
        | cut -d '"' -f 4) || true

    if [[ -z "$RELEASE_URL" ]]; then
        echo "No native binary found in latest release"
        return 1
    fi

    echo "Downloading $BINARY_NAME..."
    mkdir -p "$BIN_DIR"
    if curl -sfL "$RELEASE_URL" -o "$BIN_DIR/jsrc"; then
        chmod +x "$BIN_DIR/jsrc"
        echo "✓ Native binary downloaded"
    else
        echo "Download failed"
        return 1
    fi

    # Build native Tree-sitter libs (still needed at runtime)
    ensure_native_libs

    # Verify
    echo ""
    echo "Verifying native binary..."
    if LD_LIBRARY_PATH="$LIB_DIR" DYLD_LIBRARY_PATH="$LIB_DIR" "$BIN_DIR/jsrc" --describe --json &>/dev/null; then
        echo "✓ jsrc native binary is working!"
        return 0
    else
        echo "⚠ Native binary verification failed, falling back to JAR build"
        rm -f "$BIN_DIR/jsrc"
        return 1
    fi
}

# ---- JAR installation (fallback) ----
install_jar() {
    echo ""
    echo "Installing jsrc via JAR build..."

    # ---- Check/install Java ----
    check_java() {
        local java_bin=""
        if command -v java &>/dev/null; then
            java_bin="java"
        elif [[ -x "$HOME/.sdkman/candidates/java/current/bin/java" ]]; then
            java_bin="$HOME/.sdkman/candidates/java/current/bin/java"
            export PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH"
        elif [[ -x "$HOME/.sdkman/candidates/java/current/Contents/Home/bin/java" ]]; then
            java_bin="$HOME/.sdkman/candidates/java/current/Contents/Home/bin/java"
            export PATH="$HOME/.sdkman/candidates/java/current/Contents/Home/bin:$PATH"
        fi
        if [[ -n "$java_bin" ]]; then
            JAVA_VER=$($java_bin -version 2>&1 | head -1 | awk -F'"' '{print $2}' | cut -d. -f1)
            if [[ "$JAVA_VER" -ge "$MIN_JAVA_VERSION" ]]; then
                echo "✓ Java $JAVA_VER found"
                return 0
            fi
        fi
        return 1
    }

    sdk_cmd() {
        if [[ "$OS" == "Darwin" && "${BASH_VERSINFO[0]}" -lt 4 ]]; then
            zsh -c "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && sdk $*" || true
        else
            source "$HOME/.sdkman/bin/sdkman-init.sh" 2>/dev/null || true
            sdk "$@" || true
        fi
    }

    ensure_sdkman() {
        if [[ ! -f "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
            echo "Installing SDKMAN..."
            curl -s "https://get.sdkman.io" | bash
        fi
    }

    install_java() {
        echo "Java $MIN_JAVA_VERSION+ not found. Installing via SDKMAN..."
        ensure_sdkman
        sdk_cmd install java 22.0.2-tem -y
        sdk_cmd default java 22.0.2-tem

        for candidate in "$HOME/.sdkman/candidates/java/22.0.2-tem" "$HOME/.sdkman/candidates/java/current"; do
            if [[ -x "$candidate/bin/java" ]]; then
                export JAVA_HOME="$candidate"
                export PATH="$candidate/bin:$PATH"
                break
            elif [[ -x "$candidate/Contents/Home/bin/java" ]]; then
                export JAVA_HOME="$candidate/Contents/Home"
                export PATH="$candidate/Contents/Home/bin:$PATH"
                break
            fi
        done

        if check_java; then
            return 0
        fi
        echo "Error: Failed to install Java $MIN_JAVA_VERSION"
        echo "  Try manually: source ~/.sdkman/bin/sdkman-init.sh && sdk install java 22.0.2-tem"
        exit 1
    }

    if ! check_java; then
        install_java
    fi

    # ---- Check/install Maven ----
    if command -v mvn &>/dev/null; then
        echo "✓ Maven found: $(mvn --version 2>&1 | head -1)"
    else
        echo "Maven not found. Installing via SDKMAN..."
        ensure_sdkman
        sdk_cmd install maven -y
        export PATH="$HOME/.sdkman/candidates/maven/current/bin:$PATH"
        if ! command -v mvn &>/dev/null; then
            echo "Error: Maven installation failed"
            exit 1
        fi
        echo "✓ Maven installed"
    fi

    # ---- Check build tools ----
    if ! command -v git &>/dev/null; then
        echo "Error: git required"
        exit 1
    fi
    echo "✓ Git found"

    # ---- Build native libs ----
    ensure_native_libs

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
            git clone https://github.com/$GITHUB_REPO.git "$INSTALL_DIR" 2>/dev/null
        fi
        cd "$INSTALL_DIR"
    fi

    echo "Building jsrc..."
    for tool in java maven; do
        tool_bin="$HOME/.sdkman/candidates/$tool/current/bin"
        [[ -d "$tool_bin" ]] && export PATH="$tool_bin:$PATH"
        tool_home="$HOME/.sdkman/candidates/$tool/current/Contents/Home/bin"
        [[ -d "$tool_home" ]] && export PATH="$tool_home:$PATH"
    done
    mvn package -q -DskipTests

    if [[ ! -f "$INSTALL_DIR/target/jsrc.jar" ]]; then
        echo "Error: Build failed — jsrc.jar not found"
        exit 1
    fi

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
# jsrc — Java Source Code Navigator (JAR mode)

# Find java
for tool_bin in "\$HOME/.sdkman/candidates/java/current/bin" "\$HOME/.sdkman/candidates/java/current/Contents/Home/bin"; do
    [ -d "\$tool_bin" ] && export PATH="\$tool_bin:\$PATH" && break
done

exec java --enable-native-access=ALL-UNNAMED \\
    -Djava.library.path="$LIB_DIR" \\
    -jar "$INSTALL_DIR/target/jsrc.jar" "\$@"
WRAPPER
    chmod +x "$BIN_DIR/jsrc"
}

# ---- Main ----

if [[ "$FORCE_JAR" == "true" ]]; then
    echo "Forced JAR mode"
    install_jar
elif try_native_install; then
    echo ""
    echo "=== Installed (native binary) ==="
else
    install_jar
fi

# ---- Final output ----
echo ""
echo "=== Installation complete ==="
echo ""
echo "  CLI:          $BIN_DIR/jsrc"
echo "  Native libs:  $LIB_DIR"
echo ""

# Check if ~/bin is in PATH
if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    echo "Add to your shell profile (~/.zshrc or ~/.bashrc):"
    echo ""
    echo "  export PATH=\"\$HOME/bin:\$PATH\""
    echo ""
fi

# Verify
echo "Verifying..."
if "$BIN_DIR/jsrc" --describe --json &>/dev/null; then
    echo "✓ jsrc is working!"
else
    echo "⚠ jsrc installed but verification failed."
    echo "  Try: jsrc --describe --json"
fi

echo ""
echo "Quick start:"
echo ""
echo "  jsrc --describe --json          # list all commands"
echo "  cd /path/to/java/project"
echo "  jsrc --index                    # index the codebase (one-time)"
echo "  jsrc --overview --json          # explore"
echo ""
