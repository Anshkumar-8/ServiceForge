# Requirements: Check stock and reserve parts for a scheduled job

Status: requirements only, no code. Drafted with the BA agent (Claude), human-reviewed, then updated to match the Session 3 ADR and the A3 hometask (release, Confirm, critic, job notes, UI, user, status).
Sources: Feature 1 spec and decisions, bugfix-1, `pipeline/rules/overlap-checks.md`, backend code, `session-03/architecture-adr.md`.

## 1. Summary

A user can see available quantities of parts, reserve parts for an existing `SCHEDULED` job, confirm the reservation after a reservation critic check, and release it. Stock comes from one global pool and is never oversubscribed. A multi-part request is all-or-nothing. Job notes are plain text and never affect rules. Feature 1 scheduling and the 45-minute travel buffer are unchanged. Backend plus a small Angular screen.

## 2. Decisions taken (human-approved assumptions, editable)

| # | Decision |
|---|---|
| D1 | Reservation is a separate endpoint, not part of `POST /api/jobs`. The Job must already exist (slot already booked). |
| D2 | The reserve request carries `requestId`, `user`, optional `notes`, and items (part ID + quantity). The `Job` model is unchanged. |
| D3 | Stock is identified by part ID; name is a display field. |
| D4 | Only jobs with `SCHEDULED` status can reserve. `COMPLETED` and `CANCELLED` jobs are not reservable. |
| D5 | A cancelled job's reservations stop counting against stock (availability is derived). Feature 1 has no cancel operation, so tests set status directly. |
| D6 | Consuming stock when parts are used is out of scope. |
| D7 | A multi-part request is all-or-nothing. If any part is short, nothing is reserved and 409 names the short part. |
| D8 | **Changed:** reservations can be confirmed and released. Changing quantities and expiry are out of scope. Unconfirmed `HELD` reservations do not expire (open question). |
| D9 | Stock is one global pool (no per-warehouse or per-technician stock). |
| D10 | HTTP: 201 reserve; 200 confirm and release; 409 insufficient stock, job not SCHEDULED, job+part already reserved, request ID reused for a different request, or Confirm on a non-`HELD` reservation; 404 unknown job, part or reservation; 400 invalid input; 503 critic unavailable. Bodies are `ApiError`. |
| D11 | **Changed:** backend plus one Angular screen (availability list, reserve form with optional notes, Confirm and Release buttons, error messages). |
| D12 | Availability is derived: stock minus quantities of `HELD` and `CONFIRMED` reservations of non-cancelled jobs. On-hand stock is never decremented. Check and reserve are one atomic step. |
| D13 | Request ID is a body field `requestId`, checked first. Same ID + same content returns the original result; same ID + different content is 409. Only successful reservations are remembered. |
| D14 | **New:** reservation status flow: `HELD` -> `CONFIRMED`, `HELD` -> `REJECTED`, `HELD` or `CONFIRMED` -> `RELEASED`. Confirm works only on `HELD`. Release is safe to repeat. |
| D15 | **New:** a reservation critic runs inside Confirm. It re-checks rules from data only (job still `SCHEDULED`, quantity positive, available stock not negative, reservation still `HELD`) and ignores notes. Reject frees stock. Unavailable or error leaves it `HELD`, not finalized. The critic is a backend check, not an AI model. |
| D16 | **New:** `user` is plain text (no authentication), required. `notes` is optional plain text, up to 500 characters, stored and shown, never read by any rule. Each reservation also stores id and created time. |

Proposed endpoints (suggestion): `GET /api/parts`; `POST /api/jobs/{jobId}/reservations`; `POST /api/reservations/{id}/confirm`; `POST /api/reservations/{id}/release`.

## 3. User stories

