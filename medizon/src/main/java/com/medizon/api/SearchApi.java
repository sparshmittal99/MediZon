package com.medizon.api;

import com.medizon.domain.IdMask;
import com.medizon.dto.Product;
import com.medizon.service.CatalogService;
import com.medizon.web.DeliveryLocations;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Suggestions for the search box while the shopper types, priced and stocked for their delivery location. Open to
 * visitors, like the shop. Links are relative to the site (the page adds its base) and use masked ids.
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchApi {

    private static final int MAX_QUERY = 80;
    private static final int LIMIT = 8;

    private final CatalogService catalog;
    private final DeliveryLocations deliveryLocations;

    @GetMapping("/suggest")
    public Map<String, Object> suggest(@RequestParam(defaultValue = "") String q, HttpServletRequest request) {
        String query = q.length() > MAX_QUERY ? q.substring(0, MAX_QUERY) : q;
        CatalogService.Suggestions s = catalog.suggest(query, deliveryLocations.pincode(request), LIMIT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", query);
        out.put("medicines", s.products().stream().map(SearchApi::item).toList());
        out.put("categories", s.categories());
        out.put("didYouMean", s.didYouMean());
        return out;
    }

    private static Map<String, Object> item(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        String sub = List.of(nz(p.getGenericName()), nz(p.getPackLabel())).stream()
                .filter(x -> !x.isBlank()).reduce((a, b) -> a + " · " + b).orElse("");
        m.put("sub", sub);
        m.put("category", p.getCategory());
        m.put("url", "medicines/" + IdMask.encode(p.getId(), "medicine"));
        m.put("image", p.getImageUrl() == null || p.getImageUrl().isBlank() ? null : p.getImageUrl().replaceFirst("^/", ""));
        m.put("icon", p.getIcon());
        boolean listed = !p.isUnavailable();
        m.put("price", listed && p.getPrice() != null ? p.getPrice().stripTrailingZeros().toPlainString() : null);
        m.put("mrp", listed && p.getMrp() != null && p.getPrice() != null && p.getMrp().compareTo(p.getPrice()) > 0
                ? p.getMrp().stripTrailingZeros().toPlainString() : null);
        m.put("inStock", listed && p.isInStock());
        m.put("rx", p.isPrescriptionRequired());
        return m;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
