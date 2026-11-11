package com.serviceforge.service;

import com.serviceforge.data.MockDataStore;
import com.serviceforge.model.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests against Feature 1's Definition of Done (pipeline/features/feature-1-technician-availability.md)
 * plus the 45-minute travel-buffer decision (pipeline/decisions/feature-1-decisions.md).
 *
 * Seed for technician 1 today: 09:00-11:00 and 14:00-15:30. Each job blocks [start, end + 45min].
 */
class TechnicianAvailabilityServiceTest {

    private MockDataStore dataStore;
    private TechnicianAvailabilityService service;

    @BeforeEach
    void setUp() {
        dataStore = new MockDataStore();
        dataStore.seed();
        service = new TechnicianAvailabilityService(dataStore);
    }

    @Test
    void booksAJobForAnAvailableTechnician() {
        LocalDateTime start = at(17, 0);
        LocalDateTime end = start.plusHours(1);

        Job job = service.bookJob(1L, "New Customer", start, end);

        assertEquals("New Customer", job.getCustomerName());
        assertEquals(1L, job.getTechnicianId());
    }

    @Test
    void listsATechniciansBookedJobs() {
        assertEquals(2, service.getJobsForTechnician(1L).size());
        service.bookJob(1L, "New Customer", at(17, 0), at(18, 0));
        assertEquals(3, service.getJobsForTechnician(1L).size());
    }

    @Test
    void rejectsAnOverlapWithADifferentStartTime() {
        // Existing 09:00-11:00; new 10:00-12:00 genuinely overlaps (the original bug).
        assertThrows(IllegalStateException.class, () -> service.bookJob(1L, "Overlap", at(10, 0), at(12, 0)));
    }

    @Test
    void rejectsABookingFullyInsideAnExistingJob() {
        assertThrows(IllegalStateException.class, () -> service.bookJob(1L, "Inside", at(9, 30), at(10, 30)));
    }

    @Test
    void rejectsABookingThatEnclosesAnExistingJob() {
        assertThrows(IllegalStateException.class, () -> service.bookJob(1L, "Encloses", at(8, 0), at(12, 0)));
    }

    @Test
    void rejectsABookingStartingInsideTheTravelBufferAfterAJob() {
        // Existing job ends 11:00; buffer runs to 11:45. 11:30 is too soon.
        assertThrows(IllegalStateException.class, () -> service.bookJob(1L, "Too soon after", at(11, 30), at(12, 30)));
    }

    @Test
    void rejectsABookingEndingInsideTheTravelBufferBeforeAJob() {
        // Existing job starts 14:00; new job ending 13:30 leaves only 30 min of travel.
        assertThrows(IllegalStateException.class, () -> service.bookJob(1L, "Too soon before", at(12, 30), at(13, 30)));
    }

    @Test
    void allowsABookingExactlyOneTravelBufferAfterAJob() {
        // Existing job ends 11:00; 11:45 leaves exactly 45 minutes, which is allowed.
        Job job = service.bookJob(1L, "Just enough gap", at(11, 45), at(12, 45));
        assertEquals(at(11, 45), job.getStartTime());
    }

    @Test
    void allowsOverlappingTimesForADifferentTechnician() {
        // Technician 1 is busy 09:00-11:00; technician 3 has no jobs and must be unaffected.
        Job job = service.bookJob(3L, "Other tech", at(10, 0), at(12, 0));
        assertEquals(3L, job.getTechnicianId());
    }

    @Test
    void conflictMessageNamesTheConflictingJob() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.bookJob(1L, "Overlap", at(10, 0), at(12, 0)));
        assertTrue(e.getMessage().contains("Jordan Reyes"));
        assertTrue(e.getMessage().contains("45-minute travel buffer"));
    }

    @Test
    void rejectsABookingWhoseEndIsBeforeItsStart() {
        // The reported case: 11:00 to 01:00 (1 AM) slipped past the overlap check.
        assertThrows(InvalidBookingException.class, () -> service.bookJob(3L, "b", at(11, 0), at(1, 0)));
    }

    @Test
    void rejectsABookingWithZeroLength() {
        assertThrows(InvalidBookingException.class, () -> service.bookJob(3L, "zero", at(11, 0), at(11, 0)));
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).withNano(0);
    }

    @Test
    void rejectsABookingAtAnIdenticalStartTimeAsAnExistingJob() {
        LocalDateTime start = LocalDateTime.now().withHour(9).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);
        // Technician 1 already has a seeded job starting at 09:00 (see MockDataStore.seed()).

        assertThrows(IllegalStateException.class, () -> service.bookJob(1L, "Conflicting Customer", start, end));
    }

    @Test
    void throwsForAnUnknownTechnician() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        LocalDateTime end = start.plusHours(1);

        assertThrows(IllegalArgumentException.class, () -> service.bookJob(999L, "Nobody", start, end));
    }
}
