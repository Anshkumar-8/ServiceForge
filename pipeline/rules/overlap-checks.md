# Rule: No-Overlap Booking

**Status:** Active. Added after the Feature 1 overlap bug (`pipeline/bugfixes/bugfix-1-overlapping-booking.md`).

Any code that books a technician must use an interval-overlap test, not a start-time comparison, and must apply
`TRAVEL_BUFFER_MINUTES` (from `TechnicianAvailabilityService`) to both jobs. Each job reserves `[start, end + buffer)`.
A conflict exists when:

```text
newStart      < existingEnd + TRAVEL_BUFFER_MINUTES
existingStart < newEnd      + TRAVEL_BUFFER_MINUTES
```

- A gap of exactly 45 minutes is allowed.
- Reject `end <= start` with 400; conflicts return 409. Both use `ApiError`.
- Compare only the same technician's jobs. `CANCELLED` jobs don't block.
- New features must call `bookJob(...)`, not write their own check.
- Never compare only exact start times, skip the buffer, or hardcode 45.

**Tester:** keep regression tests for different-start overlap, buffer conflict, the exact 45-minute boundary, and `end <= start`.
