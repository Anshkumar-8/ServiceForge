# Implementation plan: Parts Reservation

Inputs: `architecture-adr.md`, `design-review.md`, `session-02/requirements.md` (R1-R22, D1-D13).
Rules to follow: `pipeline/rules/overlap-checks.md` (do not touch booking), `build-code-skill`, `migration-safety-skill`.
Backend only. No orchestrator: each task runs as a standalone agent. The human approves between tasks.

## Tasks (in build order)

| # | Task | Owner | Depends on | Result | Done check | Risk |
|---|---|---|---|---|---|---|
| T0 | Turn the requirements into a committed spec under `pipeline/features/` and pick the feature number | ba-agent, then **human approves** | none | `feature-N-parts-reservation.md` with a Definition of Done | Human signs off. The number does not clash with the work-order spec. | Low |
| T1 | Add parts data: `Part`, `Reservation` models, `PartStore` (seed parts 1-3, own lock), additive `findJob(id)` on `MockDataStore` | developer-agent | T0 | New classes; each reservation has its own id and request ID; seeded stock 10, 3, 0 | Existing Feature 1 tests pass unchanged (R16). `findJob` returns a job by id. | Low |
| T2 | **Protected Reserve action** in a `ReservationService`: request ID first, then validation, job state, stock check and save as one locked step | developer-agent | T1 | Service method with all-or-nothing and derived availability | Service tests for R2-R14 and R18-R22 pass. No other code writes reservations. | **HIGH** |
| T3 | Endpoints: `GET /api/parts`, `POST /api/jobs/{jobId}/reservations`, exception to HTTP mapping with `ApiError` | developer-agent | T2 | Two endpoints, 201/400/404/409 as in D10 | R1 and R17 pass. Request shape matches the spec. | Medium |
| T4 | Busy-day tests: concurrency (10 parallel requests, 3 succeed), repeat clicks, no stock, cancelled job releases stock, Feature 1 regression | tester-agent | T3 | Test class plus pass/fail report by R-number | R15 gives exactly 3 x 201 and 7 x 409, available 0, never negative. All of R1-R22 reported. | Medium |
| T5 | Record decisions and close out: `pipeline/decisions/feature-N-decisions.md`, log the Feature 1 `bookJob` race as a separate bug | developer-agent, **human approves** | T4 | Decision log with the derived-availability, request ID and lock-location choices | Human reads and approves. Spec Definition of Done ticked. | Low |

## Requirement coverage

| Requirement | Covered by |
|---|---|
| R1 stock visible | T3, T4 |
| R2-R6 reserve, exact fit, insufficient, all-or-nothing, zero stock | T2, T4 |
| R7, R8, R12, R19 validation (quantity, empty, duplicate part, request ID) | T2, T4 |
| R9, R10 unknown part / job | T2, T4 |
| R11, R18, R20, R21, R22 duplicates and request ID | T2, T4 |
| R13 job state (SCHEDULED only) | T2, T4 |
| R14 cancelled job frees stock | T2, T4 |
| R15 concurrency | T2 (lock), T4 (test) |
| R16 Feature 1 unchanged | T1, T4 |
| R17 `ApiError` bodies | T3, T4 |

## Human gates
1. After T0: approve the spec and the feature number before any code.
2. After T2: review the Reserve action (high risk) before endpoints are built.
3. After T4: approve the test report. After T5: approve the decision log.

## Stop rules
- An agent retries a failing step at most 2 times, then stops and asks the human.
- Any agent that needs to change `Job`, `bookJob` or `ApiError` stops. Those are out of scope (R16).
- Never claim a test passed unless it was run.

## Out of scope for this plan
Expiry, Confirm step, release/changing reservations, UI, fixing the Feature 1 `bookJob` race, a `code` field on `ApiError`.
