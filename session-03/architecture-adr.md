# ADR 1: Parts Reservation for a scheduled job

Status: draft for human review. Planning only; nothing here has been built or run.

## 1. Context
A dispatcher needs to set parts aside for an already-scheduled job. Two people may ask for the last unit of a part at the
same moment, someone may click twice, and a reservation may be released twice. Stock must never go below zero. Data lives
in memory and is cleared on restart, so there is no database to lean on. Feature 1's scheduling and 45-minute travel-buffer
rules stay unchanged.

## 2. Decision
One protected **Reserve** operation: inside a single locked step the backend checks the request ID, the input, the job
(must exist and be `SCHEDULED`), and available stock, then creates the reservation. If any check fails, nothing is saved.
A second **Confirm** operation runs the **reservation critic** and only then finalizes it. **Release** frees the stock.

**Status flow:** `HELD` (stock set aside) -> `CONFIRMED` (critic approved) ; `HELD` -> `REJECTED` (critic said no, stock freed) ;
`HELD` or `CONFIRMED` -> `RELEASED` (stock freed). Only `HELD` and `CONFIRMED` count against stock.

| Layer | What changes |
|---|---|
| **MockDataStore** | One additive lookup, `findJob(id)`. Parts and reservations live in a new in-memory store seeded at startup, with its own lock, so Feature 1 data is untouched. |
| **Spring Boot backend** | Reserve, Confirm and Release operations; endpoints to list parts, reserve, confirm, release; `ApiError` bodies as in Feature 1. |
| **Angular UI** | One screen: part availability list, a reserve form (part, quantity, optional job notes), and Confirm / Release actions that show the result or the error message. HTTP calls only in `services/`. |

**Key reservation data:** reservation id, job id, part id, quantity, user, request ID, created time, status, job notes (text).

**Protection and safety**
- **One protected operation:** the stock check and the save happen together under one lock, so two requests cannot both see "one left".
- **Duplicates:** the request ID is checked first. Same ID and same content returns the original result. Same ID with different content is rejected.
- **Safe release:** release only changes `HELD`/`CONFIRMED` to `RELEASED`. A second release finds `RELEASED` and does nothing, so stock is restored once. Available stock is calculated (stock minus active reservations), never added back by hand.
- **No partial changes:** a failed request saves nothing. A multi-part request is all-or-nothing.
- **Job notes** are stored as plain text and shown to people. No rule reads them, so they cannot change stock, status or checks.
- **Critic:** runs inside the Confirm operation, after the Reserve step and before status becomes `CONFIRMED`. It re-checks the rules from data only (job still `SCHEDULED`, quantity positive, stock not negative, reservation still `HELD`). If it rejects, the reservation becomes `REJECTED` and stock is freed. If it is unavailable or errors, the reservation stays `HELD`, is **not** finalized, and the user is told to retry or release.

## 3. Reason
Chosen: check and reserve in one locked step. **Alternative rejected:** check stock first, then reserve in a second call. Under
concurrent requests both can pass the check and oversell the last part. The one-step design is also simple enough for an in-memory store.

## 4. Impact
- **Risk:** a `HELD` reservation nobody confirms or releases keeps stock locked, and a single lock could slow requests under heavy load.
- **Response:** release is always available; the lock only covers short in-memory work; expiry is an open question (below).

## Open questions (labelled assumptions)
1. Should unconfirmed `HELD` reservations expire? Assumed no for now (smallest change); it can be added later because status and time are stored.
2. Who may release a reservation? Assumed any user (no authentication in the app).
3. Does cancelling a job auto-release? Feature 1 has no cancel operation; assumed a cancelled job's reservations stop counting as stock.
4. The critic is a plain backend check, not an AI model, so it is predictable. Replacing it with an agent is a later decision.
