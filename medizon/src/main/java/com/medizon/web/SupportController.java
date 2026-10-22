package com.medizon.web;

import com.medizon.domain.Order;
import com.medizon.domain.SupportTicket;
import com.medizon.domain.TicketAttachment;
import com.medizon.domain.TicketTopic;
import com.medizon.domain.User;
import com.medizon.service.BusinessException;
import com.medizon.service.CurrentUser;
import com.medizon.service.OrderService;
import com.medizon.service.SupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * Help & support for customers: quick answers from the assistant, raising a ticket (optionally about an order, with
 * photos), and following each ticket's conversation. Ticket photos are also served here to the staff on the ticket.
 */
@Controller
@RequestMapping("/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService support;
    private final OrderService orderService;
    private final CurrentUser currentUser;

    /**
     * With {@code order} (from an order's "Get help"), a ticket raised from here is about that order. With {@code q},
     * only the tickets whose number matches.
     */
    @GetMapping
    public String index(@RequestParam(required = false) Long order, @RequestParam(required = false) String q, Model model) {
        if (order != null) model.addAttribute("order", orderService.getForUser(order, currentUser.get()));
        List<SupportTicket> all = support.forCustomer(currentUser.get()).stream().filter(t -> SupportService.numberMatches(t, q)).toList();
        model.addAttribute("q", q == null || q.isBlank() ? null : q.trim());
        model.addAttribute("openTickets", all.stream().filter(t -> !t.getStatus().isClosed()).toList());
        model.addAttribute("closedTickets", all.stream().filter(t -> t.getStatus().isClosed()).toList());
        return "support/index";
    }

    @GetMapping("/new")
    public String form(@RequestParam(required = false) Long order, @RequestParam(required = false) TicketTopic topic, Model model) {
        User me = currentUser.get();
        Order o = order == null ? null : orderService.getForUser(order, me);
        return form(model, topic != null ? topic : o != null ? TicketTopic.ORDER : null, o == null ? null : o.getId(), null, null);
    }

    private String form(Model model, TicketTopic topic, Long orderId, String subject, String description) {
        model.addAttribute("topics", TicketTopic.values());
        model.addAttribute("topic", topic);
        model.addAttribute("orderId", orderId);
        model.addAttribute("subject", subject);
        model.addAttribute("description", description);
        model.addAttribute("orders", orderService.forUser(currentUser.get()).stream().limit(20).toList());
        model.addAttribute("maxImages", SupportService.MAX_IMAGES);
        return "support/new";
    }

    /** A problem with the form shows it again with what was typed, rather than losing a long description. */
    @PostMapping("/new")
    public String create(@RequestParam(required = false) TicketTopic topic, @RequestParam(required = false) String subject,
                         @RequestParam(required = false) String description, @RequestParam(required = false) Long orderId,
                         @RequestParam(required = false) List<MultipartFile> images, Model model, RedirectAttributes ra) {
        SupportTicket t;
        try {
            t = support.create(currentUser.get(), topic, subject, description, orderId, images);
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return form(model, topic, orderId, subject, description);
        }
        ra.addFlashAttribute("success", "Ticket " + t.getNumber() + " raised. We'll reply here, and let you know when we do.");
        return "redirect:/support/tickets/" + t.getId();
    }

    @GetMapping("/tickets/{id}")
    public String ticket(@PathVariable Long id, Model model) {
        model.addAttribute("t", support.getForCustomer(id, currentUser.get()));
        model.addAttribute("maxImages", SupportService.MAX_IMAGES);
        model.addAttribute("now", java.time.LocalDateTime.now());
        return "support/ticket";
    }

    /** For the open ticket page to notice a new reply without reloading. */
    @GetMapping("/tickets/{id}/latest")
    @ResponseBody
    public Map<String, Object> latest(@PathVariable Long id) {
        SupportTicket t = support.getForCustomer(id, currentUser.get());
        return Map.of("latest", t.getLatestCustomerMessageId(), "status", t.getStatus().name());
    }

    @PostMapping("/tickets/{id}/reply")
    public String reply(@PathVariable Long id, @RequestParam(required = false) String body,
                        @RequestParam(required = false) List<MultipartFile> images, RedirectAttributes ra) {
        support.customerReply(id, currentUser.get(), body, images);
        return "redirect:/support/tickets/" + id + "#latest";
    }

    @PostMapping("/tickets/{id}/reopen")
    public String reopen(@PathVariable Long id, @RequestParam(required = false) String reason, RedirectAttributes ra) {
        support.reopen(id, currentUser.get(), reason);
        ra.addFlashAttribute("success", "Ticket reopened. Our support team will pick it up again.");
        return "redirect:/support/tickets/" + id + "#latest";
    }

    @PostMapping("/tickets/{id}/rate")
    public String rate(@PathVariable Long id, @RequestParam(required = false) Integer rating,
                       @RequestParam(required = false) String comment, RedirectAttributes ra) {
        support.rate(id, currentUser.get(), rating, comment);
        ra.addFlashAttribute("success", "Thanks for rating your experience.");
        return "redirect:/support/tickets/" + id;
    }

    /** A photo on a ticket: for the customer who owns it and the staff who can see the ticket; anyone else gets 404. */
    @GetMapping("/attachments/{messageId}/{index}")
    public ResponseEntity<Resource> attachment(@PathVariable Long messageId, @PathVariable int index) {
        TicketAttachment a = support.attachment(messageId, index, currentUser.get());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.getContentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-photo-" + messageId + "-" + (index + 1) + "\"")
                .body(support.file(a));
    }
}
