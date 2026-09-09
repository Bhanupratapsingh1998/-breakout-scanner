package com.javawarriors.breakout.reversal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A confirmed hammer-reversal signal: a stock that was structurally bearish for ~6 months,
 * printed a capitulation-low hammer candle, and then had a later close confirm it by clearing
 * the hammer's high. Unconfirmed hammers never produce a ReversalResult — see ReversalAnalyzer.
 */
public class ReversalResult {

    public final String symbol;
    public boolean confirmed;
    public double sixMonthReturnPct;
    public double hammerLow;
    public double hammerHigh;
    public int hammerBarsAgo;
    public int confirmBarsAgo;
    public double currentPrice;
    public double stopLoss;
    public double target;
    public double riskReward;

    public ReversalResult(String symbol) {
        this.symbol = symbol;
    }

    public Map<String, Object> toRow(String name, String universe) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("name", name);
        row.put("universe", universe);
        row.put("sixMonthReturnPct", sixMonthReturnPct);
        row.put("hammerLow", hammerLow);
        row.put("hammerHigh", hammerHigh);
        row.put("hammerBarsAgo", hammerBarsAgo);
        row.put("confirmBarsAgo", confirmBarsAgo);
        row.put("currentPrice", currentPrice);
        row.put("stopLoss", stopLoss);
        row.put("target", target);
        row.put("riskReward", riskReward);
        return row;
    }
}
