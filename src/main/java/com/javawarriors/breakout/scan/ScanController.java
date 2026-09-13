package com.javawarriors.breakout.scan;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Kicks off a full Nifty 500 reversal rescan in the background. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> triggerScan() {
        boolean started = scanService.triggerScan();
        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "already-running"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"));
    }

    @GetMapping("/scan/status")
    public Map<String, Object> scanStatus() {
        return scanService.status();
    }

    /** Last completed scan's reversal setups — what the Reversal Watch tab renders. */
    @GetMapping("/results")
    public ResponseEntity<Map<String, Object>> results() {
        Map<String, Object> results = scanService.results();
        if (results == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(results);
    }
}
