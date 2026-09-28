package com.javawarriors.breakout.marketdata;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The stocks that have listed futures and options, from NSE's own market-lots file.
 *
 * <h2>Why this file rather than an index list</h2>
 *
 * <p>F&amp;O eligibility is not an index. SEBI sets it from turnover, market-wide position limits
 * and delivery value, and NSE adds and removes names on its own schedule - so no
 * {@code ind_nifty*list.csv} describes it. {@code fo_mktlots.csv} is the derivatives segment's own
 * contract file: if a symbol has a lot size published for the coming expiry, it trades in F&amp;O.
 * That makes the list definitionally correct rather than approximately correct, and it carries the
 * lot size, which is the number an F&amp;O reader needs and an equity list does not have.
 *
 * <h2>The index contracts are dropped</h2>
 *
 * <p>The same file lists NIFTY, BANKNIFTY, FINNIFTY and the rest. They are indices, not stocks:
 * there is no {@code NIFTY.NS} to fetch bars for, and a chart-pattern scan of an index is a
 * different feature. They are filtered on the UNDERLYING column reading "NIFTY ...", which is how
 * NSE names every one of them.
 *
 * <h2>The fallback is a snapshot, and says so</h2>
 *
 * <p>When NSE is unreachable the bundled list is used. It is a point-in-time copy and it will drift
 * - a stock added to F&amp;O next month is not in it. {@link #isLive()} reports which list is in
 * play so the UI can say so rather than quietly presenting stale membership as current.
 */
public class FnoUniverseSource {

    private static final Logger log = LoggerFactory.getLogger(FnoUniverseSource.class);

    /**
     * NSE moved this file from {@code archives.} to {@code nsearchives.}; the old host answers 301
     * and OkHttp would follow it, but naming the current host avoids a redirect on every refresh.
     */
    private static final String MKT_LOTS_URL =
            "https://nsearchives.nseindia.com/content/fo/fo_mktlots.csv";

    /** NSE tickers are upper-case alphanumerics plus &amp; and -, e.g. M&amp;M, BAJAJ-AUTO. */
    private static final Pattern TICKER = Pattern.compile("[A-Z0-9&\\-]+");

    private static final OkHttpClient CLIENT = new OkHttpClient();

    /** Symbol (with .NS) -> lot size. Empty until the first refresh succeeds. */
    private static final AtomicReference<Map<String, Integer>> LOTS = new AtomicReference<>(Map.of());
    /** Symbol (with .NS) -> the UNDERLYING column, which is a company name. */
    private static final AtomicReference<Map<String, String>> NAMES = new AtomicReference<>(Map.of());
    private static final AtomicReference<Boolean> LIVE = new AtomicReference<>(false);
    /**
     * Whether a fetch has been tried this session.
     *
     * <p>Without it, {@link #lotSizeOf} - which is called once per symbol while building a 210-row
     * universe - would retry an unreachable NSE 210 times per page load.
     */
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean(false);

    private FnoUniverseSource() {
    }

    /**
     * Re-fetches the contract file and updates the cache.
     *
     * <p>Never throws. A derivatives list that cannot be fetched should cost freshness, not the
     * whole tab - the fallback is a working universe, and {@link #isLive()} carries the difference.
     */
    public static Map<String, Integer> refresh() {
        ATTEMPTED.set(true);
        Request req = new Request.Builder()
                .url(MKT_LOTS_URL)
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            parse(resp.body().string());
            LIVE.set(true);
            log.info("F&O universe refreshed from NSE: {} stocks", LOTS.get().size());
        } catch (Exception e) {
            log.warn("F&O contract file unavailable, using the bundled snapshot: {}", e.toString());
            if (LOTS.get().isEmpty()) useFallback();
        }
        return LOTS.get();
    }

    /** Parses the market-lots CSV. Package-private so a test can drive it without the network. */
    static void parse(String csv) {
        Map<String, Integer> lots = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        // header: UNDERLYING,SYMBOL,<one column per expiry month>
        for (String line : csv.split("\r?\n")) {
            String[] cols = line.split(",");
            if (cols.length < 3) continue;
            String underlying = cols[0].trim();
            String ticker = cols[1].trim();
            if (ticker.isEmpty() || "SYMBOL".equalsIgnoreCase(ticker)) continue;
            // Every index contract's UNDERLYING begins "NIFTY " - NIFTY 50, NIFTY BANK, and so on.
            if (underlying.toUpperCase().startsWith("NIFTY")) continue;
            if (!TICKER.matcher(ticker).matches()) continue;

            String symbol = ticker + ".NS";
            lots.put(symbol, parseLot(cols[2]));
            names.put(symbol, toTitleCase(underlying));
        }
        if (lots.isEmpty()) throw new IllegalStateException("no F&O underlyings parsed");
        LOTS.set(lots);
        NAMES.set(names);
    }

    private static int parseLot(String cell) {
        try {
            return Integer.parseInt(cell.trim());
        } catch (NumberFormatException e) {
            // A blank nearest-expiry cell means no contract for that month, not a broken row.
            return 0;
        }
    }

    /** Two- and three-letter words that are not acronyms, so they get cased like any other word. */
    private static final java.util.Set<String> NOT_ACRONYMS =
            java.util.Set.of("LTD", "INC", "CO", "THE", "AND", "OF", "PLC", "NEW", "SUN");

    /**
     * Title-cases NSE's shouted underlying names - "ADANI PORT &amp; SEZ LTD" - keeping short
     * acronyms as acronyms.
     *
     * <p>A token of two or three capitals is an acronym (SEZ, IOC, TCS); anything longer is treated
     * as a word, which is right for PORT and BANK and wrong for NTPC. The rule is deliberately not
     * cleverer than that: measured against the live lists, all 210 F&amp;O stocks are also Nifty 500
     * constituents, so every name actually rendered comes from that properly-cased CSV and this
     * runs only for a name NSE has added to derivatives ahead of the next index rebalance. A
     * hand-maintained acronym list would be upkeep for a path that is currently empty.
     */
    static String toTitleCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (String token : s.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(isShortAcronym(token) ? token : caseWord(token));
        }
        return out.toString();
    }

    private static boolean isShortAcronym(String token) {
        if (token.length() < 2 || token.length() > 3) return false;
        if (NOT_ACRONYMS.contains(token)) return false;
        for (char c : token.toCharArray()) {
            if (!Character.isUpperCase(c)) return false;
        }
        return true;
    }

    /** Capitalises after each separator, so "GAIL (INDIA)" keeps its inner word capitalised too. */
    private static String caseWord(String token) {
        StringBuilder out = new StringBuilder(token.length());
        boolean boundary = true;
        for (char c : token.toLowerCase().toCharArray()) {
            out.append(boundary ? Character.toUpperCase(c) : c);
            boundary = c == '(' || c == '.' || c == '-' || c == '&';
        }
        return out.toString();
    }

    /**
     * Symbol -> lot size, fetching from NSE the first time it is asked for.
     *
     * <p>Lazy rather than loaded at startup, and lazy rather than only-on-scan. The tab shows the
     * universe size and every contract's lot before any scan has run, so waiting for a scan would
     * mean a first render with 210 rows reading "lot n/a" that silently corrected itself minutes
     * later. One fetch, on the first question asked.
     */
    public static Map<String, Integer> lots() {
        if (LOTS.get().isEmpty() && ATTEMPTED.compareAndSet(false, true)) refresh();
        if (LOTS.get().isEmpty()) useFallback();
        return LOTS.get();
    }

    /** The F&O symbols, {@code .NS}-suffixed, in NSE's own file order. */
    public static List<String> symbols() {
        return List.copyOf(lots().keySet());
    }

    /** Lot size for a symbol, or 0 when it is not an F&O underlying. */
    public static int lotSizeOf(String symbol) {
        return lots().getOrDefault(symbol, 0);
    }

    /** The UNDERLYING column's company name, or null when the symbol is not in the list. */
    public static String nameOf(String symbol) {
        lots();
        return NAMES.get().get(symbol);
    }

    /** True when the list in use came from NSE this session, false when it is the bundled copy. */
    public static boolean isLive() {
        return LIVE.get();
    }

    private static synchronized void useFallback() {
        if (!LOTS.get().isEmpty()) return;
        Map<String, Integer> lots = new LinkedHashMap<>();
        for (String ticker : FALLBACK) lots.put(ticker + ".NS", 0);
        LOTS.set(lots);
        NAMES.set(Map.of());
    }

    /**
     * The F&O stock list as of September 2026, used only when NSE cannot be reached.
     *
     * <p>Lot sizes are deliberately absent rather than frozen: a stale symbol is a symbol that may
     * no longer be in F&amp;O, which is a small error, but a stale lot size is a wrong contract
     * value, which is a number someone could size a position against. A missing lot renders as "—".
     */
    private static final List<String> FALLBACK = List.of(
            "360ONE", "ABB", "ABCAPITAL", "ADANIENSOL", "ADANIENT", "ADANIGREEN", "ADANIPORTS",
            "ADANIPOWER", "ALKEM", "AMBER", "AMBUJACEM", "ANGELONE", "APLAPOLLO", "APOLLOHOSP",
            "ASHOKLEY", "ASIANPAINT", "ASTRAL", "ATHERENERG", "AUBANK", "AUROPHARMA", "AXISBANK",
            "BAJAJ-AUTO", "BAJAJFINSV", "BAJAJHLDNG", "BAJFINANCE", "BANDHANBNK", "BANKBARODA",
            "BANKINDIA", "BDL", "BEL", "BHARATFORG", "BHARTIARTL", "BHEL", "BIOCON", "BLUESTARCO",
            "BOSCHLTD", "BPCL", "BRITANNIA", "BSE", "CAMS", "CANBK", "CDSL", "CGPOWER", "CHOLAFIN",
            "CIPLA", "COALINDIA", "COCHINSHIP", "COFORGE", "COLPAL", "CONCOR", "CROMPTON",
            "CUMMINSIND", "DABUR", "DELHIVERY", "DIVISLAB", "DIXON", "DLF", "DMART", "DRREDDY",
            "EICHERMOT", "ETERNAL", "FEDERALBNK", "FORCEMOT", "FORTIS", "GAIL", "GLENMARK",
            "GMRAIRPORT", "GODFRYPHLP", "GODREJCP", "GODREJPROP", "GRASIM", "GVT&D", "HAL",
            "HAVELLS", "HCLTECH", "HDFCAMC", "HDFCBANK", "HDFCLIFE", "HEROMOTOCO", "HINDALCO",
            "HINDPETRO", "HINDUNILVR", "HINDZINC", "HYUNDAI", "ICICIBANK", "ICICIGI", "ICICIPRULI",
            "IDEA", "IDFCFIRSTB", "IEX", "INDHOTEL", "INDIANB", "INDIGO", "INDUSINDBK",
            "INDUSTOWER", "INFY", "INOXWIND", "IOC", "IREDA", "IRFC", "ITC", "JINDALSTEL",
            "JIOFIN", "JSWENERGY", "JSWSTEEL", "JUBLFOOD", "KALYANKJIL", "KAYNES", "KEI",
            "KFINTECH", "KOTAKBANK", "KPITTECH", "LAURUSLABS", "LICHSGFIN", "LICI", "LODHA", "LT",
            "LTF", "LTM", "LUPIN", "M&M", "MAHABANK", "MANAPPURAM", "MANKIND", "MARICO", "MARUTI",
            "MAXHEALTH", "MAZDOCK", "MCX", "MFSL", "MOTHERSON", "MOTILALOFS", "MPHASIS",
            "MUTHOOTFIN", "NAM-INDIA", "NATIONALUM", "NAUKRI", "NBCC", "NESTLEIND", "NHPC", "NMDC",
            "NTPC", "NYKAA", "OBEROIRLTY", "OFSS", "OIL", "ONGC", "PAGEIND", "PATANJALI", "PAYTM",
            "PERSISTENT", "PETRONET", "PFC", "PGEL", "PHOENIXLTD", "PIDILITIND", "PIIND", "PNB",
            "PNBHOUSING", "POLICYBZR", "POLYCAB", "POWERGRID", "POWERINDIA", "PREMIERENE",
            "PRESTIGE", "RADICO", "RBLBANK", "RECLTD", "RELIANCE", "RVNL", "SAGILITY", "SAIL",
            "SBICARD", "SBILIFE", "SBIN", "SHREECEM", "SHRIRAMFIN", "SIEMENS", "SOLARINDS",
            "SONACOMS", "SRF", "SUNPHARMA", "SUPREMEIND", "SUZLON", "SWIGGY", "TATACONSUM",
            "TATAELXSI", "TATAPOWER", "TATASTEEL", "TCS", "TECHM", "TIINDIA", "TITAN", "TMPV",
            "TORNTPHARM", "TRENT", "TVSMOTOR", "ULTRACEMCO", "UNIONBANK", "UNITDSPR", "UNOMINDA",
            "UPL", "VBL", "VEDL", "VMM", "VOLTAS", "WAAREEENER", "WIPRO", "YESBANK", "ZYDUSLIFE");
}
