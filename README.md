# jcode

```
  ██    ████    ████      ██   ████
  ██   ██      ██  ██     ██  ██  ██
  ██   ██      ██  ██  █████  ████
██  ██   ██      ██  ██  ██  ██ ██
 ████     ████    ████    █████   ████
```

A lightweight, terminal-based AI coding agent powered by local LLMs.

jcode connects to local model providers like **LM Studio** and **Ollama** to give you an interactive coding assistant right in your terminal — with full filesystem access, bash execution, and automatic planning.

## Features

- **Interactive REPL** — chat with an AI coding agent in your terminal
- **Tool use** — the agent can read, write, edit files, run bash commands, grep, and find files
- **Automatic planning** — generates an execution plan before complex tasks using a separate reasoning model
- **Readonly mode** — restrict the agent to read-only operations
- **One-shot mode** — pipe a prompt in and get a response back, no REPL
- **Local-first** — works with LM Studio and Ollama, no cloud API keys required
- **Homebrew installable** — distribute via a custom tap

## Architecture

```
com.jcode
├── JCodeCli              CLI entry point (picocli)
├── AgentSession           Core agent loop: LLM ↔ tool execution
├── Config                 Loads/saves ~/.jcode/config.json
├── ModelResolver          Auto-discovers models from provider endpoints
├── SetupWizard            Interactive first-run configuration
│
├── model/
│   ├── JCodeConfig        Top-level config structure
│   ├── Model              Resolved model (id, provider, context window)
│   ├── ModelConfig         Persisted model entry (id, type, provider, url)
│   └── ReasoningModelConfig
│
├── tools/
│   ├── Tool               Interface: name, schema, execute
│   ├── ReadFileTool        Read file contents
│   ├── WriteFileTool       Overwrite a file
│   ├── EditFileTool        Find-and-replace within a file
│   ├── BashTool            Execute shell commands
│   ├── GrepTool            Regex search across files
│   └── FindFilesTool       Glob-based file discovery
│
├── tui/
│   └── AppRunner          Terminal UI: banner, line reader, streaming output
│
└── extensions/
    └── PlanningExtension  Generates execution plans via reasoning model
```

### How the agent loop works

```
User prompt
  │
  ├─▶ [PlanningExtension] generate plan (optional, via reasoning model)
  │
  ▼
Agent loop
  ├─▶ Send conversation history to LLM (streaming SSE)
  ├─▶ Parse response: text content + tool_calls
  ├─▶ If tool_calls present:
  │     ├─ Execute each tool
  │     ├─ Append tool results to history
  │     └─ Loop back ↑
  └─▶ If no tool_calls: return final response
```

## Requirements

- **Java 21+**
- **Maven 3.8+**
- A running local LLM provider:
  - [LM Studio](https://lmstudio.ai/) (default: `http://127.0.0.1:1234/v1`)
  - [Ollama](https://ollama.com/) (default: `http://127.0.0.1:11434/v1`)

## Getting Started

### Build from source

```bash
git clone https://github.com/duymap/jcode.git
cd jcode
mvn clean package -DskipTests
```

### Run

```bash
# Interactive mode
mvn exec:java

# Or run the packaged JAR
java -jar target/jcode-0.2.0.jar
```

### First-run setup

```bash
jcode setup
```

The setup wizard walks you through:

1. Choosing a provider (LM Studio / Ollama)
2. Configuring the API endpoint
3. Selecting a long-context model (primary)
4. Optionally selecting a reasoning model (for planning)

Configuration is saved to `~/.jcode/config.json`.

## Usage

```bash
# Interactive REPL
jcode

# One-shot mode
jcode -p "write a Python hello world"

# Readonly mode (no file writes or bash)
jcode --readonly

# Override model and provider
jcode -m "deepseek-coder" -P ollama -u "http://localhost:11434/v1"
```

### CLI Options

| Option | Description |
|--------|-------------|
| `-m, --model` | Model name/ID (overrides config) |
| `-P, --provider` | LLM provider (`lm-studio`, `ollama`) |
| `-u, --url` | API endpoint URL |
| `-r, --readonly` | Disable write and bash tools |
| `-p, --print` | One-shot mode: print response and exit |
| `--no-planning` | Skip automatic planning step |

### REPL Commands

| Command | Description |
|---------|-------------|
| `/help` | Show available commands |
| `/clear` | Clear conversation history |
| `/exit`, `/quit` | Exit the session |

## Tools

The agent has access to the following tools during a session:

| Tool | Description | Available in readonly? |
|------|-------------|----------------------|
| `read` | Read file contents with optional offset/limit | Yes |
| `write` | Create or overwrite a file | No |
| `edit` | Find-and-replace text in a file | No |
| `bash` | Execute a shell command (120s default timeout) | No |
| `grep` | Regex search across files | Yes |
| `find` | Find files by glob pattern | Yes |

## Install via Homebrew

```bash
brew tap duymap/jcode
brew install jcode
```

## License

MIT
