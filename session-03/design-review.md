# Design review: ADR 1 (protected Reserve action)

Reviewed against `architecture-adr.md`, `session-02/requirements.md` and the actual backend code
(`MockDataStore`, `TechnicianAvailabilityService`, `JobController`, `Job`). Reviewer: Claude. Decisions: author.

## ASK
| Question | Answer |
|---|---|
| Does it solve the need? | Yes: reserve parts for a booked job without selling the last unit twice. |
| Is "stock never negative" clear? | Yes, but D12 said "decrement" while the ADR says "available = stock minus reservations". Contradiction, fixed below. |
| No stock? | 409 naming the part; nothing saved (R6). |

## CHALLENGE: drawbacks found

| # | Drawback | Evidence | Cost to fix later | Decision |
|---|---|---|---|---|
| 1 | **Two ways to count stock.** D12 decrements stock, the ADR derives it. Mixing them double-counts. | requirements D12 vs ADR Impact | High (data model) | **Derived only.** `stock` (on hand) never changes in this feature. `available = stock - sum(active reservations)`. D12 reworded. |
| 2 | **The shared data is not thread-safe.** `MockDataStore` uses a plain `ArrayList`, and `bookJob` is not locked. A lock only inside Reserve does not protect against Feature 1 changing jobs at the same time. | `MockDataStore.java` | Medium | New reservation store with its own lock; the Reserve action only reads job status. Feature 1's own race is a **separate known issue** (see below), not fixed here. |
| 3 | **Request ID is under-defined.** Where does it live, how long, what if the same ID comes with different parts? | R18 only covers the happy replay | High (public API) | Body field `requestId`. Same ID + same payload = original result. Same ID + different payload (job or items) = **409**. Only **successful** reservations are remembered; a failed request can be retried with the same ID. IDs are kept for the app's lifetime (in-memory). |
| 4 | **Order of checks.** If the job was cancelled after a successful reserve, does a replay return 201 or 409? | not covered | Medium | Check the request ID **first**: a replay returns the original result even if the job is cancelled since. |
| 5 | **Overloaded 409.** Insufficient stock, duplicate, wrong job state and ID mismatch all return 409 with only a message. A client cannot tell them apart. | `ApiError` has one `message` field | Medium | Accepted. Adding a `code` field changes `ApiError` and Feature 1's responses (R16). Revisit if a UI needs it. Messages use distinct, fixed wording. |
| 6 | **A mistaken quantity cannot be fixed** (D8: no change/release). | requirements D8 | Low-Medium | Accepted for now. Store each reservation with its own id so a release feature can be added later without a data migration. |
| 7 | **Single lock = global bottleneck.** | ADR risk | Low | Accepted: in-memory work only. Keep the lock inside the new store, never around Feature 1 calls. |
| 8 | **Putting parts into `MockDataStore`** makes it a catch-all and touches Feature 1's class. | `MockDataStore.java` | Medium | New `PartStore` (own seed + lock) in `data/`. `MockDataStore` gets one **additive** method, `findJob(id)`. R16 stays true. |
| 9 | **Request ID memory grows forever.** | in-memory map | Low | Accepted for a mock store; note in the plan. |

### Known issue found, not in scope
Feature 1 `bookJob` reads existing jobs, then saves, with no lock, and `jobs` is an `ArrayList`. Two simultaneous bookings
for the same technician can both pass the overlap check. This breaks the overlap rule under concurrency. **Log as a
separate bug** (future bugfix), do not absorb it into this feature.

## DECIDE
- **Approve:** one protected Reserve action; derived availability; all-or-nothing; request ID; no expiry (D8).
- **Improve (applied):** items 1, 3, 4, 8 above are now written into the ADR and requirements.
- **Alternative rejected:** per-part lock (more code, no benefit for mock data); 15-minute expiry (needs a Confirm step,
  see ADR history).
- **Human decision needed:** none blocking. Items 5, 6 and 9 are accepted drawbacks; say if you want any reversed.
