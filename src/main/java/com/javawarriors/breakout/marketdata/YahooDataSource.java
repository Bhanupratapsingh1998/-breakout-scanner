package com.javawarriors.breakout.marketdata;

import com.javawarriors.breakout.model.Bar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches daily OHLCV from Yahoo Finance's public chart endpoint.
 * NSE symbols use the .NS suffix (e.g. INGERRAND.NS), BSE uses .BO.
 */
public class YahooDataSource {

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Bar> fetchDaily(String symbol, String range) throws IOException {
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                + symbol + "?range=" + range + "&interval=1d";

        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")  // Yahoo rejects default UA
                .build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code() + " for " + symbol);
            }
            JsonNode root = mapper.readTree(resp.body().string());
            JsonNode result = root.path("chart").path("result").get(0);
            if (result == null || result.isMissingNode()) {
                throw new IOException("No data for " + symbol);
            }

            JsonNode ts = result.path("timestamp");
            JsonNode q = result.path("indicators").path("quote").get(0);
            JsonNode open = q.path("open"), high = q.path("high"),
                     low = q.path("low"), close = q.path("close"), vol = q.path("volume");

            // Today's bar often has close=null until Yahoo finalizes the session;
            // meta.regularMarketPrice carries the live price for that same bar.
            JsonNode meta = result.path("meta");
            Double liveClose = meta.hasNonNull("regularMarketPrice")
                    ? meta.path("regularMarketPrice").asDouble() : null;

            List<Bar> bars = new ArrayList<>();
            int last = ts.size() - 1;
            for (int i = 0; i < ts.size(); i++) {
                boolean closeMissing = close.get(i).isNull();
                if (closeMissing && i == last && liveClose != null) {
                    // patch today's still-forming bar with the live quote instead of dropping it
                    double hi = Math.max(high.get(i).isNull() ? liveClose : high.get(i).asDouble(), liveClose);
                    double lo = Math.min(low.get(i).isNull() ? liveClose : low.get(i).asDouble(), liveClose);
                    bars.add(new Bar(
                            ts.get(i).asLong(),
                            open.get(i).isNull() ? liveClose : open.get(i).asDouble(),
                            hi, lo, liveClose,
                            vol.get(i).isNull() ? 0 : vol.get(i).asDouble()));
                    continue;
                }
                // skip rows with any null (Yahoo emits nulls on holidays)
                if (closeMissing || vol.get(i).isNull()) continue;
                bars.add(new Bar(
                        ts.get(i).asLong(),
                        open.get(i).asDouble(),
                        high.get(i).asDouble(),
                        low.get(i).asDouble(),
                        close.get(i).asDouble(),
                        vol.get(i).asDouble()));
            }
            return bars;
        }
    }
}
