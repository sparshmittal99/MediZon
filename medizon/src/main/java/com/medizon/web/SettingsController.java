package com.medizon.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** General settings: dark mode, text size and motion (kept in the browser), plus links to account and alert settings. */
@Controller
public class SettingsController {

    @GetMapping("/settings")
    public String settings() {
        return "settings";
    }
}
