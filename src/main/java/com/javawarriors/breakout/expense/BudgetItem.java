package com.javawarriors.breakout.expense;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line in a month's budget - either a loan EMI or a fixed expense.
 *
 * <p>Both kinds live in one table because they are the same thing to the arithmetic: a fixed
 * monthly outgoing. What separates them is {@link #remainingMonths}, which only a loan has, and
 * which is what lets the forecast say when that payment stops and the cash comes back.
 */
@Entity
@Table(name = "budget_item")
public class BudgetItem {

    public static final String LOAN = "LOAN";
    public static final String EXPENSE = "EXPENSE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private MonthlyBudget budget;

    /** LOAN or EXPENSE. */
    private String kind;

    private String name;

    private double amount;

    /** Months of EMI left after this one. Null for an expense, which never ends on its own. */
    private Integer remainingMonths;

    /** Preserves the order the user arranged their rows in. */
    private int position;

    protected BudgetItem() {
        // JPA
    }

    public BudgetItem(MonthlyBudget budget, String kind, String name, double amount,
                      Integer remainingMonths, int position) {
        this.budget = budget;
        this.kind = kind;
        this.name = name;
        this.amount = amount;
        this.remainingMonths = remainingMonths;
        this.position = position;
    }

    public boolean isLoan() {
        return LOAN.equals(kind);
    }

    public Long getId() { return id; }
    public MonthlyBudget getBudget() { return budget; }
    public void setBudget(MonthlyBudget budget) { this.budget = budget; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public Integer getRemainingMonths() { return remainingMonths; }
    public void setRemainingMonths(Integer remainingMonths) { this.remainingMonths = remainingMonths; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", kind);
        row.put("name", name);
        row.put("amount", amount);
        row.put("remainingMonths", remainingMonths);
        row.put("position", position);
        return row;
    }
}
