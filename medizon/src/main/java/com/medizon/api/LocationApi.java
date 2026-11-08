package com.medizon.api;

import com.medizon.domain.Address;
import com.medizon.domain.User;
import com.medizon.geo.GeoPlace;
import com.medizon.geo.GeoService;
import com.medizon.service.AddressService;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeliveryAreaService;
import com.medizon.web.DeliveryLocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Delivery location and address lookups. The header's picker checks whether MediZon delivers to a PIN code and
 * remembers the choice; the picker and the address form look up addresses here (suggestions while typing, the
 * address at a map pin, a PIN code's city and areas), so the geocoder keys stay on the server.
 */
@RestController
@RequestMapping("/api/location")
@RequiredArgsConstructor
public class LocationApi {

    private final DeliveryLocations locations;
    private final CurrentUser currentUser;
    private final AddressService addressService;
    private final GeoService geo;

    /** Either a saved address of the signed-in customer, or a PIN code with an optional name for it. */
    public record LocationRequest(String pincode, String label, Long addressId) {}

    /** Whether a PIN code is delivered to, without saving it. */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestParam String pincode, HttpServletRequest request) {
        if (DeliveryAreaService.normalize(pincode) == null) return invalid(request);
        return ResponseEntity.ok(body(locations.locate(pincode, null)));
    }

    /** Suggestions while typing an address: buildings, landmarks, streets and areas in India. */
    @GetMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        if (!geo.allow(client(request))) return tooMany(request);
        return ResponseEntity.ok(Map.of("places", geo.suggest(q).stream().map(this::place).toList()));
    }

    /** The address at a map pin or the device's location, with whether we deliver there. */
    @GetMapping("/reverse")
    public ResponseEntity<?> reverse(@RequestParam double lat, @RequestParam double lon, HttpServletRequest request) {
        if (!inIndia(lat, lon)) return ApiErrors.of(HttpStatus.BAD_REQUEST, "That spot is outside India.", request);
        if (!geo.allow(client(request))) return tooMany(request);
        return geo.reverse(lat, lon)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of("place", place(p))))
                .orElseGet(() -> ApiErrors.of(HttpStatus.NOT_FOUND, "Couldn't find an address here. Move the pin a little.", request));
    }

    /** A PIN code's city, state and post office localities (India Post), and whether we deliver there. */
    @GetMapping("/pincode")
    public ResponseEntity<?> pincode(@RequestParam String pincode, HttpServletRequest request) {
        String pin = DeliveryAreaService.normalize(pincode);
        if (pin == null) return invalid(request);
        if (!geo.allow(client(request))) return tooMany(request);
        Map<String, Object> out = new HashMap<>();
        out.put("pincode", pin);
        out.put("serviceable", locations.locate(pin, null).isServiceable());
        geo.pincode(pin).ifPresentOrElse(info -> {
            out.put("known", true);
            out.put("city", nz(info.city()));
            out.put("state", nz(info.state()));
            out.put("localities", info.localities());
        }, () -> out.put("known", false));
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> place(GeoPlace p) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", p.title());
        m.put("subtitle", nz(p.subtitle()));
        m.put("lat", p.lat());
        m.put("lon", p.lon());
        m.put("building", nz(p.building()));
        m.put("road", nz(p.road()));
        m.put("area", nz(p.area()));
        m.put("city", nz(p.city()));
        m.put("state", nz(p.state()));
        m.put("pincode", nz(p.pincode()));
        if (p.partial()) m.put("partial", true);
        if (p.pincode() != null) m.put("serviceable", locations.locate(p.pincode(), null).isServiceable());
        return m;
    }

    /** Roughly India's bounding box, so the geocoders aren't asked about the rest of the world. */
    static boolean inIndia(double lat, double lon) {
        return lat >= 6 && lat <= 37.5 && lon >= 68 && lon <= 97.5;
    }

    /** The visitor's address: Render puts it first in X-Forwarded-For. */
    private static String client(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static ResponseEntity<Map<String, Object>> tooMany(HttpServletRequest request) {
        return ApiErrors.of(HttpStatus.TOO_MANY_REQUESTS, "Too many address lookups. Wait a few minutes or type the address.", request);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> choose(@RequestBody LocationRequest req,
                                                      HttpServletRequest request, HttpServletResponse response) {
        if (req.addressId() != null) {
            User user = currentUser.find().orElse(null);
            if (user == null) return ApiErrors.of(HttpStatus.UNAUTHORIZED, "Sign in to use your saved addresses.", request);
            Address a = addressService.find(req.addressId(), user).orElse(null);
            // Someone else's address answers the same as one that doesn't exist
            if (a == null) return ApiErrors.of(HttpStatus.NOT_FOUND, "That address isn't saved on your account.", request);
            return ResponseEntity.ok(body(locations.chooseAddress(a, request, response)));
        }
        if (DeliveryAreaService.normalize(req.pincode()) == null) return invalid(request);
        String label = req.label();
        if (label == null || label.isBlank()) {
            // A bare PIN code: name it after its area (India Post) unless admins named it
            if (locations.locate(req.pincode(), null).getLabel() == null) {
                label = geo.pincode(req.pincode())
                        .map(i -> i.localities().size() == 1 ? i.localities().get(0) : i.city())
                        .orElse(null);
            }
        }
        DeliveryLocations.Location loc = locations.locate(req.pincode(), label);
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

    private static ResponseEntity<Map<String, Object>> invalid(HttpServletRequest request) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "Enter a valid 6 digit PIN code.", request);
    }
}
