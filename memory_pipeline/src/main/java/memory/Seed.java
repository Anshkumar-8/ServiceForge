package memory;

import memory.WritePath.Proposal;
import memory.WritePath.Scope;

import java.util.List;

import static memory.MemoryRecord.CONTEXT;
import static memory.MemoryRecord.DECISION;
import static memory.MemoryRecord.FACT;

/**
 * Hand-written seed: prior-feature facts from Feature 1 (Overlap Booking) go to the shared main namespace;
 * Feature 2 decisions go to the working branch. Seeds pass through the same retain/discard filter as any write.
 */
public final class Seed {

    private Seed() { }

    public static List<Proposal> proposals() {
        return List.of(
                // Feature 1, shared (main)
                new Proposal("feature-1/decisions", FACT,
                        "Every booked job blocks its own time plus a 45-minute travel buffer; the value is "
                                + "TRAVEL_BUFFER_MINUTES in TechnicianAvailabilityService and must never be hardcoded.", Scope.MAIN),
                new Proposal("bugfix-1/overlap", DECISION,
                        "Overlap detection compares buffered intervals: conflict when newStart < existingEnd + buffer "
                                + "and existingStart < newEnd + buffer; exactly 45 minutes apart is allowed.", Scope.MAIN),
                new Proposal("bugfix-1/overlap", FACT,
                        "CANCELLED jobs do not block a slot, only the same technician's jobs are compared, "
                                + "and conflicts return 409 with an ApiError body.", Scope.MAIN),
                new Proposal("bugfix-1/second-pass", DECISION,
                        "A booking whose end time is not after its start time is rejected with 400 and an ApiError "
                                + "body before any overlap check.", Scope.MAIN),
                new Proposal("feature-1/review", CONTEXT,
                        "Known issue: bookJob has no lock and jobs is a plain ArrayList, so two simultaneous bookings "
                                + "for one technician can both pass the overlap check; logged as a separate bug.", Scope.MAIN),
                new Proposal("rules/overlap-checks", DECISION,
                        "New features must call TechnicianAvailabilityService.bookJob and never re-implement the "
                                + "overlap check or the travel buffer.", Scope.MAIN),
                // Feature 2, this branch
                new Proposal("feature-2/adr", DECISION,
                        "Availability is derived as stock minus HELD and CONFIRMED reservations of non-cancelled jobs; "
                                + "on-hand stock is never decremented.", Scope.BRANCH),
                new Proposal("feature-2/adr", DECISION,
                        "Reserve checks the request ID first: same ID and same content returns the original result, "
                                + "same ID with different content is 409.", Scope.BRANCH),
                new Proposal("feature-2/adr", DECISION,
                        "Reservation status flow is HELD to CONFIRMED or REJECTED, and HELD or CONFIRMED to RELEASED; "
                                + "Confirm works only on HELD and release is safe to repeat.", Scope.BRANCH),
                new Proposal("feature-2/adr", DECISION,
                        "A reservation critic runs inside Confirm, re-checks rules from data only, ignores job notes, "
                                + "and an unavailable critic leaves the reservation HELD and not finalized.", Scope.BRANCH),
                new Proposal("feature-2/design-review", FACT,
                        "Expiry was dropped because a hold needs a Confirm step; with Confirm added, unconfirmed HELD "
                                + "reservations can stay stuck, so expiry is an open question.", Scope.BRANCH),
                new Proposal("feature-2/design-review", DECISION,
                        "Parts and reservations live in a new PartStore with its own lock; MockDataStore gains only "
                                + "findJob(id) so Feature 1 stays unchanged.", Scope.BRANCH));
    }
}
