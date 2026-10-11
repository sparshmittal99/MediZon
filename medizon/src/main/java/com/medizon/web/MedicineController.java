package com.medizon.web;

import com.medizon.domain.Medicine;
import com.medizon.service.BusinessException;
import com.medizon.service.MedicineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class MedicineController {

    private final MedicineService medicineService;

    @GetMapping("/medicines")
    public String list(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "") String category,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("results", medicineService.search(q, category, page));
        model.addAttribute("categories", medicineService.categories());
        model.addAttribute("q", q);
        model.addAttribute("category", category);
        return "medicines/list";
    }

    @GetMapping("/medicines/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Medicine m = medicineService.get(id);
        if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
        model.addAttribute("m", m);
        return "medicines/detail";
    }
}
