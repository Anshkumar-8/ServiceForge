package com.serviceforge.service;

import com.serviceforge.data.MockDataStore;
import com.serviceforge.model.Job;
import com.serviceforge.model.JobStatus;
import com.serviceforge.model.Technician;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Feature 1 — Technician Availability Calendar.
 *
 * See pipeline/features/feature-1-technician-availability.md for the spec this was built against,
 * and pipeline/decisions/feature-1-decisions.md for the decisions referenced below.
 */
@Service
public class TechnicianAvailabilityService {

    /**
     * Decision (see pipeline/decisions/feature-1-decisions.md): every booked job reserves an
     * additional 45-minute travel buffer on top of its own start/end time, to account for the
     * technician getting to the next job. This is what actually limits how many jobs can be
     * scheduled for one technician in a day — anything reasoning about daily capacity needs
     * this number, not a guess.
     */
    public static final int TRAVEL_BUFFER_MINUTES = 45;

    private final MockDataStore dataStore;

    public TechnicianAvailabilityService(MockDataStore dataStore) {
        this.dataStore = dataStore;
    }

    public List<Technician> getAllTechnicians() {
        return dataStore.getAllTechnicians();
    }

    public Optional<Technician> findTechnician(Long technicianId) {
        return dataStore.findTechnician(technicianId);
    }

    public List<Job> getJobsForTechnician(Long technicianId) {
        return dataStore.getJobsForTechnician(technicianId);
    }

    /**
     * Books a new job for a technician.
     *
     * Rejects (IllegalStateException -> 409) any booking whose time range, padded by the
     * travel buffer, overlaps an existing non-cancelled job for the same technician.
     * Each job blocks [start, end + TRAVEL_BUFFER_MINUTES], so two jobs must be at least
     * TRAVEL_BUFFER_MINUTES apart (exactly that gap is allowed).
     */
    public Job bookJob(Long technicianId, String customerName, LocalDateTime startTime, LocalDateTime endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new InvalidBookingException(
                    "End time " + endTime + " must be after start time " + startTime);
        }

        Technician technician = dataStore.findTechnician(technicianId)
                .orElseThrow(() -> new IllegalArgumentException("No technician with id " + technicianId));

        List<Job> existingJobs = dataStore.getJobsForTechnician(technicianId);

        Optional<Job> conflict = existingJobs.stream()
                .filter(existing -> existing.getStatus() != JobStatus.CANCELLED)
                .filter(existing -> overlapsWithBuffer(existing, startTime, endTime))
                .findFirst();

        if (conflict.isPresent()) {
            Job existing = conflict.get();
            throw new IllegalStateException(
                    "Technician " + technician.getName() + " already has a job from "
                            + existing.getStartTime() + " to " + existing.getEndTime()
                            + " that conflicts with " + startTime + " to " + endTime
                            + " (including a " + TRAVEL_BUFFER_MINUTES + "-minute travel buffer)");
        }

        Job job = new Job(dataStore.nextJobId(), technicianId, customerName, startTime, endTime, JobStatus.SCHEDULED);
        return dataStore.save(job);
    }

    private boolean overlapsWithBuffer(Job existing, LocalDateTime newStart, LocalDateTime newEnd) {
        Duration buffer = Duration.ofMinutes(TRAVEL_BUFFER_MINUTES);
        return newStart.isBefore(existing.getEndTime().plus(buffer))
                && existing.getStartTime().isBefore(newEnd.plus(buffer));
    }
}
