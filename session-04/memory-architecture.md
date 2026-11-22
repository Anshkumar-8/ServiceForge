# Memory Architecture: Feature 2 (Parts Reservation)

Status: DRAFT for human review. Design only; nothing built or run. B2 will implement it, then audit it against this file.
Sources: repo files named below, `session-02/requirements.md`, `session-03/*`, B1 slides and transcript.

## 1. Memory map (the four boxes)

| Box | What goes here | Real files in this repo | Loaded when |
|---|---|---|---|
| **Semantic** (always-true rules) | Stack conventions, "no database without a decision", API error shapes, standing rules: stock never negative, request ID checked first, buffered overlap rule, 45-minute buffer | `AGENTS.md`, `CLAUDE.md`, `.github/copilot-instructions.md`, `pipeline/rules/overlap-checks.md` | Every session, automatically. Must stay short. |
| **Procedural** (how-to, on demand) | How to build, how to evolve data safely, how to write a spec, how to design and review | `.claude/skills/*/SKILL.md` (build-code, migration-safety, spec-generation), `.claude/agents/*.md`, `pipeline/orchestration.md`. No MCP tools yet. | Only when the task matches a skill or agent description |
| **Episodic** (what happened) | Past decisions and why: bugfix-1 overlap fix, 45-minute buffer, expiry dropped then reopened, Confirm step added, Feature 1 `bookJob` race (open), derived availability | Existing: `pipeline/decisions/*.md`, `pipeline/bugfixes/*.md`. New in B2: `memory_store.json` (one record per decision) | Retrieved just in time when a task resembles the event |
| **Short-term / working** | The current plan task, the file being edited, test output, the current Reserve/Confirm discussion | The context window only. Never saved as is. | Always, and discarded at session end |

Not stored anywhere: chat history, build logs, raw test output, stack traces (keep only the fix that came from them).
`session-02/requirements.md` and the ADR are **documents the task reads (context)**, not memory. Their decisions get copied into episodic memory as short facts.

## 2. Store selection

| Memory | Store | Why |
|---|---|---|
| Semantic | Markdown files (instructions, rules) | Small, human-edited, version-controlled, loaded whole |
| Procedural | Markdown skills and agents | Description-matched loading is built into the tool |
| Episodic | **JSON file** (`memory_store.json`) behind a `put`/`search` interface | A few hundred short facts with exact keys (feature, type, status): structured lookup is enough |
| Vector / graph | **Not for v1** | `AGENTS.md` forbids a database without a recorded decision. Embeddings add little for hundreds of facts. Revisit if memory passes about 1,000 records or fuzzy search is needed; swap the store, keep the interface. |

Hybrid rule for the day we add one: structured lookup first, vector search second.

## 3. Namespace scoping `repo:branch:env`

- Format: `serviceforge:<branch>:<env>`. Env is `dev` (mock data only).
- **`serviceforge:main:dev`**: durable, approved facts (merged decisions, rules). Readable from every branch.
- **`serviceforge:<feature-branch>:dev`**: work-in-progress facts for that branch. Readable only from that branch.
- **Promotion:** when a branch merges to `main`, its approved `active` facts are copied into the `main` namespace. Abandoned branches are never promoted.
- **Read rule:** a branch reads its own namespace plus `main`, never another branch's.
- **Leak check for B2:** a fact written on branch A must not appear in a retrieval on branch B.

## 4. Retain / discard rules

| Keep (with provenance) | Discard |
|---|---|
| Decisions and the reason (e.g. "no expiry; held until job cancelled") | Raw conversation, chatter, reasoning traces |
| Domain facts (45-minute buffer, `CANCELLED` jobs do not block) | Build and test logs, stdout, stack traces |
| Prior-feature dependencies (Feature 2 must call `bookJob`, not re-implement it) | Anything already in the code or in git history |
| Failed approaches (15-minute expiry needs a Confirm step) | Duplicates and anything that would not change the next action |

Test for every item: "If a future agent did not know this, would it act differently?" If no, discard.
One fact per record, one sentence of content, never a pasted document.

## 5. Write path

- **Trigger (after the turn, never inline):** an agent turn ends with a decision; a human approves a gate; a task or feature finishes.
- **Who writes:** the agent proposes the fact; a human approves anything that becomes a decision (matches the human gates in `session-03/agent-blueprint.md`).
- **Record fields:** `id`, `namespace`, `source_task`, `timestamp`, `type_tag` (decision / fact / context), `content`, `status` (active / superseded).
- **Superseded, not deleted:** a changed decision gets a new record; the old one becomes `superseded` and points to the new one.
- **Size limit:** content up to about 60 tokens; longer is rejected as a probable dump.

## 6. Read path (just in time)

1. **When:** just before the LLM call for a task, not at session start.
2. **Query:** built from the current task (feature, plan task, key terms), not the whole history. Active records only.
3. **Rank:** exact tag and feature match first, then recency; the more specific fact wins ties.
4. **Progressive disclosure:** inject one-line references first (id + title); full text only for the top items the task needs.
5. **Never** load memory eagerly "just in case".

## 7. Token ceiling

Hard ceiling for retrieved memory per task: **500 tokens**, counted before injecting. This excludes the always-loaded semantic files and the task itself.
| Slice | Tokens | Contents |
|---|---|---|
| References | 100 | One-liners for up to about 8 matching records |
| Detail | 300 | Full text of the top 3 to 5 records |
| Reserve | 100 | Left unused so tool descriptions and reasoning are not squeezed |

Why 500: roughly 8 short decisions with their reasons, small enough to leave the window for code and the task.
**Cut first under pressure:** detail of low-ranked records, then references, never the highest-ranked decision.
Semantic files are already large (`AGENTS.md` is about 480 words, roughly 650 tokens), so keep them short and move how-tos into skills.

## 8. Conflict precedence (mentor's order)

Procedural (rules, guardrails) > in-context (the user's prompt now) > working state > episodic > semantic.
Note: the mentor's order treats system rules and guardrails as "procedural". In this repo they live in `pipeline/rules/` (listed under semantic above), so for conflicts I treat `pipeline/rules/*` as hard guardrails at the top. Example: a prompt saying "skip the 45-minute buffer for this run" loses to `overlap-checks.md`; the agent stops and asks the human.
A conflict means a memory is stale or duplicated: fix the memory, do not rely on the order.

## 9. Maintenance

- Promote an episodic fact to a semantic rule when it recurs (e.g. overlap bug led to `overlap-checks.md`).
- Mark superseded facts; review stale and duplicate records at each feature close.
- Story-level artifacts (specs, review notes) can be archived when the feature ships; decisions stay.

## 10. Open questions
1. Is a vector store ever needed here? Current answer: no for v1, review at about 1,000 records.
2. Who approves a promotion to the `main` namespace? Assumed a human at PR review.
3. Does the user-prompt-vs-guardrail case in section 8 need an explicit rule?
4. Is 500 tokens right? Assumed; B2 should measure real retrievals.

## Whiteboard summary (slide 12)
Boxes: Working (context) / Long-term store (`memory_store.json`, plus rule and decision files) / Write path (post-turn, filter, provenance) / Read path (query, rank, 500-token cap, JIT).
Retain-vs-discard in one sentence: keep a fact only if forgetting it would change what the agent does next.
Open question to bring: when should a JSON store be replaced by a vector store?
