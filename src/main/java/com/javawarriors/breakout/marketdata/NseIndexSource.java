package com.javawarriors.breakout.marketdata;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches the official Nifty 500 constituent list from NSE's public archive
 * (live, not hardcoded — always reflects the current index membership).
 */
public class NseIndexSource {

    private static final String NIFTY_500_URL =
            "https://archives.nseindia.com/content/indices/ind_nifty500list.csv";

    private static final OkHttpClient CLIENT = new OkHttpClient();
    private static final AtomicReference<Map<String, String>> CACHE = new AtomicReference<>(Map.of());

    /** Re-fetches the live list from NSE and updates the cache. Throws on network failure. */
    public static Map<String, String> refreshNifty500() throws IOException {
        Request req = new Request.Builder()
                .url(NIFTY_500_URL)
                .header("User-Agent", "Mozilla/5.0")
                .build();

        Map<String, String> out = new LinkedHashMap<>();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code() + " fetching Nifty 500 list");
            }
            String csv = resp.body().string();
            String[] lines = csv.split("\r?\n");
            // header: Company Name,Industry,Symbol,Series,ISIN Code
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                String[] cols = lines[i].split(",");
                if (cols.length < 3) continue;
                out.put(cols[2].trim() + ".NS", cols[0].trim());
            }
        }
        CACHE.set(out);
        return out;
    }

    /** Last successfully fetched Nifty 500 map (symbol -> company name); empty if never fetched. */
    public static Map<String, String> cached() {
        return CACHE.get();
    }
}
