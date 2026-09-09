package com.javawarriors.breakout.journal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single delivery/swing trade, mirroring the user's manual trade-journal spreadsheet. Only the
 * raw inputs are persisted — everything the spreadsheet calls "auto-calculated" (invested, exit
 * value, P/L, hold days, result) is derived on read in {@link #toRow()} so it can never drift
 * from the stored numbers.
 */
@Entity
public class TradeLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String stock;
    private LocalDate buyDate;
    private LocalDate sellDate;
    private double qty;
    private double buyPrice;
    private Double sellPrice;
    private Double stopLoss;
    private Double target;

    protected TradeLogEntry() {
        // JPA
    }

    public TradeLogEntry(String stock, LocalDate buyDate, double qty, double buyPrice,
                          Double stopLoss, Double target) {
        this.stock = stock;
        this.buyDate = buyDate;
        this.qty = qty;
        this.buyPrice = buyPrice;
        this.stopLoss = stopLoss;
        this.target = target;
    }

    public Long getId() { return id; }
    public String getStock() { return stock; }
    public void setStock(String stock) { this.stock = stock; }
    public LocalDate getBuyDate() { return buyDate; }
    public void setBuyDate(LocalDate buyDate) { this.buyDate = buyDate; }
    public LocalDate getSellDate() { return sellDate; }
    public double getQty() { return qty; }
    public void setQty(double qty) { this.qty = qty; }
    public double getBuyPrice() { return buyPrice; }
    public void setBuyPrice(double buyPrice) { this.buyPrice = buyPrice; }
    public Double getSellPrice() { return sellPrice; }
    public Double getStopLoss() { return stopLoss; }
    public void setStopLoss(Double stopLoss) { this.stopLoss = stopLoss; }
    public Double getTarget() { return target; }
    public void setTarget(Double target) { this.target = target; }

    public boolean isClosed() {
        return sellDate != null && sellPrice != null;
    }

    /** Closes the trade — the only way sellDate/sellPrice are ever set. */
    public void close(LocalDate sellDate, double sellPrice) {
        this.sellDate = sellDate;
        this.sellPrice = sellPrice;
    }

    public double invested() {
        return qty * buyPrice;
    }

    public Double exitValue() {
        return isClosed() ? qty * sellPrice : null;
    }

    public Double pl() {
        Double exit = exitValue();
        return exit == null ? null : exit - invested();
    }

    public Double plPct() {
        Double pl = pl();
        return pl == null || invested() == 0 ? null : pl / invested() * 100;
    }

    public long holdDays() {
        LocalDate end = isClosed() ? sellDate : LocalDate.now();
        return ChronoUnit.DAYS.between(buyDate, end);
    }

    public double riskAtOpen() {
        return !isClosed() && stopLoss != null ? qty * (buyPrice - stopLoss) : 0;
    }

    /** WIN / LOSS / OPEN. */
    public String result() {
        Double pl = pl();
        if (pl == null) return "OPEN";
        return pl > 0 ? "WIN" : pl < 0 ? "LOSS" : "OPEN";
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("stock", stock);
        row.put("status", isClosed() ? "Closed" : "Open");
        row.put("buyDate", buyDate.toString());
        if (sellDate != null) row.put("sellDate", sellDate.toString());
        row.put("holdDays", holdDays());
        row.put("qty", qty);
        row.put("buyPrice", buyPrice);
        if (sellPrice != null) row.put("sellPrice", sellPrice);
        if (stopLoss != null) row.put("stopLoss", stopLoss);
        if (target != null) row.put("target", target);
        row.put("invested", invested());
        if (exitValue() != null) row.put("exitValue", exitValue());
        if (pl() != null) row.put("pl", pl());
        if (plPct() != null) row.put("plPct", plPct());
        row.put("result", result());
        return row;
    }
}
