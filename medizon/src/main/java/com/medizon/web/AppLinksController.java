package com.medizon.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Android App Links: Android fetches this file to confirm the MediZon app may open links to this site. With the app
 * installed, links in our emails open the app on that page; without it they open in the browser.
 */
@RestController
public class AppLinksController {

    private final String packageName;
    private final List<String> fingerprints;

    public AppLinksController(@Value("${medizon.android.package:in.medizon.app}") String packageName,
                              @Value("${medizon.android.cert-sha256:}") String fingerprints) {
        this.packageName = packageName;
        this.fingerprints = Arrays.stream(fingerprints.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).toList();
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        List<Map<String, Object>> body = fingerprints.isEmpty() ? List.of() : List.of(Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of(
                        "namespace", "android_app",
                        "package_name", packageName,
                        "sha256_cert_fingerprints", fingerprints)));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }
}
