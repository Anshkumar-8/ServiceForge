# Bugfix 1 — Overlapping bookings are accepted

| Field | Value |
|---|---|
| **Status** | Delivered — branch `Task-Fix-the-Overlapping-Booking-Bug` |
| **Fixes** | Feature 1 — Technician Availability Calendar (`pipeline/features/feature-1-technician-availability.md`) |
| **Spec change** | None. The spec's Definition of Done was already correct; the code did not meet it. |
| **Depends on** | Decision: 45-minute travel buffer (`pipeline/decisions/feature-1-decisions.md`) |

This file is a directive for agents (BA / Developer / Tester, see `pipeline/orchestration.md`). It states the
problem, the boundaries and the acceptance criteria *before* any code is touched. Section 6 records how it was
actually delivered.

## 1. Symptom

`POST /api/jobs` accepts a booking that overlaps an existing job for the same technician.
Repro (technician 1 has a seeded 09:00–11:00 job): book 10:00–12:00 today → returns `201`, expected `409`.

## 2. Root cause

`TechnicianAvailabilityService.bookJob(...)` detected conflicts with
`existing.getStartTime().equals(startTime)`, so only an identical start time conflicted. Separately, the
`TRAVEL_BUFFER_MINUTES = 45` decision was defined as a constant but never applied.

## 3. Scope

**In scope**
- Reject any booking whose range overlaps an existing job for the same technician, using the 45-minute buffer.
- Keep the error path: `IllegalStateException` → `409` with an `ApiError` body (controller unchanged).
- Tests for the overlap behaviour; mark the spec's "Known issue" as fixed; update the decision log.

**Out of scope (do not absorb)**
- ~~Validating `endTime` is after `startTime`.~~ **Moved into scope** after UI testing (see section 6, "Second pass").
- Returning `ApiError` (instead of Spring's default body) for `@Valid` failures on `POST /api/jobs`.
- Any parts/inventory work (Feature 2).
- New dependencies, any database.

## 4. Acceptance criteria (the tester checks these literally)

Rule: each job blocks `[start, end + 45 min]`. A new booking conflicts when
`newStart < existingEnd + 45min` **and** `existingStart < newEnd + 45min`.

| # | Criterion | Seed reference (technician 1) |
|---|---|---|
| A1 | A technician can still be booked when free | 17:00–18:00 accepted |
| A2 | A technician's jobs can be listed | count rises 2 → 3 after booking |
| A3 | Partial overlap with a different start is rejected (409) | 10:00–12:00 |
| A4 | Booking fully inside an existing job is rejected | 09:30–10:30 |
| A5 | Booking that encloses an existing job is rejected | 08:00–12:00 |
| A6 | Booking starting inside the buffer after a job is rejected | 11:30–12:30 |
| A7 | Booking ending inside the buffer before a job is rejected | 12:30–13:30 |
| A8 | A gap of exactly 45 minutes is allowed | 11:45–12:45 |
| A9 | Other technicians are unaffected | technician 3, 10:00–12:00 |
| A10 | Identical start time is still rejected (no regression) | 09:00–10:00 |
| A11 | Unknown technician still errors (no regression) | id 999 |
| A12 | Conflict message names the technician and the buffer | — |
| A13 | End before start is rejected with 400 + `ApiError` | 11:00–01:00 (the UI case) |
| A14 | Zero-length booking is rejected with 400 | 11:00–11:00 |

Decision made while writing this: `CANCELLED` jobs do not block a slot (not stated in the original spec).

## 5. Tasks by role

- **BA** — not needed (no spec change). Skipped deliberately.
- **Developer** — load `build-code-skill`; change only `TechnicianAvailabilityService`; keep constructor injection and
  the existing exception → HTTP mapping; apply `TRAVEL_BUFFER_MINUTES`, do not hardcode a new number. Update the spec's
  Known-issue section and the decision log.
- **Tester** — one test per criterion above; report pass/fail by criterion ID. If an existing test now fails because the
  old behaviour was the bug, change that test and say so in the report.

## 6. How this was delivered (honest record)

| Step | What happened |
|---|---|
| Plan shown first | Before editing, the approach was written out: skip BA, developer fix, tester tests, and the interval rule above. |
| Developer step | Done directly by the main Claude Code session, following `build-code-skill`. The `developer-agent` subagent was **not** launched. |
| Code change | `bookJob` now filters out `CANCELLED` jobs and uses `overlapsWithBuffer(...)` (`Duration` + `isBefore`). Controller untouched. |
| Tester step | Done directly by the main session; the `tester-agent` subagent was **not** launched. 9 tests added → 12 total. |
| Test regression | The old happy-path test booked 16:00, which is only 30 min after the 15:30 job. It now correctly conflicts, so it was moved to 17:00. |
| Verification | `mvn test`: 12 run, 0 failures. Live check against the running backend: 10:00–12:00 → 409, 09:00–10:00 → 409, 11:45–12:45 → 201. |
| Human checkpoint | The pipeline asks a human to review the spec before coding. Here the plan was shown and the user said to proceed; no separate spec file existed until this document was written afterwards. |
| Git | `main` holds the original buggy code (`e29c1e4`); the fix is one commit (`d685729`) on `Task-Fix-the-Overlapping-Booking-Bug`, to be raised as a PR. |

### Second pass — found by testing in the UI

| Step | What happened |
|---|---|
| Report | A screenshot showed job `a` (10:00–12:00) and job `b` (11:00–**01:00**) both accepted on the same day. |
| Cause | `b`'s end was before its start (likely 1 AM entered instead of 1 PM). A backwards range never satisfies the overlap comparison, so it slipped through. The first pass had listed this as out of scope; that was the wrong call. |
| Reproduced | Via API before fixing: 11:00–01:00 → 201; 11:00–13:00 → 409. |
| Fix | `bookJob` rejects `end <= start` with new `InvalidBookingException`; `JobController` maps it to 400 + `ApiError`. |
| Verified | 14 tests, 0 failures (2 added: A13, A14). Live: 11:00–01:00 → 400, 11:00–11:00 → 400, 11:00–13:00 → 409, 14:00–15:00 → 201. |
| Lesson | The first pass's "verification" only exercised the API with sensible ranges, not the UI inputs a user can actually produce. |

## 7. Follow-ups (not done)

- Add the first standing rule under `pipeline/rules/`, e.g. "overlap checks must compare intervals and apply the travel
  buffer", as `AGENTS.md` anticipates for a shipped bug. It was not created because it was not requested.
- Next time, run this file through `developer-agent` then `tester-agent` instead of doing both in the main session.
