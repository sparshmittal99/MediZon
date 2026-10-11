package com.medizon.web;

import com.medizon.domain.Prescription;
import com.medizon.service.CurrentUser;
import com.medizon.service.PrescriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("prescriptions", prescriptionService.forUser(currentUser.get()));
        return "prescriptions/list";
    }

    @PostMapping
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(required = false) String doctorName,
                         @RequestParam(required = false) String notes,
                         @RequestParam(required = false) String returnTo,
                         RedirectAttributes ra) {
        prescriptionService.upload(currentUser.get(), file, doctorName, notes);
        ra.addFlashAttribute("success", "Prescription uploaded. A pharmacist will review it shortly.");
        return "redirect:" + ("checkout".equals(returnTo) ? "/checkout" : "/prescriptions");
    }

    /** Streams the file to its owner or an admin only. */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long id) {
        Prescription p = prescriptionService.getForViewer(id, currentUser.get());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"prescription-" + p.getId() + "\"")
                .body(prescriptionService.file(p));
    }
}
