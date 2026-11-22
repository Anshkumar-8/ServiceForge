# Feature 1 — Decision Log

Decisions made while building Feature 1 that later features may need to retrieve. This file exists so a later feature's memory-retrieval step has something concrete to pull a single fact from, instead of needing the entire build history in context.

## Decision: standard travel buffer per job is 45 minutes

**What was decided:** every booked job reserves not just its own start/end time on a technician's calendar, but an additional 45-minute travel buffer, to account for the technician getting to the next job. This is encoded as `TRAVEL_BUFFER_MINUTES = 45` in `TechnicianAvailabilityService`.

**Why it matters beyond Feature 1:** this buffer is what actually limits how many jobs — and, later, how many parts-reservations tied to those jobs — can realistically be scheduled for one technician in a day. Anything built on top of the calendar that reasons about "how many jobs can fit today" needs this number, not a guess.

**Where it lives in code:** `backend/src/main/java/com/serviceforge/service/TechnicianAvailabilityService.java`, `TRAVEL_BUFFER_MINUTES` constant.

## Decision: overlap detection uses buffered intervals (supersedes the exact-start-time check)

**What was decided:** `bookJob(...)` treats each job as blocking `[start, end + 45 min]`. A new booking is rejected (409) if `newStart < existingEnd + buffer` and `existingStart < newEnd + buffer`. A gap of exactly 45 minutes is allowed. `CANCELLED` jobs do not block. The original check compared exact start times only, which silently accepted overlapping bookings.

**Why it matters:** anything that assumes "a technician's booked slots never overlap, and are at least 45 minutes apart" can now rely on it for jobs booked through `bookJob`. Note this also means the seed data's back-to-back style bookings (e.g. a job starting right after another ends) are now rejected.