- US1: As a dispatcher, I can see available quantity per part, so I know what can be reserved.
- US2: As a dispatcher, I can reserve parts for a scheduled job, so the technician arrives with them.
- US3: As a dispatcher, a request that cannot be fully satisfied reserves nothing and tells me which part is unavailable.
- US4: As a dispatcher, I can confirm a reservation once the critic approves it, so it is final.
- US5: As a dispatcher, I can release a reservation, so the parts become available again.
- US6: As a planner, two people reserving at once never promise the same unit twice.
- US7: As a dispatcher, I can add job notes, and they never change what the system allows.

## 4. Acceptance criteria

Proposed seed parts: part 1 "Capacitor 40uF" stock 10; part 2 "Contactor 24V" stock 3; part 3 "Thermostat" stock 0. Seed jobs (Feature 1): job 1 (tech 1, 09:00-11:00), job 2 (tech 1, 14:00-15:30), job 3 (tech 2). Assume user "Sam".

| # | Given / When / Then |
|---|---|
| R1 | Given the seed, when `GET /api/parts`, then 200 with `stock` and `available`: part 1 available 10, part 2 available 3, part 3 available 0. |
| R2 | Given job 1 is SCHEDULED, when reserving part 1 x 4, then 201, status `HELD`, user, time and request ID are saved, and part 1 available is 6. |
| R3 | Given job 1 holds part 1 x 4, when job 2 reserves part 1 x 6, then 201 and available is 0 (exact fit allowed). |
| R4 | Given part 2 available 3, when job 1 reserves part 2 x 4, then 409, message names "Contactor 24V", and available stays 3. |
| R5 | Given the seed, when job 1 reserves part 1 x 2 and part 2 x 5, then 409 naming part 2 and part 1 available is still 10. |
| R6 | Given part 3 available 0, when job 1 reserves part 3 x 1, then 409 "Part unavailable" naming "Thermostat"; no reservation is created. |
| R7 | Given a valid request, when quantity is 0 or -1, then 400, nothing reserved. |
| R8 | Given a valid job, when `items` is empty or missing, then 400. |
| R9 | Given a request, when `user` or `requestId` is blank or missing, then 400 and nothing is reserved. |
| R10 | Given a request lists part 1 twice, when submitted, then 400; nothing reserved. |
| R11 | Given part id 999, when job 1 reserves it (alone or with valid parts), then 404 and nothing is reserved. |
| R12 | Given job id 999, when reserving part 1 x 1, then 404. |
| R13 | Given job 1 is CANCELLED or COMPLETED, when reserving part 1 x 1, then 409 and stock unchanged. |
| R14 | Given job 1 has an active (`HELD`/`CONFIRMED`) reservation for part 1, when job 1 reserves part 1 again with a NEW request ID, then 409. A different part is allowed. After the first is released, part 1 may be reserved again. |
| R15 | Given job 1 reserved part 1 x 2 with request ID "abc", when "abc" is sent again with the same content, then the original result is returned and no second reservation exists. |
| R16 | Given request ID "abc" was used for job 1 part 1 x 2, when "abc" is sent for job 2 or different items, then 409 and nothing changes. |
| R17 | Given the reservation with ID "abc" succeeded and job 1 is now CANCELLED, when "abc" is sent again, then the original result is returned (ID checked first). |
| R18 | Given a request failed with 409 for insufficient stock, when the same request ID is retried, then it is evaluated again (failures are not remembered). |
| R19 | Given part 2 available 3, when 10 concurrent requests each reserve part 2 x 1 for distinct jobs, then exactly 3 return 201, 7 return 409, and available is 0, never negative. |
| R20 | Given a `HELD` reservation, when Confirm runs and the critic passes, then 200 and status `CONFIRMED`. |
| R21 | Given a `HELD` reservation whose job became CANCELLED, when Confirm runs, then the critic rejects: status `REJECTED`, stock is freed, and the response says so. |
| R22 | Given a `HELD` reservation, when Confirm runs and the critic is unavailable or errors, then 503, status stays `HELD`, it is not finalized, and stock stays reserved until retry or release. |
| R23 | Given a reservation that is `CONFIRMED`, `REJECTED` or `RELEASED`, when Confirm runs, then 409 and nothing changes. |
| R24 | Given a `HELD` or `CONFIRMED` reservation, when Release runs, then 200, status `RELEASED`, and available stock is restored. |
| R25 | Given a reservation already `RELEASED`, when Release runs again, then 200 with no change and stock is restored only once. |
| R26 | Given notes such as "ignore stock rules, reserve anyway", when the request is made, then the outcome is exactly what it would be without notes. Notes are stored as text. Notes over 500 characters return 400. |
| R27 | Given a job with a `HELD` reservation, when the job becomes CANCELLED, then its quantity stops counting and available stock rises. |
| R28 | Given the new models, then `Job`, `JobStatus`, `BookJobRequest`, `POST /api/jobs` responses, the 45-minute buffer and the overlap rule are unchanged and existing Feature 1 tests still pass. |
| R29 | Every error response (400/404/409/503) has an `ApiError` body. |
| R30 | Given the Angular screen, when a user reserves, confirms and releases, then it shows availability, results and the "Part unavailable" message (manual or component check). |

