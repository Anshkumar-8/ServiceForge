# Implementation plan: Parts Reservation

Planning only. Nothing is built, run or tested yet. Highest-risk task: **T-02** (protected Reserve and Release).

| Task ID / Task | Owner | Depends on | Output | Estimate | Done check |
|---|---|---|---|---|---|
| T-01 / Create parts and reservation data: `Part`, `Reservation` (id, job, part, quantity, user, request ID, time, status, notes), new in-memory store with its own lock, seed parts, additive `findJob(id)` | Build agent | none | Models and store seeded at startup; Feature 1 untouched | 2 h | Existing Feature 1 tests still pass; seeded parts include one with a single unit and one with zero |
| T-02 / **Add protected Reserve and Release** (HIGH RISK): request ID first, validation, job `SCHEDULED`, stock check and save in one locked step; release with status guard | Build agent, then human reviews | T-01 | Reserve and Release operations | 4 h | Only one of two simultaneous requests for the last part succeeds; stock never negative; same request twice makes one reservation; release twice restores stock once; failed request saves nothing |
| T-03 / Add reservation critic and Confirm: critic re-checks rules from data only, ignores notes; reject frees stock; unavailable keeps `HELD` | Build agent | T-02 | Confirm operation with critic | 3 h | Confirm on a rule-breaking reservation ends `REJECTED`; critic error leaves `HELD`; notes saying "ignore rules" change nothing |
| T-04 / Add API endpoints: list parts, reserve, confirm, release, with `ApiError` 400/404/409 | Build agent | T-03 | REST endpoints | 2 h | No-stock request returns 409 with "Part unavailable"; unknown job or part returns 404 |
| T-05 / Build Angular screen: availability list, reserve form with optional notes, Confirm and Release buttons, error messages | Build agent | T-04 | One screen, HTTP calls in `services/` | 3 h | A user can reserve, confirm and release, and sees the unavailable message when stock is zero |
| T-06 / Write and run tests for the five cases plus Feature 1 regression, then report | Test agent, then human reviews results | T-05 | Test report | 3 h | Report shows pass/fail for: last-part race, no stock, duplicate request, double release, notes/critic cases |

## Human checkpoints
1. Approve the ADR and review before T-01.
2. Review T-02 (highest risk) before T-03.
3. Approve the test report from T-06 before delivery.

## Assumptions
Estimates are rough guesses. The critic is a backend check, not an AI model. Expiry of `HELD` reservations is not planned (ADR open question 1).
