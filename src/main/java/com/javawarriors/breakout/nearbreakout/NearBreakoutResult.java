package com.javawarriors.breakout.nearbreakout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A stock that hasn't broken out yet but is coiling tightly under resistance — the pre-breakout
 * counterpart to {@code BreakoutResult}. Deliberately carries no entry/stop/target: recommending
 * a price before the breakout is actually confirmed would defeat the point of this list, which
 * is to watch for the confirmed move, not to chase ahead of it.
 */
public class NearBreakoutResult {

    public final String symbol;
    public String classification;       // COILING / TIGHTENING / NEAR
    public double currentPrice;
    public double resistance;
    public double breakoutConfirmLevel;  // the level BreakoutAnalyzer itself requires a close above
    public double distanceToResistancePct;
    public double atrContractionRatio;   // current ATR / ATR ~10 bars ago — lower is tighter
    public double volumeDryUpRatio;      // trailing 5D avg volume / 20D avg volume — lower is quieter
    public boolean contracting;
    public boolean volumeDriedUp;

    public NearBreakoutResult(String symbol) {
        this.symbol = symbol;
    }

    public Map<String, Object> toRow(String name, String universe) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("name", name);
        row.put("universe", universe);
        row.put("classification", classification);
        row.put("currentPrice", currentPrice);
        row.put("resistance", resistance);
        row.put("breakoutConfirmLevel", breakoutConfirmLevel);
        row.put("distanceToResistancePct", distanceToResistancePct);
        row.put("atrContractionRatio", atrContractionRatio);
        row.put("volumeDryUpRatio", volumeDryUpRatio);
        row.put("contracting", contracting);
        row.put("volumeDriedUp", volumeDriedUp);
        return row;
    }
}
