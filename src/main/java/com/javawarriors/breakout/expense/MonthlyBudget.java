package com.javawarriors.breakout.expense;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One month's cashflow: what came in, and every committed outgoing against it.
 *
 * <p>A row per month rather than one rolling snapshot, because the point of tracking is comparison
 * - what changed since last month, and what a year adds up to. A single editable snapshot can only
 * ever answer "what is true now", and overwrites the history you would want to compare against.
 *
 * <p>Only the inputs are stored. Every total - committed, savings, the savings rate - is derived on
 * read, exactly as {@code TradeLogEntry} does, so a stored total can never drift from the figures
 * it was calculated from.
 */
@Entity
@Table(name = "monthly_budget")
public class MonthlyBudget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ISO year-month, "2026-09". Unique: one budget per calendar month. */
    @Column(unique = true, nullable = false)
    private String period;

    private double income;

    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<BudgetItem> items = new ArrayList<>();

    protected MonthlyBudget() {
        // JPA
    }

    public MonthlyBudget(String period, double income) {
        this.period = period;
        this.income = income;
    }

    public Long getId() { return id; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public double getIncome() { return income; }
    public void setIncome(double income) { this.income = income; }
    public List<BudgetItem> getItems() { return items; }

    public YearMonth yearMonth() {
        return YearMonth.parse(period);
    }

    public double totalEmi() {
        return items.stream().filter(BudgetItem::isLoan).mapToDouble(BudgetItem::getAmount).sum();
    }

    public double totalExpenses() {
        return items.stream().filter(i -> !i.isLoan()).mapToDouble(BudgetItem::getAmount).sum();
    }

    public double committed() {
        return totalEmi() + totalExpenses();
    }

    /** What is left after every committed outgoing. Negative means the month ran short. */
    public double savings() {
        return income - committed();
    }

    /** Savings as a percentage of income, or NaN when no income was recorded. */
    public double savingsRatePct() {
        return income > 0 ? savings() / income * 100 : Double.NaN;
    }

    /** Totals only — used by the year and dashboard views, which never need the line items. */
    public Map<String, Object> toSummaryRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", period);
        row.put("income", income);
        row.put("totalEmi", totalEmi());
        row.put("totalExpenses", totalExpenses());
        row.put("committed", committed());
        row.put("savings", savings());
        row.put("savingsRatePct", savingsRatePct());
        return row;
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = toSummaryRow();
        row.put("id", id);
        row.put("saved", true);
        List<Map<String, Object>> loans = new ArrayList<>();
        List<Map<String, Object>> expenses = new ArrayList<>();
        for (BudgetItem i : items) {
            (i.isLoan() ? loans : expenses).add(i.toRow());
        }
        row.put("loans", loans);
        row.put("expenses", expenses);
        return row;
    }
}
