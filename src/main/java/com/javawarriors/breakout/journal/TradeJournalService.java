package com.javawarriors.breakout.journal;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD over the trade journal plus the auto-calculated performance dashboard — formulas
 * reverse-engineered from the user's own spreadsheet and checked against its real numbers
 * (e.g. expectancy ₹79 = 0.8×936 + 0.2×(-3350), profit factor 1.1 = 3744/3350). The spreadsheet's
 * own "largest win/loss" cells were a broken formula (always ₹0); this computes them correctly
 * instead of reproducing that bug.
 */
@Service
public class TradeJournalService {

    private final TradeLogRepository repository;

    public TradeJournalService(TradeLogRepository repository) {
        this.repository = repository;
    }

    public List<Map<String, Object>> list() {
        return repository.findAll().stream()
                .sorted((a, b) -> b.getBuyDate().compareTo(a.getBuyDate()))
                .map(TradeLogEntry::toRow)
                .toList();
    }

    public Map<String, Object> add(String stock, LocalDate buyDate, double qty, double buyPrice,
                                    Double stopLoss, Double target) {
        TradeLogEntry entry = new TradeLogEntry(stock, buyDate, qty, buyPrice, stopLoss, target);
        return repository.save(entry).toRow();
    }

    public Map<String, Object> update(Long id, Double stopLoss, Double target) {
        TradeLogEntry entry = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No trade with id " + id));
        if (stopLoss != null) entry.setStopLoss(stopLoss);
        if (target != null) entry.setTarget(target);
        return repository.save(entry).toRow();
    }

    public Map<String, Object> close(Long id, LocalDate sellDate, double sellPrice) {
        TradeLogEntry entry = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No trade with id " + id));
        entry.close(sellDate, sellPrice);
        return repository.save(entry).toRow();
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    /** Overall dashboard across every trade ever logged. */
    public Map<String, Object> dashboard() {
        return dashboard(null);
    }

    /** Dashboard scoped to trades whose BUY DATE falls in `month` (format "yyyy-MM"), or the
     *  overall dashboard when `month` is null/blank — a trade is grouped by when it was entered,
     *  so an open position shows up under the month it was opened even before it closes. */
    public Map<String, Object> dashboard(String month) {
        List<TradeLogEntry> all = repository.findAll();
        if (month != null && !month.isBlank()) {
            all = all.stream().filter(e -> e.getBuyDate().toString().startsWith(month)).toList();
        }
        List<TradeLogEntry> closed = all.stream().filter(TradeLogEntry::isClosed).toList();
        List<TradeLogEntry> open = all.stream().filter(e -> !e.isClosed()).toList();
        List<TradeLogEntry> wins = closed.stream().filter(e -> "WIN".equals(e.result())).toList();
        List<TradeLogEntry> losses = closed.stream().filter(e -> "LOSS".equals(e.result())).toList();

        double realizedPl = closed.stream().mapToDouble(e -> e.pl()).sum();
        double closedInvested = closed.stream().mapToDouble(TradeLogEntry::invested).sum();
        double openInvested = open.stream().mapToDouble(TradeLogEntry::invested).sum();
        double totalInvested = all.stream().mapToDouble(TradeLogEntry::invested).sum();
        double capitalAtRisk = open.stream().mapToDouble(TradeLogEntry::riskAtOpen).sum();

        double avgWin = wins.isEmpty() ? 0 : wins.stream().mapToDouble(e -> e.pl()).average().orElse(0);
        double avgLoss = losses.isEmpty() ? 0 : losses.stream().mapToDouble(e -> e.pl()).average().orElse(0);
        double largestWin = wins.stream().mapToDouble(e -> e.pl()).max().orElse(0);
        double largestLoss = losses.stream().mapToDouble(e -> e.pl()).min().orElse(0);

        int closedCount = closed.size();
        int winCount = wins.size();
        int lossCount = losses.size();
        double winRate = closedCount == 0 ? 0 : (double) winCount / closedCount;
        double lossRate = closedCount == 0 ? 0 : (double) lossCount / closedCount;

        double sumWinPl = wins.stream().mapToDouble(e -> e.pl()).sum();
        double sumLossPl = losses.stream().mapToDouble(e -> e.pl()).sum(); // negative
        double expectancy = winRate * avgWin + lossRate * avgLoss;
        double profitFactor = sumLossPl == 0 ? (sumWinPl > 0 ? Double.POSITIVE_INFINITY : 0)
                : sumWinPl / Math.abs(sumLossPl);
        double winLossRatio = lossCount == 0 ? (winCount > 0 ? Double.POSITIVE_INFINITY : 0)
                : (double) winCount / lossCount;
        double avgHoldDays = closedCount == 0 ? 0
                : closed.stream().mapToLong(TradeLogEntry::holdDays).average().orElse(0);
        double riskOnOpenCapital = openInvested == 0 ? 0 : capitalAtRisk / openInvested;

        Map<String, Object> capitalPl = new LinkedHashMap<>();
        capitalPl.put("realizedPl", realizedPl);
        capitalPl.put("returnOnClosedCapitalPct", closedInvested == 0 ? 0 : realizedPl / closedInvested * 100);
        capitalPl.put("capitalDeployedOpen", openInvested);
        capitalPl.put("capitalAtRiskOpen", capitalAtRisk);
        capitalPl.put("totalCapitalInvested", totalInvested);

        Map<String, Object> performanceQuality = new LinkedHashMap<>();
        performanceQuality.put("winRatePct", winRate * 100);
        performanceQuality.put("avgWin", avgWin);
        performanceQuality.put("avgLoss", avgLoss);
        performanceQuality.put("largestWin", largestWin);
        performanceQuality.put("largestLoss", largestLoss);

        Map<String, Object> tradeCount = new LinkedHashMap<>();
        tradeCount.put("total", all.size());
        tradeCount.put("closed", closedCount);
        tradeCount.put("open", open.size());
        tradeCount.put("wins", winCount);
        tradeCount.put("losses", lossCount);

        Map<String, Object> edgeMetrics = new LinkedHashMap<>();
        edgeMetrics.put("expectancyPerTrade", expectancy);
        edgeMetrics.put("profitFactor", profitFactor);
        edgeMetrics.put("winLossRatio", winLossRatio);
        edgeMetrics.put("avgHoldClosedDays", avgHoldDays);
        edgeMetrics.put("riskOnOpenCapitalPct", riskOnOpenCapital * 100);

        Map<String, Object> winLossScorecard = new LinkedHashMap<>();
        winLossScorecard.put("closedTrades", closedCount);
        winLossScorecard.put("wins", winCount);
        winLossScorecard.put("losses", lossCount);
        winLossScorecard.put("winRatePct", winRate * 100);
        winLossScorecard.put("winLossRatio", winLossRatio);

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("capitalAndPl", capitalPl);
        dashboard.put("performanceQuality", performanceQuality);
        dashboard.put("tradeCount", tradeCount);
        dashboard.put("edgeMetrics", edgeMetrics);
        dashboard.put("winLossScorecard", winLossScorecard);
        return dashboard;
    }
}
