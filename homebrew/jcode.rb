class Jcode < Formula
  desc "A lightweight, terminal-based AI coding agent powered by LLMs"
  homepage "https://github.com/keytechx/jcode"
  url "https://github.com/keytechx/jcode/releases/download/v0.2.0/jcode-0.2.0-dist.tar.gz"
  sha256 "PLACEHOLDER_SHA256"
  license "MIT"

  depends_on "openjdk@21"

  def install
    libexec.install Dir["lib/*"]
    (bin/"jcode").write_env_script libexec/"bin/jcode",
      JAVA_HOME: Formula["openjdk@21"].opt_prefix
  end

  test do
    assert_match "jcode", shell_output("#{bin}/jcode --help", 0)
  end
end
