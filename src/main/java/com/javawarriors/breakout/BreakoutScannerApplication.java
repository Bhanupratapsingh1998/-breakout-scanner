package com.javawarriors.breakout;

import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.NseIndexSource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@SpringBootApplication
public class BreakoutScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BreakoutScannerApplication.class, args);
    }

    /** Warms the Nifty 500 name/membership cache and all three benchmark indices on startup, so
     *  custom lookups are correctly tagged and the outperformance check works before the first full scan. */
    @Component
    static class Nifty500Warmup {
        @EventListener(ApplicationReadyEvent.class)
        public void warmUp() {
            Thread t = new Thread(() -> {
                try {
                    NseIndexSource.refreshNifty500();
                } catch (IOException ignored) {
                    // fine — falls back to CUSTOM tagging until a scan or retry succeeds
                }
                BenchmarkSource.refreshAll(); // best-effort per index
            }, "nifty-warmup");
            t.setDaemon(true);
            t.start();
        }
    }
}
