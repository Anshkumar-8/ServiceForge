# Requirements: Check stock and reserve parts for a scheduled job

Status: requirements only, no code. Drafted with the BA agent (Claude), then human-reviewed; updated after the Session 3 ADR and design review (request ID, derived availability, no expiry).
Sources read: spec-generation-skill, Feature 1 spec + decisions, bugfix-1, `pipeline/rules/overlap-checks.md`, backend model/data/service/controller.

## 1. Summary

Let a user reserve quantities of parts from a single global stock pool against an existing `SCHEDULED` Job (Feature 1). Reservation is its own endpoint; `Job` is unchanged. Stock cannot be oversubscribed, a multi-part request is all-or-nothing, and cancelling a job releases its reservations. Backend only.

## 2. Decisions taken (human-approved assumptions, editable)

| # | Decision |
|---|---|
| D1 | Reservation is a separate endpoint, not part of `POST /api/jobs`. The Job must already exist (slot already booked). |
| D2 | Request carries a request ID plus parts + quantities. The `Job` model is unchanged. (Request ID added after ADR 1.) |
| D3 | Stock is identified by part ID; name is a display field. |
| D4 | Only jobs with `SCHEDULED` status can reserve. `COMPLETED` and `CANCELLED` jobs are not reservable. |
| D5 | Cancelling a job automatically releases its reservations (mirrors Feature 1: cancelled jobs free the slot). |
| D6 | Consuming stock when parts are used is out of scope. Only reserve and release. |
| D7 | Multi-part request is all-or-nothing. If any part is short, nothing is reserved and 409 names the short part. |
| D8 | Changing or expiring reservations is out of scope. |
| D9 | Stock is one global pool (no per-warehouse or per-technician stock). |
| D10 | HTTP: 201 success; 409 insufficient stock, same job+part reserved again, job not SCHEDULED, or request ID reused for a different request; 404 unknown job or part; 400 quantity <= 0, empty request, duplicate part in one request, or missing request ID. Bodies are `ApiError`. |
| D11 | Backend only; no Angular UI. |
| D12 | Concurrent reservations must not oversubscribe stock. Availability is derived (stock minus active reservations); on-hand stock is never decremented. The check and the reservation are one atomic step. (Reworded after design review.) |
| D13 | Request ID is a body field `requestId`. Checked first. Same ID + same job/items returns the original result; same ID + different job/items is 409. Only successful reservations are remembered. |

Proposed endpoint shape (a suggestion, not a human decision): `POST /api/jobs/{jobId}/reservations` with body `{ "items": [ { "partId": 1, "quantity": 2 } ] }`; plus a read of stock (`GET /api/parts`) so "check stock" is observable.

## 3. User stories

- US1: As a dispatcher, I can see available quantity per part, so I know what can be reserved.
- US2: As a dispatcher, I can reserve parts for a scheduled job, so the technician arrives with them.
- US3: As a dispatcher, a request that cannot be fully satisfied reserves nothing and tells me which part is short.
- US4: As a dispatcher, cancelling a job returns its parts to available stock.
- US5: As a planner, two people reserving at once never promise the same unit twice.

## 4. Acceptance criteria

Proposed seed parts (new `Part` model, seeded in `MockDataStore`): part 1 "Capacitor 40uF" stock 10; part 2 "Contactor 24V" stock 3; part 3 "Thermostat" stock 0. Jobs from existing seed: job 1 (Acme, tech 1, 09:00-11:00), job 2 (Northwind, tech 1, 14:00-15:30), job 3 (Globex, tech 2). "Available" = stock minus reserved.

| # | Given / When / Then |
|---|---|
| R1 | Given the seed, when `GET /api/parts`, then 200 and part 1 shows available 10, part 2 available 3, part 3 available 0. |
| R2 | Given job 1 is SCHEDULED, when reserving part 1 x 4, then 201, the response lists the reservation, and part 1 available is 6. |
| R3 | Given job 1 reserved part 1 x 4, when job 2 reserves part 1 x 6, then 201 and available is 0 (exact-fit is allowed). |
| R4 | Given part 2 available 3, when job 1 reserves part 2 x 4, then 409, message names "Contactor 24V" (id 2), and available stays 3. |
| R5 | Given the seed, when job 1 reserves part 1 x 2 and part 2 x 5, then 409 naming part 2 and part 1 available is still 10 (all-or-nothing). |
| R6 | Given part 3 available 0, when job 1 reserves part 3 x 1, then 409 naming "Thermostat". |
| R7 | Given a valid request, when quantity is 0 or -1, then 400, nothing reserved. |
| R8 | Given a valid job, when `items` is empty or missing, then 400. |
| R9 | Given part id 999, when job 1 reserves it, then 404 and nothing is reserved (including other valid parts in the same request). |
| R10 | Given job id 999, when reserving part 1 x 1, then 404. |
| R11 | Given job 1 reserves part 1 x 2, when job 1 reserves part 1 x 1 again, with a NEW request ID, then 409 (duplicate job/part), stock unchanged. A request for a different, non-duplicated part is allowed. |
| R18 | Given job 1 reserved part 1 x 2 with request ID "abc", when the same request ID "abc" is sent again, then the original result is returned and stock is unchanged (no second reservation). |
| R19 | Given a reservation request, when the request ID is missing or blank, then 400 and nothing is reserved. |
| R20 | Given request ID "abc" already reserved job 1 part 1 x 2, when "abc" is sent for job 2 or for different items, then 409 and nothing changes. |
| R21 | Given job 1's reservation with ID "abc" succeeded and job 1 is now CANCELLED, when "abc" is sent again, then the original result is returned (the ID is checked first). |
| R22 | Given a request failed with 409 for insufficient stock, when the same request ID is retried, then it is evaluated again (failures are not remembered). |
| R12 | Given a request lists part 1 twice, when submitted, then 400; nothing reserved. |
| R13 | Given job 1 is CANCELLED or COMPLETED, when reserving part 1 x 1, then 409 and stock unchanged. |
| R14 | Given job 1 holds part 1 x 4 (available 6), when job 1 becomes CANCELLED, then part 1 available is 10 and job 1 no longer holds a reservation. |
| R15 | Given part 2 available 3, when 10 concurrent requests each reserve part 2 x 1 (distinct jobs), then exactly 3 return 201, 7 return 409, and available is 0, never negative. |
| R16 | Given the new Part/Reservation models, then `Job`, `JobStatus`, `BookJobRequest` and `POST /api/jobs` responses are byte-for-byte unchanged and existing Feature 1 tests still pass. |
| R17 | Every error response (400/404/409) has an `ApiError` body. |

