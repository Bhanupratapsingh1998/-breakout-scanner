package com.javawarriors.breakout.scan;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * What is left of the scan API after the Reversal Watch tab was removed: a health check.
 *
 * <p>The scan, status and results endpoints went with the tab. {@code ScanRunner} survives, but
 * only for its universe helpers - the Index 500 and watchlist features build their symbol lists
 * from it.
 */
@RestController
@RequestMapping("/api")
public class ScanController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

}
