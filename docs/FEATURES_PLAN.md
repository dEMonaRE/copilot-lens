# Feature Plan

Source: comparative analysis of [pavanvamsi3/copilot-lens](https://github.com/pavanvamsi3/copilot-lens) (TypeScript / Node + Express + Ink + Chart.js) against our Java CLI.

Constraint baseline:

- JDK 17+ only. No Node, no npm, no `.exe` installs.
- No environment variables set by us. No third-party AI services.
- Output stays in the current terminal; HTML report is single-shot static.
- All features are opt-in via `config.properties` flags.

## Why this document

We evaluated the other project's feature set and split it into four tiers:

- **P0 — new data source**: opens a part of VSCode we currently ignore.
- **P1 — algorithms**: pure heuristics on already-collected data, no extra IO.
- **P2 — subcommands**: user-visible new commands wrapping P0/P1.
- **P3 — visual**: replaces ASCII bars in the HTML report with inline SVG.
- **P4 — small**: minor utilities, do last.

Not every feature from the other project is worth porting. Items that require
Node, a web server, a browser, or an unsupported data source are listed under
"Out of scope" and the reason is stated.

---

## Schema findings on this PC

Verified 2026-08-31 on `C:\Users\rog\AppData\Roaming\Code`:

| Source | Status | Detail |
|---|---|---|
| `User\globalStorage\state.vscdb` | exists, 2.5 MB | 253 keys; `chat.ChatSessionStore.index` is **empty** here |
| `User\workspaceStorage\<wsId>\state.vscdb` | exists per workspace | **40 indexed sessions** across 10 workspaces |
| `User\workspaceStorage\<wsId>\chatSessions\*.json` | 4 files | completed sessions, full `requests[]` with `message.text` and `response[].kind/value` |
| `User\workspaceStorage\<wsId>\chatSessions\*.jsonl` | 36 files | live sessions, JSONL patch format (`kind:0` snapshot, `kind:1` key update, `kind:2` array append) |

Sample test: session titled "hey" (workspace `d5d04...`) has
`requests[0].message.text = "hey"` — exactly what the user typed into Copilot
Chat. This is the data we cannot get from `output_logging*.log`.

Implication: the other project's `JSON.parse`-only approach works on completed
`.json` files but not on live `.jsonl` files. We need a small patch replayer.

---

## P0 — VSCode chat-session reader

Goal: surface the real prompt text, assistant response text, tool calls,
agent id, and per-turn model/latency for each VSCode chat turn. Today we only
see the `fetchCompletions` URL and `ccreq model/latency` line in the log.

### Scope

1. **New JAR**: `lib/sqlite-jdbc-X.Y.Z.jar`. Downloaded by `build.sh` from
   Maven Central (same channel as `jtokkit`). Pure Java, no native deps.
2. **New module `parser/VsCodeSessionDb.java`**: opens every
   `state.vscdb` under `User\workspaceStorage\<wsId>\` in read-only mode,
   reads `ItemTable` key `chat.ChatSessionStore.index`, extracts the
   `entries` map. Skips files that don't have the key.
3. **New module `parser/VsCodeSessionJson.java`**: loads
   `<wsId>\chatSessions\<sessionId>.json` files (the easy format). Streams
   parse, caps at `chatsession.maxBytes`, strips base64 image data and
   truncates message text > 10k chars.
4. **New module `parser/VsCodeSessionJsonl.java`**: replays the patch
   format from `.jsonl` files. Pure state machine:

   ```
   kind:0  full snapshot   → take v as current state
   kind:1  key update      → state[k[0]][k[1]]...[k[n]] = v
   kind:2  array append    → state[k[0]][...][k[n]].append(v)
   ```

5. **Extended `parser/CopilotRequest.java`**: add `promptText`, `responseText`,
   `toolsUsed` (set of normalised tool names), `agent` (e.g. `setup.agent`),
   `sessionId`. Optional fields; log-only parser leaves them null.
6. **Integrate in `Main`**: after the log parser runs, if
   `chatsession.enabled=true`, walk the workspace storage tree and emit
   one enriched `CopilotRequest` per turn. Merge into the same `Report`
   record so existing reporters get richer data for free.

### Config

```properties
# OFF by default. Existing users see no behaviour change.
chatsession.enabled=false
chatsession.maxBytes=209715200   # 200 MB; matches the other project's cap
```

### Risk

- VSCode chat schema is **undocumented by Microsoft** and has changed at
  least once (we have v3 completed + JSONL patches side by side). We pin
  a version probe: read `version` field, fail soft if unknown.
- VSCode keeps the SQLite DB locked while writing. We open with
  `sqlite-jdbc` `open_mode=1` (readonly) — that uses `SQLITE_OPEN_READONLY`
  in C and is safe to open while VSCode is running.
- 200 MB session files are common for heavy users. Same cap as the other
  project, applied before JSON parse.

### Acceptance

- With `chatsession.enabled=false`: existing tests, snapshot, trend, HTML,
  watch all behave identically.
- With `chatsession.enabled=true`: `copilot-lens gain` shows the user's
  "hey" prompt in the Top 10 list (not just the URL).
- `cache.json` schema gets a new optional field for `promptText`; old
  caches still load.

### Effort

Roughly one day. Heaviest part is the JSONL patch replayer.

---

## P1 — Pure-algorithm features

All four are portable from the other project's `sessions.ts`. They operate
on data we already collect (or that P0 will start collecting).

### P1.1 Effectiveness Score

Port `scorePromptQuality`, `scoreToolUtilization`, `scoreEfficiency`,
`scoreMcpUtilization`, `scoreEngagement` from `sessions.ts:624-738`.

Scoring (5 × 20 = 100 max):

| Category | What it measures | Heuristic |
|---|---|---|
| Prompt Quality (20) | avg prompt length + ask-clarification penalty | ≥100 chars = 20; ≥50 = 15; ≥20 = 10; else 5; minus 0-30 for high `ask_user` ratio |
| Tool Utilization (20) | distinct tool count | ≥7 = 20; ≥5 = 15; ≥3 = 10; else 5 |
| Efficiency (20) | tool success rate + bonus for short sessions | success ≥90% = 15; ≥80% = 10; ≥70% = 7; +5 bonus if avg turns < 15 |
| MCP Utilization (20) | configured MCP servers actually invoked | fuzzy match on name; ratio ≥80% = 20; ≥50% = 15; >0 = 10; 0 = 5 |
| Engagement (20) | avg session duration + consistency | 5-30 min = 15; +5 bonus if active on ≥7 days |

New file: `analyzer/EffectivenessScorer.java`. No new deps.

Caveat: Prompt Quality, Tool Utilization, Efficiency need `user.message`
events with content length and tool execution events. We currently extract
none of this from IDE logs. **P1.1 only ships usefully after P0 lands.**

### P1.2 Tips generation

Port `generateTips()` from `sessions.ts:740-798`. Pure string rules tied to
low-scoring categories:

- "Your prompts average N chars — try adding more context, expected
  behaviour, and constraints to reduce back-and-forth."
- "Try using grep, glob, edit, task, view — these tools can speed up your
  workflow."
- "Your tool success rate is N% — review failing commands and provide
  clearer instructions."
- "You have unused MCP servers: X. Try leveraging them in your prompts."
- "Your sessions are very brief — try tackling larger tasks with Copilot
  for more impactful results."

Lives in `reporter/CliReporter.printTips()` and `reporter/HtmlReporter`
adds a Tips card.

### P1.3 MCP server config scanner

Read these files in order, return the first non-empty:

```
~/.vscode/mcp.json
%APPDATA%\Code\User\mcp.json
%APPDATA%\Code - Insiders\User\mcp.json
<project-root>\.vscode\mcp.json
<project-root>\.github\copilot\mcp.json
```

Strip JSONC trailing commas. Extract `servers` or `mcpServers` object keys.

New file: `detector/McpScanner.java`. Pure JSON, no deps.

Used by P1.1's MCP Utilization category and by the new `copilot-lens mcp`
subcommand in P2.3.

### P1.4 Active duration with 5-min gap cap

Port the `MAX_GAP = 300_000` rule from `sessions.ts:267-269, 403-416`. Use
this when computing "session duration" for analytics so paused sessions
aren't inflated.

Touches: `analyzer/StatsAggregator` (replace any current duration calc).

---

## P2 — New subcommands

Each is a thin wrapper around a P0/P1 module.

### P2.1 `copilot-lens score`

```
Overall Effectiveness Score: 72 / 100

  Prompt Quality      14 / 20   avg prompt 87 chars (good)
  Tool Utilization    15 / 20   5 distinct tools used (good diversity)
  Efficiency          12 / 20   82% tool success | avg 14 turns/session
  MCP Utilization     10 / 20   using 1/3 configured MCP servers
  Engagement          21 / 20   avg 18 min/session | active on 12 days

  Tips:
  - You have unused MCP servers: postgres, github. Try them in your prompts.
```

Requires P0 (real prompt text + tools) and P1.1 / P1.2 / P1.3.

### P2.2 `copilot-lens search "<query>" [--limit=N] [--source=vscode|idea]`

Token-frequency search across session content. Rank by `count / word_count`
plus a title bonus (+0.5) and `cwd` bonus (+0.2). Show ±60-char windows
around each match, trimmed to word boundaries. Up to 3 highlight snippets
per result, dedup overlapping.

Source: `analyzer/SearchIndex.java`. Pure CPU on already-collected data.

Requires P0 (real prompt/response text — without it we can only search log
URLs and `summary`, which is thin).

### P2.3 `copilot-lens mcp`

```
Configured MCP servers
-----------------------
  postgres          github.copilot/mcp.json  invoked 4x
  github            github.copilot/mcp.json  invoked 0x (unused)
  internal-tools    .vscode/mcp.json          invoked 12x
```

Uses P1.3 scanner + tool usage counts from P0's session replay.

### P2.4 `copilot-lens export sft --out=finetune.jsonl [--source=vscode|idea]`

One OpenAI-style chat JSON object per assistant turn, suitable for SFT:

```json
{"messages":[
  {"role":"user","content":"hey"},
  {"role":"assistant","content":"...","model":"gpt-4o","ts":"2026-08-24T..."}
]}
```

Skip empty/short sessions. Truncate to a max token budget (configurable,
default 8k tokens using jtokkit).

Uses P0's prompt/response text. If P0 isn't enabled, this command errors
with a clear message: `"chatsession.enabled=false; turn it on in config.properties"`.

### P2.5 `copilot-lens cost [--period=daily|weekly|monthly]`

Port the upstream-API cost estimation table from `token-usage.ts`. Static
per-1k-token prices (configurable via `config.properties.cost.*`). Show
with disclaimer: *"GitHub Copilot bills on premium requests, not tokens.
This is what you'd pay calling the provider APIs directly."*

Useful as a reference number. Pure math on existing token totals.

---

## P3 — Visual improvements to the HTML report

The other project ships a Chart.js web dashboard. We don't have a browser-
side runtime, so we substitute **inline SVG** generated server-side. SVG is
plain text, embeds in our self-contained HTML, renders identically in every
browser, no JS.

### P3.1 SVG bar chart for daily trend

Replace the ASCII `#` bars in `report` with horizontal SVG `<rect>` bars.
Same data, same width calc. Tooltip on hover is nice-to-have but optional
(needs a tiny `<title>` element which works without JS).

### P3.2 SVG line chart for cumulative tokens

Stack the daily trend area as a line chart. Y axis = total tokens,
X axis = day. One `<polyline>` per IDE series.

### P3.3 SVG donut for model distribution

Replace the model distribution table with a small donut chart (one arc per
model). Center label shows the top model.

### P3.4 Sparkline inside the trend table

Each daily row gets a 60×12 px sparkline of the last 14 days.

### Constraint check

All four use **inline SVG only**. No external resources, no JS. The report
file stays self-contained. Renders correctly with `prefers-color-scheme`
because we use CSS custom properties already.

### Effort

~1 day. Heaviest part is the SVG geometry math (path arcs for donut). Could
ship as a `reporter/SvgChart.java` helper used by all four.

---

## P4 — Small utilities

### P4.1 `copilot-lens workspaces`

List detected workspaces (from `workspaceStorage` index) with session count,
last activity, top directory.

### P4.2 `chatsession.maxBytes` documented in `--help`

Right now the help text only mentions the log globs. After P0 we add
`chatsession.*` keys to the help output.

### P4.3 Cache-hit rate metric (skipped — see "Out of scope")

---

## Out of scope (and why)

| Feature from the other project | Why we skip it |
|---|---|
| Web dashboard (Express + Chart.js at `localhost:3000`) | Requires Node runtime. Our self-contained HTML report is the equivalent. |
| `--open` (auto-launch browser) | OS shell call we don't need; user opens the file directly. |
| Ink TUI (React for terminals) | Replaces our straight `System.out` printer. No benefit; we'd lose ANSI escape sequence control. |
| `better-sqlite3` native module | We'd use pure-Java `sqlite-jdbc` instead. Same effect, no native binary. |
| Copilot CLI `usage` token counts | The other project gets real `prompt_tokens` / `completion_tokens` from `~/.copilot/logs/process-*.log`. The user doesn't run Copilot CLI — only the VSCode/IntelliJ plugins. N/A. |
| Claude Code `~/.claude/projects/**/*.jsonl` | User constraint: "only GitHub Copilot, no third-party AI". Not applicable. |
| Cache hit rate metric | We don't have `prompt_tokens_details.cached_tokens` in IDE log sources. Could only show if we added Copilot CLI; out of scope. |
| Active duration with 5-min gap cap | **In scope as P1.4.** Listed here for clarity, not skipped. |
| 8 separate charts in one dashboard | Our HTML report gets P3 SVG charts; we don't ship a separate dashboard route. |

---

## Order of execution

1. **P0** first — schema is verified, format is understood, the gain is the
   biggest single feature we can add. Once this lands, P1 and P2 mostly
   follow from it.
2. **P1** in order: 1.3 (MCP scanner) has no deps and is small, do it first;
   then 1.4 (duration cap) touches StatsAggregator; then 1.1 and 1.2 land
   together since 1.2 needs 1.1's output.
3. **P2** wraps P1/P0 into user commands: score (needs 1.1+1.2), search
   (needs P0), mcp (needs 1.3 + P0), sft (needs P0), cost (independent).
4. **P3** is independent of P0/P1/P2 and can run anytime; it only touches
   `HtmlReporter`.
5. **P4** last; optional polish.

---

## Open questions

- Do we want to allow **multiple configurations** (e.g., per-workspace
  copilot-lens overrides) or stick to the current global+project model?
- Should `export sft` support other formats (Anthropic, Gemini) or stay
  OpenAI-only?
- Should the SVG charts in P3 also be available in the CLI as ASCII art
  for accessibility / piped output?

These can be answered when each tier is started.
