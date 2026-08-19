package com.ledgerintegrity.platform.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The API has no UI at its root; answer with service status instead of a 404 page. */
@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, String> home() {
        return Map.of(
                "service", "Ledger Integrity & Audit Intelligence Platform API",
                "status", "up",
                "note", "This is the backend API. The web app is served separately; endpoints live under /api/**.");
    }
}
