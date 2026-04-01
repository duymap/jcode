# jcode Improvement Plan

Based on analysis of Claude Code source (for-learn-claude-cli) applied to jcode's Java/local-first architecture.

## Current State

- Java 21 + picocli + JLine REPL
- 8 tools (read, write, edit, bash, grep, find, web_search, web_fetch)
- Basic agent loop with streaming SSE
- Simple planning extension (reasoning model)
- Basic context trimming (oldest-first)
- Config via `~/.jcode/config.json`
- 3 slash commands (/help, /clear, /exit)

---

## Proposed Features (Prioritized)

### Tier 1 — High Impact, Moderate Effort

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 1 | **Persistent Memory System** | `~/.jcode/memory/` with markdown files. Remember user prefs, project context, feedback across sessions. | Medium |
| 2 | **Concurrent Tool Execution** | Run read-only tools in parallel, write tools sequentially. Java virtual threads make this natural. | Medium |
| 3 | **Sub-agent / Forked Agent** | Spawn secondary LLM calls for subtasks without polluting main conversation. | Medium-High |
| 4 | **Context Compaction** | Summarize conversation instead of just trimming. Preserves important context while freeing tokens. | Medium |
| 5 | **Permission System** | Granular per-tool permissions: default (ask), readonly, auto (approve safe ops). | Medium |

### Tier 2 — Good Value, Lower Effort

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 6 | **More Slash Commands** | `/commit`, `/diff`, `/cost`, `/compact`, `/status`, `/model` | Low-Medium |
| 7 | **JCODE.md Support** | Per-project context file injected into system prompt. | Low |
| 8 | **Layered Error Recovery** | Compact → increase tokens → retry with recovery message. | Medium |
| 9 | **LSP Integration** | Go-to-definition, find-references, diagnostics for richer code intelligence. | High |
| 10 | **Improved Diff Rendering** | Word-level diffs, syntax highlighting, file headers with line ranges. | Low |

### Tier 3 — Nice to Have, Higher Effort

| # | Feature | Why | Effort |
|---|---------|-----|--------|
| 11 | **MCP Support** | External tool servers for community plugins. | High |
| 12 | **Git-aware Context Injection** | Auto-inject git status, recent commits, branch info into system prompt. | Low-Medium |
| 13 | **Session Resume** | Save/restore conversation sessions to disk. | Medium |
| 14 | **Worktree Isolation** | Run experiments in a git worktree. | Medium |
| 15 | **Task/Todo System** | Agent breaks work into tasks, tracks progress as a checklist. | Medium |

---

## Implementation Phases

### Phase 1 — Foundation (most bang for buck)
- [x] **JCODE.md support** (#7) — Read `JCODE.md` from project root, inject into system prompt
- [x] **Git-aware context injection** (#12) — Auto-inject git status, branch, recent commits
- [x] **More slash commands** (#6) — `/compact`, `/commit`, `/diff`, `/model`, `/cost`
- [x] **Concurrent tool execution** (#2) — Parallel read-only tools via Java virtual threads

### Phase 2 — Intelligence
- [ ] **Context compaction** (#4) — `/compact` + auto-compact when near limit
- [ ] **Persistent memory system** (#1) — `~/.jcode/memory/` with markdown files
- [ ] **Permission system** (#5) — Granular tool permissions

### Phase 3 — Advanced
- [ ] **Sub-agent support** (#3) — Spawn secondary LLM calls
- [ ] **Layered error recovery** (#8)
- [ ] **Session resume** (#13)

### Phase 4 — Ecosystem
- [ ] **LSP integration** (#9)
- [ ] **MCP support** (#11)
- [ ] **Task system** (#15)
