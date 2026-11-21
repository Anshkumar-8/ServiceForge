# Design review: ADR 1 (Parts Reservation)

**Reviewed by:** Claude (AI assistant) acting as design reviewer, checked against the ServiceForge backend code. No peer review yet.
Planning only; no tests or agents were run.

## Cases
| Case | Outcome in this design | Covered by |
|---|---|---|
| Two valid requests compete for the last part | Both enter the same locked step one at a time; the first succeeds, the second gets 409. Stock never goes negative. | One protected Reserve step |
| No stock available | No reservation saved; user sees "Part unavailable" with the part name. | Stock check inside Reserve |
| Same request submitted twice | Same request ID returns the first result; only one reservation exists. | Request ID checked first |
| Reservation released twice | First release sets `RELEASED`; the second finds `RELEASED` and changes nothing; stock restored once. | Status guard, calculated availability |
| Job notes ask to bypass rules; critic rejects or is unavailable | Notes are plain text and never read by any rule. Rejected means `REJECTED` and stock freed. Unavailable means it stays `HELD`, not finalized. | Critic inside Confirm |

## Findings
1. **Stuck holds.** With a separate Confirm step, a `HELD` reservation that is never confirmed or released locks stock forever, and the critic being unavailable makes this likelier. (Not visible before Confirm existed.)
2. **Repeat actions on other steps.** Release twice was covered, but Confirm twice, or Confirm after Release, was not defined and could finalize stock that was already freed.

## Improvement made to the ADR
Added the explicit status flow (`HELD`, `CONFIRMED`, `REJECTED`, `RELEASED`) with a guard: Confirm works only on `HELD`, Release only on `HELD` or `CONFIRMED`, and repeated actions do nothing. Stuck holds are listed as the main risk with release as the response, and expiry is an open question.

## Open items (not claimed as solved)
- Expiry of `HELD` reservations (open question 1 in the ADR).
- The critic is deterministic; whether it should become an AI agent is undecided.

## Outcome: **Ready**
The design handles all five cases and the two findings are addressed in the ADR. Open questions are labelled and do not block planning.
