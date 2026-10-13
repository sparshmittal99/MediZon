package com.medizon.api;

import com.medizon.domain.Address;
import com.medizon.domain.User;
import com.medizon.service.AddressService;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeliveryAreaService;
import com.medizon.web.DeliveryLocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * The header's location picker: checks whether MediZon delivers to a PIN code and remembers the choice. The browser
 * turns a typed address, a pin on the map or the device location into a PIN code first.
 */
@RestController
@RequestMapping("/api/location")
@RequiredArgsConstructor
public class LocationApi {

    private final DeliveryLocations locations;
    private final CurrentUser currentUser;
    private final AddressService addressService;

    /** Either a saved address of the signed-in customer, or a PIN code with an optional name for it. */
    public record LocationRequest(String pincode, String label, Long addressId) {}

    /** Whether a PIN code is delivered to, without saving it. */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestParam String pincode) {
        if (DeliveryAreaService.normalize(pincode) == null) return invalid();
        return ResponseEntity.ok(body(locations.locate(pincode, null)));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> choose(@RequestBody LocationRequest req,
                                                      HttpServletRequest request, HttpServletResponse response) {
        if (req.addressId() != null) {
            User user = currentUser.find().orElse(null);
            if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Sign in to use your saved addresses."));
            Address a = addressService.find(req.addressId(), user).orElse(null);
            if (a == null) return ResponseEntity.badRequest().body(Map.of("error", "That address isn't saved on your account."));
            return ResponseEntity.ok(body(locations.chooseAddress(a, request, response)));
        }
        if (DeliveryAreaService.normalize(req.pincode()) == null) return invalid();
        DeliveryLocations.Location loc = locations.locate(req.pincode(), req.label());
        locations.remember(loc, request, response);
        return ResponseEntity.ok(body(loc));
    }

    private Map<String, Object> body(DeliveryLocations.Location loc) {
        Map<String, Object> out = new HashMap<>();
        out.put("pincode", loc.getPincode());
        out.put("label", loc.getLabel() == null ? "" : loc.getLabel());
        out.put("serviceable", loc.isServiceable());
        return out;
    }

    private static ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid 6 digit PIN code."));
    }
}
