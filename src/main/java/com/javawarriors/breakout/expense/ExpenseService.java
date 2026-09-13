package com.javawarriors.breakout.expense;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Month-by-month cashflow: what each month earned, committed and saved, and how that adds up.
 *
 * <p>The one piece of real behaviour here is <b>carry-forward</b>. Rent, insurance and an EMI are
 * the same next month as this month, so a month with no record yet is seeded from the most recent
 * month that has one, with each loan's remaining tenure advanced by the gap and any loan that has
 * finished paying dropped. Without that, month-wise tracking means retyping the same eight rows
 * every month, which is the reason people stop doing it.
 *
 * <p>The seeded month is deliberately <em>not</em> saved by the read that produces it - a GET that
 * writes would create empty months for anything the user merely looked at, and those would then
 * count as real zero-income months in the year totals. It is returned as a draft and persists only
 * when saved.
 */
@Service
public class ExpenseService {

    private final MonthlyBudgetRepository repo;

    public ExpenseService(MonthlyBudgetRepository repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------------ reads

    /** Every recorded month, newest first, totals only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> months() {
        return repo.findAllByOrderByPeriodDesc().stream().map(MonthlyBudget::toSummaryRow).toList();
    }

    /**
     * One month. When nothing is recorded for it, returns a draft carried forward from the most
     * recent earlier month, flagged {@code saved: false}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> month(String period) {
        YearMonth ym = YearMonth.parse(period);
        Optional<MonthlyBudget> existing = repo.findByPeriod(period);
        if (existing.isPresent()) return existing.get().toRow();

        Optional<MonthlyBudget> previous = repo.findAllByOrderByPeriodDesc().stream()
                .filter(b -> b.yearMonth().isBefore(ym))
                .findFirst();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("period", period);
        row.put("saved", false);

        if (previous.isEmpty()) {
            row.put("income", 0.0);
            row.put("loans", List.of());
            row.put("expenses", List.of());
            row.put("totalEmi", 0.0);
            row.put("totalExpenses", 0.0);
            row.put("committed", 0.0);
            row.put("savings", 0.0);
            row.put("savingsRatePct", Double.NaN);
            row.put("carriedFrom", null);
            return row;
        }

        MonthlyBudget prev = previous.get();
        int gap = (int) prev.yearMonth().until(ym, java.time.temporal.ChronoUnit.MONTHS);

        List<Map<String, Object>> loans = new ArrayList<>();
        List<Map<String, Object>> expenses = new ArrayList<>();
        double emi = 0, exp = 0;
        for (BudgetItem i : prev.getItems()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", null);                    // a draft row has no identity yet
            item.put("kind", i.getKind());
            item.put("name", i.getName());
            item.put("amount", i.getAmount());
            item.put("position", i.getPosition());
            if (i.isLoan()) {
                Integer remaining = i.getRemainingMonths();
                if (remaining != null) {
                    int left = remaining - gap;
                    // The EMI has finished in the intervening months, so it is not carried at all.
                    if (left <= 0) continue;
                    item.put("remainingMonths", left);
                } else {
                    item.put("remainingMonths", null);
                }
                loans.add(item);
                emi += i.getAmount();
            } else {
                item.put("remainingMonths", null);
                expenses.add(item);
                exp += i.getAmount();
            }
        }

        row.put("income", prev.getIncome());
        row.put("loans", loans);
        row.put("expenses", expenses);
        row.put("totalEmi", emi);
        row.put("totalExpenses", exp);
        row.put("committed", emi + exp);
        row.put("savings", prev.getIncome() - emi - exp);
        row.put("savingsRatePct", prev.getIncome() > 0
                ? (prev.getIncome() - emi - exp) / prev.getIncome() * 100 : Double.NaN);
        row.put("carriedFrom", prev.getPeriod());
        return row;
    }

    // ------------------------------------------------------------------ write

    /** Replaces a month's income and line items wholesale. Creates the month if it is new. */
    @Transactional
    public Map<String, Object> save(String period, double income, List<ItemInput> items) {
        YearMonth.parse(period);   // rejects a malformed period before anything is written

        MonthlyBudget budget = repo.findByPeriod(period)
                .orElseGet(() -> new MonthlyBudget(period, income));
        budget.setIncome(income);

        // Wholesale replace rather than diff: the editor sends the complete month every time, and
        // reconciling row-by-row would only add ways for the two to disagree.
        budget.getItems().clear();
        int position = 0;
        for (ItemInput in : items) {
            String kind = BudgetItem.LOAN.equals(in.kind()) ? BudgetItem.LOAN : BudgetItem.EXPENSE;
            Integer remaining = BudgetItem.LOAN.equals(kind) ? in.remainingMonths() : null;
            budget.getItems().add(new BudgetItem(budget, kind,
                    in.name() == null || in.name().isBlank() ? "Untitled" : in.name().trim(),
                    in.amount(), remaining, position++));
        }
        return repo.save(budget).toRow();
    }

    @Transactional
    public boolean delete(String period) {
        return repo.findByPeriod(period).map(b -> {
            repo.delete(b);
            return true;
        }).orElse(false);
    }

    public record ItemInput(String kind, String name, double amount, Integer remainingMonths) {}

    // ------------------------------------------------------------------ summaries

    /** Every month of one year, in calendar order, plus that year's totals. */
    @Transactional(readOnly = true)
    public Map<String, Object> year(int year) {
        List<MonthlyBudget> all = repo.findAllByOrderByPeriodDesc().stream()
                .filter(b -> b.yearMonth().getYear() == year)
                .sorted(Comparator.comparing(MonthlyBudget::getPeriod))
                .toList();

        List<Map<String, Object>> months = new ArrayList<>();
        double income = 0, emi = 0, exp = 0;
        for (MonthlyBudget b : all) {
            months.add(b.toSummaryRow());
            income += b.getIncome();
            emi += b.totalEmi();
            exp += b.totalExpenses();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("monthsRecorded", all.size());
        out.put("months", months);
        out.put("totalIncome", income);
        out.put("totalEmi", emi);
        out.put("totalExpenses", exp);
        out.put("totalCommitted", emi + exp);
        out.put("totalSavings", income - emi - exp);
        out.put("savingsRatePct", income > 0 ? (income - emi - exp) / income * 100 : Double.NaN);
        out.put("averageMonthlySavings", all.isEmpty() ? 0.0 : (income - emi - exp) / all.size());
        return out;
    }

    /**
     * Year-on-year totals, oldest first, each with its change against the year before.
     *
     * <p>Only years with a recorded month appear. A year that is still in progress is marked so the
     * comparison is not read as like-for-like against a complete one.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> yearOnYear() {
        Map<Integer, double[]> byYear = new TreeMap<>();   // year -> {income, emi, expenses, months}
        for (MonthlyBudget b : repo.findAllByOrderByPeriodDesc()) {
            double[] agg = byYear.computeIfAbsent(b.yearMonth().getYear(), y -> new double[4]);
            agg[0] += b.getIncome();
            agg[1] += b.totalEmi();
            agg[2] += b.totalExpenses();
            agg[3] += 1;
        }

        List<Map<String, Object>> years = new ArrayList<>();
        Double previousSavings = null;
        for (Map.Entry<Integer, double[]> e : byYear.entrySet()) {
            double[] a = e.getValue();
            double savings = a[0] - a[1] - a[2];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", e.getKey());
            row.put("monthsRecorded", (int) a[3]);
            row.put("complete", (int) a[3] == 12);
            row.put("totalIncome", a[0]);
            row.put("totalEmi", a[1]);
            row.put("totalExpenses", a[2]);
            row.put("totalCommitted", a[1] + a[2]);
            row.put("totalSavings", savings);
            row.put("savingsRatePct", a[0] > 0 ? savings / a[0] * 100 : Double.NaN);
            row.put("averageMonthlySavings", a[3] > 0 ? savings / a[3] : 0.0);
            row.put("changeVsPreviousYear", previousSavings == null ? null : savings - previousSavings);
            years.add(row);
            previousSavings = savings;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("years", years);
        out.put("lifetimeSavings", years.stream().mapToDouble(y -> (double) y.get("totalSavings")).sum());
        return out;
    }

    /** The compact figures the main dashboard card shows. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        YearMonth now = YearMonth.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentPeriod", now.toString());

        Optional<MonthlyBudget> current = repo.findByPeriod(now.toString());
        out.put("currentMonthRecorded", current.isPresent());
        out.put("currentMonth", current.map(MonthlyBudget::toSummaryRow).orElse(null));

        // Falling back to the latest recorded month keeps the card useful on the 1st of a month,
        // or whenever this month has not been filled in yet.
        Optional<MonthlyBudget> latest = repo.findAllByOrderByPeriodDesc().stream().findFirst();
        out.put("latestMonth", latest.map(MonthlyBudget::toSummaryRow).orElse(null));

        out.put("thisYear", year(now.getYear()));
        out.put("yearOnYear", yearOnYear());
        out.put("monthsTracked", repo.count());
        return out;
    }
}
