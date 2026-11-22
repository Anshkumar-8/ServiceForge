# Memory audit report: build vs B1 design

Audited: `memory_pipeline/` (Java 17, own Maven project) against `session-04/memory-architecture.md`.
Evidence: `mvn test` in `memory_pipeline/` ran **18 tests, 0 failures, 0 errors** (ReadPathTest 7, StoreAndFilterTest 6, WritePathTest 5).
The demo (`Main demo`) and a `read` against the seeded `memory_store.json` were run; their output is quoted below.
Not run: any real LLM, any real agent turn, a real tokenizer, two processes writing one file.

## Round trip (proved by running it)
1. Wrote a Feature 1 overlap decision through the write path; a chatter line in the same turn was **rejected** ("conversational chatter").
2. Seeded 12 records (all passed the filter). 6 Feature 1 facts in `serviceforge:main:dev`, 6 Feature 2 decisions in `serviceforge:day5-memory-pipeline:dev`.
3. Task: "Implement the Reserve action for a scheduled job; respect the travel buffer and the booking overlap rule".
   Result: the Feature 1 rule (`bookJob`, overlap, buffer) came back first, **386 of 500 tokens**. A second run on the seeded store used **331 of 500**.
4. Token ceiling held: a test with 120 matching records stayed under ceilings of 500, 200 and 100.

## Checklist (slide 9) against B1

| Check | Result | Evidence |
|---|---|---|
| Write path keeps only retain-worthy content | **Match** | Filter rejects chatter, logs, stack traces, transcripts, multi-line, empty, over 60 tokens (StoreAndFilterTest) |
| Runs after the turn, non-blocking | **Match** | `submit` returned while the store was blocked (WritePathTest) |
| Provenance at write time | **Match** | sourceTask, timestamp, typeTag, status, namespace all set (WritePathTest) |
| Superseded, not deleted | **Match** | Old record kept, hidden from reads (tests) |
| Store choice | **Match** | JSON file behind a `MemoryStore` interface, no database (B1 section 2, `AGENTS.md`) |
| Retrieves before responding, just in time | **Partial** | `ReadPath.read(task)` is called on demand and injects nothing for an irrelevant task. But nothing calls it from a real agent yet (gap 1) |
| Ranks by recency + relevance | **Partial** | Keyword hits + type weight + recency bonus; B1 said exact tag/feature match first (deviation 1) |
| Respects the token ceiling | **Match** | Counted before adding each line; hard check throws if breached |
| Progressive disclosure | **Match** | References (about 20%) first, then detail (about 60%); last detail item truncated, as seen with mem-009 in the demo |
| A written decision comes back | **Match** | Round trip above |
| No leakage across namespaces | **Match for reads** | Branch A's record never appeared on branch B; main visible on both (ReadPathTest) |

## Gaps
1. **Not wired into a running agent.** The read and write paths work as a library and CLI, but no agent or hook calls them after a turn or before a call. The Feature 2 pipeline itself is not built, so "wired into the Feature 2 pipeline" is only shown by the demo.
2. **No human approval on writes.** B1 says a human approves anything that becomes a decision. Any caller can write to `MAIN`. This is the main memory-pollution risk across namespaces: a branch can write into shared memory with no gate.
3. **No promotion.** B1 says approved branch facts are copied to `main` at merge. Nothing implements it.
4. **No duplicate detection.** In the demo, the hand-written overlap decision (mem-001) and the seeded one (mem-003) say nearly the same thing, and both were injected, wasting budget.
5. **Conflict precedence is not implemented.** B1 section 8 defines an order; the read path only hides superseded records and does not compare conflicting active ones.
6. **Token count is an estimate** (about 4 characters per token), never compared with a real tokenizer. The 500 ceiling is therefore approximate.
7. **Single-process only.** The store is synchronized in one JVM and writes atomically, but two processes writing one file could overwrite each other, and ids come from `size()+1`.
8. **No maintenance.** Stale-record review and episodic to semantic promotion (B1 section 9) are manual.

## Deviations (deliberate or noted)
1. Ranking uses keyword overlap, not an exact feature tag match (no `feature` field in the record; `sourceTask` is searched as text).
2. Records carry an extra `supersededBy` field not listed in B1.
3. The 100/300/100 split is expressed as 20% / 60% / 20% of the ceiling, so it scales when the ceiling changes.
4. Seed is hand-written, not parsed from the markdown files, so the store can drift from `pipeline/decisions/`.

## What I would harden before real use (one thing)
Require an approval step for writes to `MAIN` (gap 2), then add duplicate detection (gap 4).

## Open question for the debrief
Is keyword matching enough, or does the retrieval need embeddings once memory passes a few hundred records?

## Design lesson
B1 said the "specific fact wins ties"; the build needed a concrete rule (branch namespace before main), which B1 did not define. The design was right but under-specified.
