package com.javawarriors.breakout.marketdata;

import com.javawarriors.breakout.model.Bar;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived cache of fetched daily OHLCV, keyed by symbol + range.
 *
 * <p>Exists because there are now two full-universe scans — the breakout scan and the bullish
 * ranking — over the same ~500 symbols. Without this, running one after the other would download
 * the identical bars twice, doubling the request count against Yahoo's endpoint and the ~2 minutes
 * of politeness sleeps that go with it. With it, the second scan of a session is served entirely
 * from memory.
 *
 * <p>The TTL is deliberately shorter than a trading session: a cached series must never be so old
 * that the last bar is yesterday's while the user is looking at today's market. Four hours means a
 * morning scan is refetched in the afternoon, and an overnight entry is never served stale.
 *
 * <p>Sizing: ~500 symbols x ~375 daily bars x 48 bytes is roughly 10 MB, which the container heap
 * (sized from the cgroup limit in the Dockerfile) absorbs comfortably.
 */
public final class BarCache {

    /** Daily bars change once a session, so they can be held for hours. */
    private static final long DAILY_TTL_MILLIS = Duration.ofHours(4).toMillis();

    /**
     * Sub-daily bars go stale in minutes, not hours. Holding a 15-minute series for four hours
     * would mean a caller ranked this morning's candles all afternoon, which is worse than not
     * caching at all — so those entries get their own, much shorter life.
     */
    private static final long INTRADAY_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private record Entry(List<Bar> bars, long fetchedAt, long ttlMillis) {
        boolean isFresh(long now) {
            return now - fetchedAt < ttlMillis;
        }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private BarCache() {
    }

    /**
     * Cached bars for {@code symbol} over {@code range}, fetching through {@code source} on a miss
     * or an expired entry. A failed fetch is not cached — the next caller retries rather than
     * inheriting a hole.
     */
    public static List<Bar> daily(YahooDataSource source, String symbol, String range) throws IOException {
        return series(source, symbol, range, "1d");
    }

    /** Cached bars at any interval, fetching on a miss or an expired entry. */
    public static List<Bar> series(YahooDataSource source, String symbol, String range, String interval)
            throws IOException {
        String key = key(symbol, range, interval);
        long ttl = "1d".equals(interval) ? DAILY_TTL_MILLIS : INTRADAY_TTL_MILLIS;
        long now = System.currentTimeMillis();
        Entry hit = CACHE.get(key);
        if (hit != null && hit.isFresh(now)) return hit.bars();

        List<Bar> bars = source.fetch(symbol, range, interval);
        CACHE.put(key, new Entry(bars, now, ttl));
        return bars;
    }

    /** Cached daily bars without fetching, or null when absent or stale. */
    public static List<Bar> peek(String symbol, String range) {
        return peek(symbol, range, "1d");
    }

    /** Cached bars at any interval without fetching, or null when absent or stale. */
    public static List<Bar> peek(String symbol, String range, String interval) {
        Entry hit = CACHE.get(key(symbol, range, interval));
        return hit != null && hit.isFresh(System.currentTimeMillis()) ? hit.bars() : null;
    }

    private static String key(String symbol, String range, String interval) {
        return symbol + "|" + range + "|" + interval;
    }

    public static void clear() {
        CACHE.clear();
    }

    /** Number of cached series — surfaced in scan status so a cache that never hits is visible. */
    public static int size() {
        return CACHE.size();
    }
}
