package com.medizon.service;

import com.medizon.repository.PrescriptionRepository;
import com.medizon.repository.ReturnRequestRepository;
import com.medizon.repository.TicketMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The daily cleanup deletes only old files no record still needs, and never medicine images. */
class UploadCleanupTest {

    @TempDir Path dir;
    private final Instant now = Instant.now();
    private PrescriptionRepository prescriptions;
    private ReturnRequestRepository returns;
    private TicketMessageRepository tickets;
    private UploadCleanup cleanup;

    @BeforeEach
    void setUp() throws Exception {
        FileStorageService storage = new FileStorageService(dir.toString());
        prescriptions = mock(PrescriptionRepository.class);
        returns = mock(ReturnRequestRepository.class);
        tickets = mock(TicketMessageRepository.class);
        when(prescriptions.fileNamesInUse(any(), any(), any())).thenReturn(List.of());
        when(returns.photoNamesInUse(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(tickets.photoNamesInUse(any(), any())).thenReturn(List.of());
        cleanup = new UploadCleanup(storage, prescriptions, returns, tickets);
    }

    private Path file(String name, Duration age) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.from(now.minus(age)));
        return p;
    }

    @Test
    void deletesOldFilesNoLongerNeeded() throws Exception {
        Path usedUp = file("a.pdf", Duration.ofDays(3));
        Path closedReturn = file("returns/b.jpg", Duration.ofDays(3));
        Path closedTicket = file("support/c.jpg", Duration.ofDays(3));

        assertEquals(3, cleanup.run(now));
        assertFalse(Files.exists(usedUp));
        assertFalse(Files.exists(closedReturn));
        assertFalse(Files.exists(closedTicket));
    }

    @Test
    void keepsFilesStillInUse() throws Exception {
        Path pending = file("pending.pdf", Duration.ofDays(3));
        Path openReturn = file("returns/open.jpg", Duration.ofDays(3));
        Path openTicket = file("support/open.jpg", Duration.ofDays(3));
        when(prescriptions.fileNamesInUse(any(), any(), any())).thenReturn(List.of("pending.pdf"));
        when(returns.photoNamesInUse(any(), any(), any(), any(), any())).thenReturn(List.of("returns/open.jpg"));
        when(tickets.photoNamesInUse(any(), any())).thenReturn(List.of("support/open.jpg"));

        assertEquals(0, cleanup.run(now));
        assertTrue(Files.exists(pending));
        assertTrue(Files.exists(openReturn));
        assertTrue(Files.exists(openTicket));
    }

    @Test
    void keepsRecentUploadsAndMedicineImages() throws Exception {
        Path justUploaded = file("new.jpg", Duration.ofHours(2));
        Path productPhoto = file("medicine-images/p.jpg", Duration.ofDays(400));

        assertEquals(0, cleanup.run(now));
        assertTrue(Files.exists(justUploaded));
        assertTrue(Files.exists(productPhoto));
    }

    @Test
    void deletesNothingWhenTheDatabaseCantBeRead() throws Exception {
        Path old = file("a.pdf", Duration.ofDays(3));
        when(prescriptions.fileNamesInUse(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> cleanup.run(now));
        assertTrue(Files.exists(old));
    }
}
