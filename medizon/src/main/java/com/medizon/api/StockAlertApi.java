package com.medizon.api;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.StockAlertService;
import com.medizon.web.DeliveryLocations;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** The "Notify me" buttons on out-of-stock products turn back-in-stock alerts on and off through here. */
@RestController
@RequestMapping("/api/stock-alerts")
@RequiredArgsConstructor
public class StockAlertApi {

    private final StockAlertService stockAlerts;
    private final CurrentUser currentUser;
    private final DeliveryLocations deliveryLocations;

    public record AlertRequest(boolean on) {}

    @PostMapping("/{medicineId}")
    public Map<String, Object> set(@PathVariable Long medicineId, @RequestBody AlertRequest req, HttpServletRequest request) {
        User u = currentUser.get();
        if (req.on()) stockAlerts.subscribe(u, medicineId, deliveryLocations.pincode(request));
        else stockAlerts.unsubscribe(u, medicineId);
        return Map.of("medicineId", medicineId, "on", req.on());
    }
}
