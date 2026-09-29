package com.javawarriors.breakout.marketdata;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The F&amp;O universe, parsed from NSE's contract file.
 *
 * <p>Driven through {@link FnoUniverseSource#parse} with a fixture rather than over the network:
 * the parsing rules are what can break, and a test that needs nsearchives.nseindia.com to be up is
 * a test that fails for reasons that have nothing to do with the code.
 *
 * <p>The fixture is real - these are verbatim rows from the live file, including the two awkward
 * tickers ({@code M&M}, {@code BAJAJ-AUTO}) and a row whose nearest-expiry lot cell is blank.
 */
class FnoUniverseSourceTest {

    /** Verbatim shape of fo_mktlots.csv: padded columns, one per expiry month. */
    private static final String CSV = String.join("\n",
            "UNDERLYING                          ,SYMBOL    ,SEP-26     ,OCT-26     ,NOV-26     ",
            "NIFTY 50                            ,NIFTY     ,65         ,65         ,65         ",
            "NIFTY BANK                          ,BANKNIFTY ,30         ,30         ,30         ",
            "NIFTY FINANCIAL SERVICES            ,FINNIFTY  ,60         ,60         ,60         ",
            "NIFTY MID SELECT                    ,MIDCPNIFTY,120        ,120        ,120        ",
            "NIFTY NEXT 50                       ,NIFTYNXT50,25         ,25         ,25         ",
            "ADANI PORT & SEZ LTD                ,ADANIPORTS,475        ,475        ,475        ",
            "MAHINDRA & MAHINDRA LTD             ,M&M       ,200        ,200        ,200        ",
            "BAJAJ AUTO LIMITED                  ,BAJAJ-AUTO,25         ,25         ,25         ",
            "RELIANCE INDUSTRIES LTD             ,RELIANCE  ,500        ,500        ,500        ",
            "SOME DELISTED NAME LTD              ,GONE      ,           ,           ,           ",
            "");

    private static Map<String, Integer> parsed() {
        FnoUniverseSource.parse(CSV);
        return FnoUniverseSource.lots();
    }

    @Test
    void dropsTheIndexContracts() {
        Map<String, Integer> lots = parsed();

        // NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and NIFTYNXT50 are indices. There is no
        // NIFTY.NS to fetch daily bars for, so a scan would record five permanent failures.
        assertFalse(lots.containsKey("NIFTY.NS"));
        assertFalse(lots.containsKey("BANKNIFTY.NS"));
        assertFalse(lots.containsKey("FINNIFTY.NS"));
        assertFalse(lots.containsKey("MIDCPNIFTY.NS"));
        assertFalse(lots.containsKey("NIFTYNXT50.NS"));
        assertEquals(5, lots.size(), "only the five stock rows should survive");
    }

    @Test
    void keepsTickersWithPunctuation() {
        Map<String, Integer> lots = parsed();

        // M&M and BAJAJ-AUTO are real NSE tickers. A naive [A-Z]+ filter drops both, and dropping
        // M&M loses one of the largest names in the derivatives segment.
        assertEquals(200, lots.get("M&M.NS"));
        assertEquals(25, lots.get("BAJAJ-AUTO.NS"));
        assertEquals(475, lots.get("ADANIPORTS.NS"));
    }

    @Test
    void suffixesEverySymbolForYahoo() {
        for (String symbol : parsed().keySet()) {
            assertTrue(symbol.endsWith(".NS"), symbol + " must be fetchable as an NSE symbol");
        }
    }

    @Test
    void aBlankLotIsZeroRatherThanAThrow() {
        Map<String, Integer> lots = parsed();

        // A name with no contract in the nearest month is still in the file. It stays in the
        // universe - it is analysable - but reports no lot size rather than a made-up one.
        assertTrue(lots.containsKey("GONE.NS"));
        assertEquals(0, lots.get("GONE.NS"));
        assertEquals(0, FnoUniverseSource.lotSizeOf("GONE.NS"));
    }

    @Test
    void sentenceCasesTheShoutedCompanyNames() {
        parsed();

        // The file is upper case throughout. Left as-is it would be the only shouting column in
        // the table - but SEZ has to survive, because "Sez" is not a word.
        assertEquals("Adani Port & SEZ Ltd", FnoUniverseSource.nameOf("ADANIPORTS.NS"));
        assertEquals("Reliance Industries Ltd", FnoUniverseSource.nameOf("RELIANCE.NS"));
        assertEquals("Mahindra & Mahindra Ltd", FnoUniverseSource.nameOf("M&M.NS"));
    }

    @Test
    void shortAcronymsSurviveAndRealWordsDoNot() {
        // Three capitals or fewer is read as an acronym; longer is read as a word. That is right
        // for SEZ and for PORT, and wrong for NTPC - which is acceptable because every name the UI
        // actually shows comes from the Nifty 500 CSV, already cased. See the method's javadoc.
        assertEquals("IOC Ltd", FnoUniverseSource.toTitleCase("IOC LTD"));
        assertEquals("Adani Port", FnoUniverseSource.toTitleCase("ADANI PORT"));
        assertEquals("The New India Assurance Co",
                FnoUniverseSource.toTitleCase("THE NEW INDIA ASSURANCE CO"));

        // The documented limitation, asserted so it stays a known trade-off rather than becoming
        // a surprise: four letters reads as a word, so GAIL loses its capitals. Widening the rule
        // to four would fix GAIL and break PORT, BANK, AUTO and LIFE, which are commoner.
        assertEquals("Gail (India) Ltd", FnoUniverseSource.toTitleCase("GAIL (INDIA) LTD"));
    }

    @Test
    void unknownSymbolHasNoLot() {
        parsed();
        assertEquals(0, FnoUniverseSource.lotSizeOf("NOTLISTED.NS"));
        assertNull(FnoUniverseSource.nameOf("NOTLISTED.NS"));
    }

    @Test
    void refusesAFileItCouldNotUnderstand() {
        // An HTML error page served with a 200 would otherwise install an empty universe and the
        // tab would silently show nothing. Throwing keeps the previous list in place.
        assertThrows(IllegalStateException.class,
                () -> FnoUniverseSource.parse("<html><body>Access Denied</body></html>"));
    }

    @Test
    void theBundledFallbackIsAUsableUniverse() {
        // Reached whenever NSE is unreachable, so it has to stand on its own: enough names to be
        // the F&O segment, every one Yahoo-fetchable, and no index contracts smuggled in.
        FnoUniverseSource.parse(CSV);
        assertTrue(FnoUniverseSource.symbols().size() >= 5);

        java.util.List<String> fallback = fallbackSymbols();
        assertTrue(fallback.size() > 150, "the F&O segment is ~210 names, got " + fallback.size());
        assertEquals(fallback.size(), new java.util.HashSet<>(fallback).size(), "no duplicates");
        for (String s : fallback) {
            assertTrue(s.endsWith(".NS"), s);
            assertFalse(s.startsWith("NIFTY"), s + " is an index, not a stock");
        }
        assertTrue(fallback.contains("RELIANCE.NS"));
        assertTrue(fallback.contains("M&M.NS"));
    }

    /** Reads the bundled list the way {@code useFallback} does, without disturbing the cache. */
    private static java.util.List<String> fallbackSymbols() {
        try {
            var f = FnoUniverseSource.class.getDeclaredField("FALLBACK");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<String> tickers = (java.util.List<String>) f.get(null);
            return tickers.stream().map(t -> t + ".NS").toList();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FALLBACK list is gone or renamed", e);
        }
    }
}
