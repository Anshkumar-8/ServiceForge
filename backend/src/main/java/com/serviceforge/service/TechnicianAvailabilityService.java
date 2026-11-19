package com.serviceforge.service;
 
import com.serviceforge.data.MockDataStore;
import com.serviceforge.model.Job;
import com.serviceforge.model.JobStatus;
import com.serviceforge.model.Technician;
import org.springframework.stereotype.Service;
 
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
     */
    public Job bookJob(Long technicianId, String customerName, LocalDateTime startTime, LocalDateTime endTime) {
        Technician technician = dataStore.findTechnician(technicianId)
                .orElseThrow(() -> new IllegalArgumentException("No technician with id " + technicianId));
 
        if (startTime == null) {
            throw new IllegalArgumentException("Job start time is required");
        }
        if (endTime == null || !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("Job end time must be after start time");
        }
 
        List<Job> existingJobs = dataStore.getJobsForTechnician(technicianId);
 
        boolean hasConflict = existingJobs.stream()
                .anyMatch(existing ->
                        startTime.isBefore(existing.getEndTime().plusMinutes(TRAVEL_BUFFER_MINUTES))
                        && existing.getStartTime().isBefore(endTime.plusMinutes(TRAVEL_BUFFER_MINUTES)));
 
        if (hasConflict) {
            throw new IllegalStateException(
                    "Technician " + technician.getName()
                            + " already has a conflicting job at or near " + startTime);
        }
 
        Job job = new Job(dataStore.nextJobId(), technicianId, customerName, startTime, endTime, JobStatus.SCHEDULED);
        return dataStore.save(job);
    }
}