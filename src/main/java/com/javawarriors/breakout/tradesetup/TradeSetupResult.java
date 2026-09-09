package com.javawarriors.breakout.tradesetup;

import com.javawarriors.breakout.candlestick.CandlestickPattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified output of {@link TradeSetupAnalyzer} for either setup type (BREAKOUT_SETUP or
 * REVERSAL_SETUP): the candlestick patterns found, the confirmation-quality score, support/
 * volume context, the trade plan, and the final classification. Never claims a fixed win rate —
 * see TradeSetupAnalyzer's class javadoc.
 */
public class TradeSetupResult {

    public final String symbol;
    public String setupType;                              // BREAKOUT_SETUP / REVERSAL_SETUP
    public List<CandlestickPattern> patterns = new ArrayList<>();
    public int candlestickScore;
    public final int candlestickScoreMax = 10;
    public final Map<String, Integer> scoreBreakdown = new LinkedHashMap<>();
    public double volumeRatio;
    public Double fiftyTwoWeekReturnPct;
    public Double majorSupport;
    public Double distanceFromSupportPct;
    public Double patternHigh;
    public boolean confirmed;
    public Long confirmationTime;
    public Double confirmationClose;
    public double entry;
    public Double stop;
    public Double target;
    public Double riskPerShare;
    public Double rewardPerShare;
    public Double riskReward;
    public final Map<String, Boolean> hardGates = new LinkedHashMap<>();
    public String classification;

    public TradeSetupResult(String symbol) {
        this.symbol = symbol;
    }

    public Map<String, Object> toRow(String name, String universe) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("name", name);
        row.put("universe", universe);
        row.put("setupType", setupType);

        List<Map<String, Object>> patternRows = new ArrayList<>();
        for (CandlestickPattern p : patterns) {
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("type", p.type());
            pr.put("date", p.time());
            pr.put("high", p.patternHigh());
            pr.put("low", p.patternLow());
            pr.put("strength", p.strength());
            pr.put("description", p.description());
            patternRows.add(pr);
        }
        row.put("patterns", patternRows);
        row.put("patternLabel", patternLabel());

        row.put("candlestickScore", candlestickScore);
        row.put("candlestickScoreMax", candlestickScoreMax);
        row.put("scoreBreakdown", scoreBreakdown);
        row.put("volumeRatio", volumeRatio);
        if (fiftyTwoWeekReturnPct != null) row.put("fiftyTwoWeekReturnPct", fiftyTwoWeekReturnPct);
        if (majorSupport != null) row.put("majorSupport", majorSupport);
        if (distanceFromSupportPct != null) row.put("distanceFromSupportPct", distanceFromSupportPct);
        if (patternHigh != null) row.put("patternHigh", patternHigh);
        row.put("confirmed", confirmed);
        if (confirmationTime != null) row.put("confirmationDate", confirmationTime);
        if (confirmationClose != null) row.put("confirmationClose", confirmationClose);

        row.put("entry", entry);
        if (stop != null) row.put("stop", stop);
        if (target != null) row.put("target", target);
        if (riskPerShare != null) row.put("riskPerShare", riskPerShare);
        if (rewardPerShare != null) row.put("rewardPerShare", rewardPerShare);
        if (riskReward != null) row.put("riskReward", riskReward);

        row.put("hardGates", hardGates);
        row.put("classification", classification);
        return row;
    }

    /** e.g. "Hammer + Bullish Engulfing" for the dashboard's Pattern column. */
    public String patternLabel() {
        if (patterns.isEmpty()) return "—";
        StringBuilder sb = new StringBuilder();
        for (CandlestickPattern p : patterns) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(switch (p.type()) {
                case "HAMMER" -> "Hammer";
                case "BULLISH_ENGULFING" -> "Bullish Engulfing";
                case "MORNING_STAR" -> "Morning Star";
                default -> p.type();
            });
        }
        return sb.toString();
    }
}
