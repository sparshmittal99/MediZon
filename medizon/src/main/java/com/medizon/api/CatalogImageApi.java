package com.medizon.api;

import com.medizon.domain.Medicine;
import com.medizon.service.CatalogImageService;
import com.medizon.service.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Product pictures from a folder, for the admin (master catalogue > Import, catalog-images.js): first the file names
 * are matched to products, then each checked picture is uploaded on its own.
 */
@RestController
@RequestMapping("/api/catalog-images")
@RequiredArgsConstructor
public class CatalogImageApi {

    private final CatalogImageService images;
    private final CurrentUser currentUser;

    public record MatchRequest(List<String> names) {}

    /** The products each file name matches; nothing is changed. */
    @PostMapping("/match")
    public List<CatalogImageService.Match> match(@RequestBody MatchRequest request) {
        return images.match(currentUser.get(), request.names());
    }

    /** Puts one picture on a product; {@code replace} lets the admin change a picture it already has. */
    @PostMapping("/{medicineId}")
    public Map<String, Object> upload(@PathVariable Long medicineId, @RequestParam(required = false) MultipartFile file,
                                      @RequestParam(defaultValue = "false") boolean replace) {
        Medicine m = images.setImage(currentUser.get(), medicineId, file, replace);
        return Map.of("id", m.getId(), "name", m.getName(), "imageUrl", m.getImageUrl());
    }
}