## 5. Edge cases

| Case | Expected |
|---|---|
| Insufficient stock | 409 naming the first short part; nothing reserved (R4, R5, R6). |
| Concurrent reservations | One atomic check-and-reserve step; availability is derived, never negative (R15). |
| Job cancelled | Cannot reserve (R13); existing reservations released (R14). |
| Job rescheduled | Rescheduling does not exist in Feature 1 (no update endpoint; `startTime`/`endTime` are final). Not a feature here. If it is added later, reservations should stay attached to the job id; to be decided then. |
| Quantity 0 / negative | 400 (R7). |
| Unknown part | 404, whole request rejected (R9). |
| Unknown job | 404 (R10). |
| Duplicate reservation | Same request ID: original result (R18). New ID, same job+part: 409 (R11). Duplicate part within one request: 400 (R12). |
| Empty list | 400 (R8). |
| COMPLETED job | 409 (R13); only SCHEDULED jobs can reserve. |

## 6. Out of scope

- Consuming/deducting stock when parts are used (D6); a "used" state.
- Modifying, partially releasing, or expiring a reservation (D8); manual release endpoint.
- Low-stock alerts, reorder, purchasing, restocking endpoints.
- Per-warehouse or per-technician stock (D9).
- Any change to `Job`, `BookJobRequest`, or booking/overlap logic.
- Rescheduling jobs.
- Angular UI (D11); any real database or new dependency.

## 7. Dependency matrix against Feature 1

| Feature 1 decision / rule | How this feature depends on it |
|---|---|
| 45-minute travel buffer (`TRAVEL_BUFFER_MINUTES`) | Not applied here. Reservations do not book time. Depends on it only indirectly: the job's slot was already validated by `bookJob`. Must not re-implement or hardcode 45. |
| Overlap rule (`pipeline/rules/overlap-checks.md`, buffered intervals) | Not touched. Reservation must not call or duplicate overlap checks; "booked slot" means the Job already exists via `bookJob`. No new code that books a technician. |
| CANCELLED jobs free the slot | Extended by analogy: CANCELLED jobs free their parts (D4, D5). |
| 409 / 400 / 404 with `ApiError` | Reused exactly: 409 conflict/state, 400 invalid input, 404 unknown id. Same exception-to-HTTP mapping style as `JobController`. |
| Migration safety (`migration-safety-skill`) | `Part` and `Reservation` are new models, additive only. `Job` untouched; seed parts added to `MockDataStore`; grep consumers of `MockDataStore` before finishing (R16). |
| AGENTS.md conventions | Package-by-layer, constructor injection, `ResponseEntity<T>`, in-memory only. |

Feature 1 has no decision log entry about parts. The spec says parts "start with Feature 2" (note: `feature-2-work-order-assignment.md` was ignored as instructed, so this feature's number is unassigned).

## 8. Open questions — resolved after skeptical review (edit if you disagree)

- Q1 RESOLVED: no cancel endpoint exists, so release is automatic by computing availability = stock minus reservations of non-CANCELLED jobs. Tests set job status directly. No cancel endpoint in this feature.
- Q2 RESOLVED: reserving on a CANCELLED job returns 409.
- Q3 RESOLVED: the same part listed twice in one request returns 400 (R12 = 400), nothing reserved.
- Q4 RESOLVED: D4 tightened. Only `SCHEDULED` jobs can reserve; `COMPLETED` and `CANCELLED` return 409 (R13 covers both).
- Q5 RESOLVED: duplicate = the same job/part pair is reserved again (R11). Reserving a different part for the same job is allowed.
- Q6 ASSUMED: seed parts above are proposals.
- Q7 RESOLVED: no extra GET for reservations; the 201 body is enough.

## 9. Skeptical review (K2.3) — weaknesses found

- R11 distinguishes duplicate job/part pairs from legitimate additional parts, because D8 prohibits changing existing reservations but does not prohibit creating a separate reservation for a new part.
- R15 (10 concurrent requests) needs a real concurrency test (threads/ExecutorService); flaky if done naively.
- R14 depends on setting status directly, which is a test-only trigger, not a user-visible behavior.
- R1 "available" is derived, so `GET /api/parts` should return both `stock` and `available`; criteria did not say which fields exist.
- R16 "byte-for-byte unchanged" is vague; testable form: existing Feature 1 tests pass unchanged and `POST /api/jobs` JSON shape is the same.
- No 409 message format is fixed beyond "names the part"; Feature 1 A12 fixed the wording style, this should too.
- Feature number is unassigned and clashes with the untracked work-order spec; decide before filing under `pipeline/features/`.
