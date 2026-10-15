package com.medizon.service;

import com.medizon.domain.NotificationType;
import com.medizon.domain.Prescription;
import com.medizon.domain.PrescriptionStatus;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PrescriptionService {

    private final PrescriptionRepository prescriptions;
    private final FileStorageService storage;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;

    @Transactional
    public Prescription upload(User user, MultipartFile file, String doctorName, String notes) {
        Prescription p = new Prescription();
        p.setUser(user);
        p.setStoredFileName(storage.store(file));
        p.setOriginalFileName(file.getOriginalFilename());
        p.setContentType(file.getContentType());
        p.setDoctorName(doctorName);
        p.setNotes(notes);
        prescriptions.save(p);
        staffAlerts.notify(Role.PHARMACIST, NotificationType.PRESCRIPTION_UPDATE, "Prescription to review",
                user.getFullName() + " uploaded a prescription" + (doctorName == null || doctorName.isBlank() ? "." : " from " + doctorName.trim() + "."),
                "/admin/prescriptions");
        return p;
    }

    public List<Prescription> forUser(User user) { return prescriptions.findByUserOrderByUploadedAtDesc(user); }

    public List<Prescription> validForUser(User user) {
        return prescriptions.findByUserAndStatusOrderByReviewedAtDesc(user, PrescriptionStatus.APPROVED)
                .stream().filter(Prescription::isValid).toList();
    }

    public List<Prescription> pending() { return prescriptions.findByStatusOrderByUploadedAtAsc(PrescriptionStatus.PENDING); }

    public List<Prescription> recentlyReviewed() {
        return prescriptions.findTop30ByStatusNotOrderByReviewedAtDesc(PrescriptionStatus.PENDING);
    }

    @Transactional
    public void review(Long id, boolean approve, String note, LocalDate validUntil) {
        Prescription p = prescriptions.findById(id)
                .orElseThrow(() -> new BusinessException("Prescription not found."));
        p.setStatus(approve ? PrescriptionStatus.APPROVED : PrescriptionStatus.REJECTED);
        p.setReviewerNote(note);
        p.setValidUntil(approve ? (validUntil != null ? validUntil : LocalDate.now().plusMonths(6)) : null);
        p.setReviewedAt(LocalDateTime.now());

        String msg = approve
                ? "Your prescription was approved and is valid until " + p.getValidUntil() + ". You can now order prescription medicines."
                : "Your prescription couldn't be approved" + (note == null || note.isBlank() ? "." : ": " + note) + " Upload a clearer copy to continue.";
        notifications.notify(p.getUser(), NotificationType.PRESCRIPTION_UPDATE,
                approve ? "Prescription approved" : "Prescription needs attention", msg, "/prescriptions");
    }

    /** Owners and admins can view a file; anyone else gets "not found". */
    @Transactional(readOnly = true)
    public Prescription getForViewer(Long id, User viewer) {
        Prescription p = prescriptions.findById(id).orElseThrow(() -> new BusinessException("File not found."));
        boolean reviewer = viewer.isAdmin() || viewer.is(Role.PHARMACIST);
        if (!reviewer && !p.getUser().getId().equals(viewer.getId())) {
            throw new BusinessException("File not found.");
        }
        return p;
    }

    public Resource file(Prescription p) { return storage.load(p.getStoredFileName()); }
}
