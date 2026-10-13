package com.medizon.web;

import com.medizon.domain.Address;
import com.medizon.domain.AddressType;
import com.medizon.domain.User;
import com.medizon.dto.AddressForm;
import com.medizon.service.AddressService;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeliveryAreaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/** Saved delivery addresses: home, office or other, for the customer or for someone else. */
@Controller
@RequestMapping("/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;
    private final DeliveryAreaService deliveryAreas;
    private final DeliveryLocations deliveryLocations;
    private final CurrentUser currentUser;

    @GetMapping
    public String list(Model model) {
        User user = currentUser.get();
        var all = addressService.list(user);
        Map<Long, Boolean> serviceable = new HashMap<>();
        all.forEach(a -> serviceable.put(a.getId(), deliveryAreas.isServiceable(a.getPincode())));
        model.addAttribute("addresses", all);
        model.addAttribute("serviceable", serviceable);
        model.addAttribute("max", AddressService.MAX_ADDRESSES);
        return "addresses/list";
    }

    @GetMapping("/new")
    public String create(@RequestParam(required = false) String returnTo, Model model) {
        return form(model, AddressForm.blank(currentUser.get()), returnTo);
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, @RequestParam(required = false) String returnTo, Model model) {
        return form(model, AddressForm.from(addressService.get(id, currentUser.get())), returnTo);
    }

    /** {@code returnTo=checkout}: opened from checkout, so saving and cancelling go back there. */
    private String form(Model model, AddressForm form, String returnTo) {
        model.addAttribute("form", form);
        model.addAttribute("types", AddressType.values());
        model.addAttribute("fromCheckout", "checkout".equals(returnTo));
        return "addresses/form";
    }

    /** After saving from checkout, the new address is the one selected there and in the header. */
    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") AddressForm form, BindingResult result,
                       @RequestParam(required = false) String returnTo, Model model, RedirectAttributes ra,
                       HttpServletRequest request, HttpServletResponse response) {
        if (form.getType() == AddressType.OTHER && (form.getCustomLabel() == null || form.getCustomLabel().isBlank())) {
            result.rejectValue("customLabel", "required", "Give this address a name, like \"Mom's place\"");
        }
        if (result.hasErrors()) {
            model.addAttribute("types", AddressType.values());
            model.addAttribute("fromCheckout", "checkout".equals(returnTo));
            return "addresses/form";
        }
        boolean isNew = form.getId() == null;
        Address a = addressService.save(currentUser.get(), form);
        deliveryLocations.chooseAddress(a, request, response);
        String note = deliveryAreas.isServiceable(a.getPincode()) ? ""
                : " We don't deliver to PIN code " + a.getPincode() + " yet, so it can't be used for orders.";
        ra.addFlashAttribute(note.isEmpty() ? "success" : "error", (isNew ? "Address saved." : "Address updated.") + note);
        return "checkout".equals(returnTo) ? "redirect:/checkout?address=" + a.getId() : "redirect:/addresses";
    }

    @PostMapping("/{id}/default")
    public String setDefault(@PathVariable Long id, RedirectAttributes ra) {
        Address a = addressService.setDefault(id, currentUser.get());
        ra.addFlashAttribute("success", a.getLabel() + " is now your default address. Automatic refills go there.");
        return "redirect:/addresses";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        Address a = addressService.delete(id, currentUser.get());
        ra.addFlashAttribute("success", a.getLabel() + " address removed.");
        return "redirect:/addresses";
    }
}
