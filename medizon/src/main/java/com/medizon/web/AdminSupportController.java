package com.medizon.web;

import com.medizon.domain.Role;
import com.medizon.domain.SupportTicket;
import com.medizon.domain.TicketTopic;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.SupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * The support desk for staff. Support works the queue of new and solved tickets and passes tickets to teams; each
 * team (pharmacy, payments, a store, the admin) sees the tickets passed to it and hands them back once solved. Who
 * may do what on a ticket is decided in {@link SupportService}.
 */
@Controller
@RequestMapping("/admin/support")
@RequiredArgsConstructor
public class AdminSupportController {

    private final SupportService support;
    private final CurrentUser currentUser;

    /** A team in the "Pass to a team" list. */
    public record TeamOption(Role role, String label) {}

    @GetMapping
    public String list(@RequestParam(defaultValue = "queue") String view, @RequestParam(required = false) TicketTopic topic,
                       Model model) {
        User me = currentUser.get();
        String v = List.of("queue", "teams", "closed", "all").contains(view) ? view : "queue";
        model.addAttribute("tickets", support.staffList(me, v, topic));
        model.addAttribute("counts", support.staffCounts(me));
        model.addAttribute("view", v);
        model.addAttribute("topic", topic);
        model.addAttribute("topics", TicketTopic.values());
        model.addAttribute("seesAll", me.isAdmin() || me.is(Role.SUPPORT));
        model.addAttribute("averageRating", support.averageRating());
        model.addAttribute("active", "support");
        return "admin/support";
    }

    @GetMapping("/{id}")
    public String ticket(@PathVariable Long id, Model model) {
        User me = currentUser.get();
        SupportTicket t = support.getForStaff(id, me);
        model.addAttribute("t", t);
        model.addAttribute("canReply", SupportService.canReply(me, t));
        model.addAttribute("canNote", SupportService.canNote(me, t));
        model.addAttribute("canRedirect", SupportService.canRedirect(me, t));
        model.addAttribute("canHandBack", SupportService.canHandBack(me, t));
        model.addAttribute("canClose", SupportService.canClose(me, t));
        model.addAttribute("teams", SupportService.teamsFor(t).stream().map(r -> new TeamOption(r, SupportTicket.teamLabel(r))).toList());
        model.addAttribute("closingMessage", SupportService.CLOSING_MESSAGE);
        model.addAttribute("maxImages", SupportService.MAX_IMAGES);
        model.addAttribute("active", "support");
        return "admin/support-ticket";
    }

    @GetMapping("/{id}/latest")
    @ResponseBody
    public Map<String, Object> latest(@PathVariable Long id) {
        SupportTicket t = support.getForStaff(id, currentUser.get());
        return Map.of("latest", t.getLatestMessageId(), "status", t.getStatus().name());
    }

    @PostMapping("/{id}/reply")
    public String reply(@PathVariable Long id, @RequestParam(required = false) String body,
                        @RequestParam(defaultValue = "false") boolean internal,
                        @RequestParam(required = false) List<MultipartFile> images, RedirectAttributes ra) {
        support.staffReply(id, currentUser.get(), body, images, internal);
        if (internal) ra.addFlashAttribute("success", "Note added. Only staff can see it.");
        return "redirect:/admin/support/" + id + "#latest";
    }

    @PostMapping("/{id}/redirect")
    public String redirect(@PathVariable Long id, @RequestParam(required = false) Role team,
                           @RequestParam(required = false) String note, RedirectAttributes ra) {
        support.redirect(id, currentUser.get(), team, note);
        ra.addFlashAttribute("success", "Passed to the " + SupportTicket.teamLabel(team).toLowerCase()
                + ". The customer has been told. You can still follow the ticket here.");
        return "redirect:/admin/support/" + id;
    }

    @PostMapping("/{id}/hand-back")
    public String handBack(@PathVariable Long id, @RequestParam(required = false) String note, RedirectAttributes ra) {
        support.handBack(id, currentUser.get(), note);
        ra.addFlashAttribute("success", "Handed back to support to close with the customer.");
        return "redirect:/admin/support";
    }

    @PostMapping("/{id}/close")
    public String close(@PathVariable Long id, @RequestParam(required = false) String message, RedirectAttributes ra) {
        support.close(id, currentUser.get(), message);
        ra.addFlashAttribute("success", "Ticket closed. The customer can rate it, or reopen it within "
                + SupportTicket.REOPEN_DAYS + " days.");
        return "redirect:/admin/support/" + id;
    }
}