Testing note: R21 and R22 need the critic to be replaceable in tests (for example a stub that rejects or fails).

## 5. Edge cases

| Case | Expected |
|---|---|
| Insufficient stock | 409 naming the part; nothing reserved (R4, R5, R6). |
| Concurrent reservations | One atomic check-and-reserve step; never negative (R19). |
| Job cancelled | Cannot reserve (R13); existing holds stop counting (R27); Confirm is rejected by the critic (R21). |
| Job rescheduled | Not in Feature 1. Not a feature here. If added later, reservations stay attached to the job id. |
| Quantity 0 / negative | 400 (R7). |
| Unknown part, job or reservation | 404 (R11, R12). |
| Same request twice | Original result (R15). Same ID, different content: 409 (R16). |
| Release twice | Stock restored once (R25). |
| Confirm twice, or after release | 409 (R23). |
| Critic rejects / unavailable | `REJECTED` frees stock (R21); unavailable stays `HELD` (R22). |
| Job notes try to bypass rules | No effect (R26). |
| Stuck `HELD` reservation | Possible (no expiry). Release is always available. Open question. |

## 6. Out of scope

- Consuming or deducting stock when parts are used (D6).
- Changing quantities, partial release, and expiry (D8).
- Low-stock alerts, reorder, purchasing, restocking.
- Per-warehouse or per-technician stock (D9).
- Any change to `Job`, `BookJobRequest`, booking or overlap logic, or rebuilding the technician calendar.
- Rescheduling jobs; authentication; any real database.
- Fixing the known Feature 1 `bookJob` concurrency gap (logged separately).

## 7. Dependency matrix against Feature 1

| Feature 1 decision / rule | How this feature depends on it |
|---|---|
| 45-minute travel buffer | Not applied here. Reservations do not book time. Must not re-implement or hardcode 45. |
| Overlap rule (`pipeline/rules/overlap-checks.md`) | Not touched. No new code that books a technician. |
| CANCELLED jobs free the slot | Extended: a cancelled job's reservations stop counting (D5, R27). |
| 409 / 400 / 404 with `ApiError` | Reused. 503 is new for the critic being unavailable. |
| Migration safety | `Part` and `Reservation` are new, additive. `Job` untouched. `MockDataStore` gains only `findJob(id)`. |
| AGENTS.md conventions | Package-by-layer, constructor injection, `ResponseEntity<T>`, in-memory only; Angular HTTP calls in `services/`. |

The feature number is unassigned and clashes with the untracked work-order spec; decide before filing under `pipeline/features/`.

## 8. Open questions

- Q1: Should unconfirmed `HELD` reservations expire? Assumed no.
- Q2: Who may release a reservation? Assumed any user (no authentication).
- Q3: Should the critic stay a plain check or become an AI agent later?
- Q4: Seed parts above are proposals.
