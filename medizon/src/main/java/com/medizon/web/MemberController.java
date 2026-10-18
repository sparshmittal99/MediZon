package com.medizon.web;

import com.medizon.domain.Member;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.MedicationPlanService;
import com.medizon.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.stream.Collectors;

/** Family members whose medicines the account manages. */
@Controller
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final MedicationPlanService planService;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        User user = currentUser.get();
        Map<Long, Long> planCounts = planService.forUser(user).stream()
                .filter(p -> p.getMember() != null)
                .collect(Collectors.groupingBy(p -> p.getMember().getId(), Collectors.counting()));
        model.addAttribute("people", memberService.people(user));
        model.addAttribute("common", memberService.common(user));
        model.addAttribute("planCounts", planCounts);
        model.addAttribute("maxFamily", MemberService.MAX_FAMILY);
        return "members/list";
    }

    @PostMapping
    public String save(@RequestParam(required = false) Long id, @RequestParam String name,
                       @RequestParam(required = false) String patientName,
                       @RequestParam(required = false) String relation, RedirectAttributes ra) {
        Member m = memberService.save(currentUser.get(), id, name, patientName, relation);
        ra.addFlashAttribute("success", id == null
                ? m.getName() + " added. Add their medicines from My medicines, or choose them in the cart."
                : "Saved.");
        return "redirect:/members";
    }

    @PostMapping("/{id}/remove")
    public String remove(@PathVariable Long id, RedirectAttributes ra) {
        Member m = memberService.remove(id, currentUser.get());
        ra.addFlashAttribute("success", m.getName() + " was removed and their reminders are off.");
        return "redirect:/members";
    }
}
