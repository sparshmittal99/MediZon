package com.medizon.web;

import com.medizon.domain.Member;
import com.medizon.domain.Prescription;
import com.medizon.domain.PrescriptionStatus;
import com.medizon.domain.User;
import com.medizon.dto.PrescriptionReading;
import com.medizon.service.BusinessException;
import com.medizon.service.CurrentUser;
import com.medizon.service.MemberService;
import com.medizon.service.PrescriptionCartFiller;
import com.medizon.service.NotFoundException;
import com.medizon.service.PrescriptionScanService;
import com.medizon.service.PrescriptionService;
import jakarta.servlet.http.HttpServletRequest;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/prescriptions")
@RequiredArgsConstructor
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final PrescriptionScanService scanService;
    private final PrescriptionCartFiller cartFiller;
    private final CurrentUser currentUser;
    private final DeliveryLocations deliveryLocations;
    private final MemberService memberService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("prescriptions", prescriptionService.forUser(currentUser.get()));
        model.addAttribute("aiEnabled", scanService.isEnabled());
        model.addAttribute("maxFiles", PrescriptionService.MAX_FILES);
        return "prescriptions/list";
    }

    /**
     * One or more files, one prescription each (e.g. for several family members). They're saved as drafts and read by
     * the AI; the customer then checks them on the review page before they go to the pharmacist.
     */
    @PostMapping
    public String upload(@RequestParam("file") List<MultipartFile> files,
                         @RequestParam(required = false) String returnTo) {
        List<MultipartFile> chosen = files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (chosen.isEmpty()) throw new BusinessException("Choose a prescription file to upload.");
        if (chosen.size() > PrescriptionService.MAX_FILES) {
            throw new BusinessException("Upload up to " + PrescriptionService.MAX_FILES + " prescriptions at a time.");
        }
        User user = currentUser.get();
        List<Long> ids = chosen.stream().map(f -> prescriptionService.upload(user, f).getId()).toList();
        scanService.scanAll(ids);              // a no-op when the AI is off; never fails the upload
        return "redirect:/prescriptions/review" + ("checkout".equals(returnTo) ? "?returnTo=checkout" : "");
    }

    /** The customer's uploads that haven't been sent yet: what was read, whose they are, remove or send. */
    @GetMapping("/review")
    public String review(@RequestParam(required = false) String returnTo, Model model) {
        User user = currentUser.get();
        List<Prescription> drafts = prescriptionService.drafts(user);
        if (drafts.isEmpty()) return "checkout".equals(returnTo) ? "redirect:/checkout" : "redirect:/prescriptions";
        Map<Long, PrescriptionReading> readings = new HashMap<>();
        Map<Long, String> goesTo = new HashMap<>();
        drafts.forEach(p -> {
            scanService.reading(p).ifPresent(r -> readings.put(p.getId(), r));
            if (p.getPatientName() != null && !p.getPatientName().isBlank()) {
                goesTo.put(p.getId(), memberService.byPatientName(user, p.getPatientName()).map(Member::getLabel)
                        .orElse("Others (not in your family members)"));
            }
        });
        model.addAttribute("drafts", drafts);
        model.addAttribute("readings", readings);
        model.addAttribute("goesTo", goesTo);
        model.addAttribute("aiEnabled", scanService.isEnabled());
        model.addAttribute("returnTo", "checkout".equals(returnTo) ? "checkout" : null);
        return "prescriptions/review";
    }

    /** Sends the drafts to the pharmacist, with the doctor's name and notes the customer added to each. */
    @PostMapping("/review")
    public String send(@RequestParam(name = "id", required = false) List<Long> ids, @RequestParam Map<String, String> form,
                       @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) throw new BusinessException("There's nothing to send.");
        Map<Long, PrescriptionService.Note> notes = new LinkedHashMap<>();
        ids.forEach(id -> notes.put(id, new PrescriptionService.Note(form.get("doctorName_" + id), form.get("notes_" + id))));
        List<Prescription> sent = prescriptionService.submit(currentUser.get(), notes);
        ra.addFlashAttribute("success", (sent.size() == 1 ? "Prescription sent" : sent.size() + " prescriptions sent")
                + " to the pharmacist. Once approved, the medicines go into your cart and we'll let you know.");
        if ("checkout".equals(returnTo)) return "redirect:/checkout";
        return sent.size() == 1 ? "redirect:/prescriptions/" + sent.get(0).getId() : "redirect:/prescriptions";
    }

    /** Removes an upload that hasn't been sent, e.g. the wrong file. */
    @PostMapping("/{id}/discard")
    public String discard(@PathVariable Long id, @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        prescriptionService.remove(currentUser.get(), id);
        ra.addFlashAttribute("success", "Removed. It wasn't sent to the pharmacist.");
        return "redirect:/prescriptions/review" + ("checkout".equals(returnTo) ? "?returnTo=checkout" : "");
    }

    /** Removes any of the customer's prescriptions, sent, approved or not (see {@link PrescriptionService#remove}). */
    @PostMapping("/{id}/remove")
    public String remove(@PathVariable Long id, RedirectAttributes ra) {
        boolean kept = prescriptionService.remove(currentUser.get(), id);
        ra.addFlashAttribute("success", kept
                ? "Removed from your prescriptions. It was used for past orders, so the pharmacy keeps a copy for its records; it won't be used again."
                : "Prescription removed.");
        return "redirect:/prescriptions";
    }

    /** What the AI read, matched to products the customer can add to the cart. */
    @GetMapping("/{id}")
    public String view(@PathVariable Long id, HttpServletRequest request, Model model) {
        Prescription p = own(id);
        if (p.getStatus() == PrescriptionStatus.DRAFT) return "redirect:/prescriptions/review";
        Optional<PrescriptionReading> reading = scanService.reading(p);
        model.addAttribute("p", p);
        model.addAttribute("aiEnabled", scanService.isEnabled());
        model.addAttribute("reading", reading.orElse(null));
        reading.ifPresent(r -> {
            List<PrescriptionScanService.Suggestion> s = scanService.suggestions(r, deliveryLocations.pincode(request));
            model.addAttribute("suggestions", s);
            model.addAttribute("addable", s.stream().filter(PrescriptionScanService.Suggestion::canAdd).count());
        });
        return "prescriptions/detail";
    }

    @PostMapping("/{id}/scan")
    public String rescan(@PathVariable Long id) {
        scanService.scan(own(id).getId());
        return "redirect:/prescriptions/" + id;
    }

    /** An approved prescription's medicines, up to the packs still left on it, into the cart. */
    @PostMapping("/{id}/cart")
    public String addToCart(@PathVariable Long id, RedirectAttributes ra) {
        Prescription p = own(id);
        if (!p.isValid() || !p.isLimited()) throw new BusinessException("Only an approved prescription's medicines can be added from here.");
        PrescriptionCartFiller.Result r = cartFiller.addRemainingToCart(id);
        if (!r.added().isEmpty()) ra.addFlashAttribute("success", "Added to your cart: " + String.join(", ", r.added()) + ".");
        if (!r.skipped().isEmpty()) ra.addFlashAttribute("error", String.join(" ", r.skipped()));
        if (r.added().isEmpty() && r.skipped().isEmpty()) ra.addFlashAttribute("success", "Everything on this prescription has been ordered.");
        return r.added().isEmpty() ? "redirect:/prescriptions/" + id : "redirect:/cart";
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

    /** The customer's own prescription; anyone else's is "not found". */
    private Prescription own(Long id) {
        User u = currentUser.get();
        Prescription p = prescriptionService.getForViewer(id, u);
        if (!p.getUser().getId().equals(u.getId()) || p.isRemoved()) throw new NotFoundException("Prescription not found.");
        return p;
    }
}
