class Jsrc < Formula
  desc "Java source code navigator and analyzer — CLI for codebase exploration"
  homepage "https://github.com/joadpe/jsrc"
  license "MIT"

  # Native binaries — no Java required
  if OS.mac? && Hardware::CPU.arm?
    url "https://github.com/joadpe/jsrc/releases/download/v2.1.0/jsrc-macos-arm64"
    sha256 "PLACEHOLDER_MACOS_ARM64_SHA256"
  elsif OS.mac? && Hardware::CPU.intel?
    url "https://github.com/joadpe/jsrc/releases/download/v2.1.0/jsrc-macos-x64"
    sha256 "PLACEHOLDER_MACOS_X64_SHA256"
  elsif OS.linux? && Hardware::CPU.intel?
    url "https://github.com/joadpe/jsrc/releases/download/v2.1.0/jsrc-linux-x64"
    sha256 "PLACEHOLDER_LINUX_X64_SHA256"
  end

  # Tree-sitter native libs still needed at runtime
  resource "tree-sitter" do
    url "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v0.25.6.tar.gz"
    sha256 "ac6ed919c6d849e8553e246d5cd3fa22661f6c7b6497299264af433f3629957c"
  end

  resource "tree-sitter-java" do
    url "https://github.com/tree-sitter/tree-sitter-java/archive/refs/tags/v0.23.5.tar.gz"
    sha256 "cb199e0faae4b2c08425f88cbb51c1a9319612e7b96315a174a624db9bf3d9f0"
  end

  def install
    # Build tree-sitter native lib
    resource("tree-sitter").stage do
      system "make", "-j#{ENV.make_jobs}"
      if OS.mac?
        lib.install "libtree-sitter.dylib"
        system "install_name_tool", "-id", "#{lib}/libtree-sitter.dylib",
               "#{lib}/libtree-sitter.dylib"
      else
        lib.install "libtree-sitter.so"
      end
      (buildpath/"ts-include").install Dir["lib/include/*"]
    end

    # Build tree-sitter-java native lib
    resource("tree-sitter-java").stage do
      if OS.mac?
        system ENV.cc, "-dynamiclib", "-fPIC",
               "-I#{buildpath}/ts-include",
               "-L#{lib}", "-ltree-sitter",
               "-install_name", "#{lib}/libtree-sitter-java.dylib",
               "-o", "libtree-sitter-java.dylib", "src/parser.c"
        lib.install "libtree-sitter-java.dylib"
        ["libtree-sitter.0.25.dylib", "libtree-sitter.0.24.dylib",
         "libtree-sitter.dylib"].each do |old_name|
          system "install_name_tool",
                 "-change", "/usr/local/lib/#{old_name}",
                 "#{lib}/libtree-sitter.dylib",
                 "#{lib}/libtree-sitter-java.dylib"
        end
      else
        system ENV.cc, "-shared", "-fPIC",
               "-I#{buildpath}/ts-include",
               "-L#{lib}", "-ltree-sitter",
               "-o", "libtree-sitter-java.so", "src/parser.c"
        lib.install "libtree-sitter-java.so"
      end
    end

    # Install native binary with wrapper that sets library path
    libexec.install Dir["jsrc-*"].first => "jsrc-native"
    chmod 0755, libexec/"jsrc-native"

    (bin/"jsrc").write <<~EOS
      #!/bin/bash
      # jsrc — Java Source Code Navigator (native binary)
      export LD_LIBRARY_PATH="#{lib}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
      export DYLD_LIBRARY_PATH="#{lib}${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
      exec "#{libexec}/jsrc-native" "$@"
    EOS
  end

  test do
    assert_match "jsrc", shell_output("#{bin}/jsrc --help")
  end
end
