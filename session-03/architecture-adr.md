# ADR 1: Protected Reserve Parts action

**Status:** Draft for human review. Decisions chosen by the author on 2026-11-21.

- **Context:** Two dispatchers may ask for the last unit of a part at the same moment. If stock is checked and reserved
  in separate steps, both see "one left" and both succeed, so stock goes below zero. Someone may also click Reserve twice.
- **Decision:** Every reservation goes through one protected Reserve action. The stock check and the reservation happen
  together as a single step that only one request can run at a time. Each request carries a request ID. There is no expiry:
  a reservation is held until its job is cancelled (D8 unchanged).
- **Why:** It makes double reservation impossible, and a repeated click with the same request ID returns the first
  result instead of reserving again.
- **Impact:** No other code may change stock or create a reservation. A reservation stores its request ID. Available
  stock is stock minus reservations of non-cancelled jobs. All-or-nothing (D7) applies inside the same step.

**Risk:** Running every request through one single step could slow things down under heavy load.
**Response:** The step only does in-memory checks, so it is short. Revisit if a real database is ever added.

## Rules for repeated requests
Check the request ID first, before job state or stock.

| Situation | Result |
|---|---|
| Same request ID, same job and items | Return the original result; stock unchanged |
| Same request ID, different job or items | 409 (ID already used for another request) |
| New request ID, job+part already reserved | 409; stock unchanged |
| New request ID, different part for the same job | Allowed |

Only successful reservations are remembered. A failed request may be retried with the same ID.

## Design choices (from design-review.md)
- On-hand `stock` never changes here. Available = stock minus reservations of non-cancelled jobs (derived, not decremented).
- New `PartStore` in `data/` holds parts, reservations, request IDs and the lock. `MockDataStore` only gains `findJob(id)`.
- The lock lives inside `PartStore` and is never held around Feature 1 calls.
- Known separate issue: Feature 1 `bookJob` has no lock, so concurrent bookings can double-book. Logged, not fixed here.

## Not included
Expiry, a Confirm step, and manual release (all out of scope, as in requirements D8).
