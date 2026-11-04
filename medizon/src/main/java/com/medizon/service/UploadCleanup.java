package com.medizon.service;

import com.medizon.domain.PrescriptionStatus;
import com.medizon.domain.ReturnStatus;
import com.medizon.domain.TicketStatus;
import com.medizon.repository.PrescriptionRepository;
import com.medizon.repository.ReturnRequestRepository;
import com.medizon.repository.TicketMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Once a day, deletes uploaded files nobody needs any more, so the upload folder doesn't keep growing:
 * <ul>
 *   <li>prescriptions rejected more than a day ago, removed ones, and used-up ones (those are also deleted the
 *       moment they're used up);</li>
 *   <li>return photos a day after the return is rejected or refunded;</li>
 *   <li>ticket photos a day after the ticket is closed;</li>
 *   <li>files no record points to (an upload that never got saved).</li>
 * </ul>
 * Drafts, prescriptions awaiting review or still approved, open returns and open tickets keep their files.
 * Medicine images are never touched. Records stay in the database; their file links show "Deleted after use".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCleanup {

    /** How long a file is kept after it stops being needed, and the minimum age of any file deleted. */
    static final Duration GRACE = Duration.ofHours(24);

    private final FileStorageService storage;
    private final PrescriptionRepository prescriptions;
    private final ReturnRequestRepository returns;
    private final TicketMessageRepository ticketMessages;

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional(readOnly = true)
    public void daily() {
        try {
            int deleted = run(Instant.now());
            if (deleted > 0) log.info("Upload cleanup deleted {} file(s) no longer needed", deleted);
        } catch (IOException e) {
            log.warn("Upload cleanup couldn't read the upload folder: {}", e.getMessage());
        }
    }

    /** Deletes what's no longer needed as of {@code now}; returns how many files went. */
    int run(Instant now) throws IOException {
        LocalDateTime since = LocalDateTime.ofInstant(now.minus(GRACE), ZoneId.systemDefault());
        // Read what's still needed first: if the database can't be read, this throws and nothing is deleted
        Set<String> keep = new HashSet<>();
        keep.addAll(prescriptions.fileNamesInUse(
                EnumSet.of(PrescriptionStatus.DRAFT, PrescriptionStatus.PENDING, PrescriptionStatus.APPROVED),
                PrescriptionStatus.REJECTED, since));
        keep.addAll(returns.photoNamesInUse(
                EnumSet.of(ReturnStatus.REQUESTED, ReturnStatus.APPROVED, ReturnStatus.PICKED_UP),
                ReturnStatus.REJECTED, ReturnStatus.REFUNDED, since));
        keep.addAll(ticketMessages.photoNamesInUse(TicketStatus.CLOSED, since));

        // Only files older than a day: one uploaded moments ago may not have its record saved yet
        List<String> candidates = storage.privateFilesOlderThan(now.minus(GRACE));
        int deleted = 0;
        for (String name : candidates) {
            if (keep.contains(name)) continue;
            storage.delete(name);
            deleted++;
        }
        return deleted;
    }
}
