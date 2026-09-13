package com.javawarriors.breakout.expense;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The service against an in-memory repository.
 *
 * <p>A fake rather than a database: the behaviour worth testing is the carry-forward and the
 * aggregation, none of which is SQL. Wiring these to Postgres would make them slow, order-dependent
 * and unable to run without credentials, and would still be testing the same Java.
 */
class ExpenseServiceTest {

    /** Minimal stand-in for the JPA repository — only the four methods the service calls. */
    static class FakeRepo implements MonthlyBudgetRepository {
        final List<MonthlyBudget> rows = new ArrayList<>();
        final AtomicLong ids = new AtomicLong();

        @Override public Optional<MonthlyBudget> findByPeriod(String period) {
            return rows.stream().filter(b -> b.getPeriod().equals(period)).findFirst();
        }
        @Override public List<MonthlyBudget> findAllByOrderByPeriodDesc() {
            return rows.stream()
                    .sorted((a, b) -> b.getPeriod().compareTo(a.getPeriod()))
                    .toList();
        }
        @Override public <S extends MonthlyBudget> S save(S entity) {
            if (!rows.contains(entity)) rows.add(entity);
            ids.incrementAndGet();
            return entity;
        }
        @Override public void delete(MonthlyBudget entity) { rows.remove(entity); }
        @Override public long count() { return rows.size(); }

        // Everything else is unused by the service.
        @Override public List<MonthlyBudget> findAll() { return rows; }
        @Override public List<MonthlyBudget> findAll(org.springframework.data.domain.Sort sort) { return rows; }
        @Override public org.springframework.data.domain.Page<MonthlyBudget> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<MonthlyBudget> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public Optional<MonthlyBudget> findById(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void deleteAll(Iterable<? extends MonthlyBudget> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public <S extends MonthlyBudget> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends MonthlyBudget> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public void deleteAllInBatch(Iterable<MonthlyBudget> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public MonthlyBudget getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public MonthlyBudget getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public MonthlyBudget getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> long count(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget> boolean exists(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends MonthlyBudget, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
    }

    private FakeRepo repo;
    private ExpenseService service;

    private static ExpenseService.ItemInput loan(String name, double emi, int remaining) {
        return new ExpenseService.ItemInput("LOAN", name, emi, remaining);
    }

    private static ExpenseService.ItemInput expense(String name, double amount) {
        return new ExpenseService.ItemInput("EXPENSE", name, amount, null);
    }

    @BeforeEach
    void setUp() {
        repo = new FakeRepo();
        service = new ExpenseService(repo);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> row, String key) {
        return (List<Map<String, Object>>) row.get(key);
    }

    @Test
    void savingsIsIncomeMinusEveryCommittedOutgoing() {
        Map<String, Object> row = service.save("2026-01", 58000,
                List.of(loan("Loan 1", 10982, 4), loan("Loan 2", 13955, 20),
                        expense("Room rent", 7500), expense("Office food", 3000)));

        assertEquals(24937.0, (double) row.get("totalEmi"), 0.001);
        assertEquals(10500.0, (double) row.get("totalExpenses"), 0.001);
        assertEquals(35437.0, (double) row.get("committed"), 0.001);
        assertEquals(22563.0, (double) row.get("savings"), 0.001);
        assertEquals(38.9, (double) row.get("savingsRatePct"), 0.1);
        assertTrue((boolean) row.get("saved"));
    }

    @Test
    void aMonthThatRanShortReportsNegativeSavings() {
        Map<String, Object> row = service.save("2026-01", 30000, List.of(expense("Rent", 40000)));

        assertEquals(-10000.0, (double) row.get("savings"), 0.001);
    }

    @Test
    void anUnrecordedMonthCarriesForwardTheRecurringRows() {
        service.save("2026-01", 58000,
                List.of(loan("Loan 1", 10982, 4), expense("Room rent", 7500)));

        Map<String, Object> feb = service.month("2026-02");

        assertFalse((boolean) feb.get("saved"), "a carried-forward month is a draft, not a record");
        assertEquals("2026-01", feb.get("carriedFrom"));
        assertEquals(58000.0, (double) feb.get("income"), 0.001);
        assertEquals(1, list(feb, "loans").size());
        assertEquals(1, list(feb, "expenses").size());
        assertEquals("Room rent", list(feb, "expenses").get(0).get("name"));
    }

    @Test
    void carryForwardAdvancesLoanTenureByTheMonthsElapsed() {
        service.save("2026-01", 58000, List.of(loan("Loan 1", 10982, 12)));

        assertEquals(11, list(service.month("2026-02"), "loans").get(0).get("remainingMonths"));
        assertEquals(9, list(service.month("2026-04"), "loans").get(0).get("remainingMonths"),
                "three months on, three fewer payments left");
    }

    @Test
    void aLoanThatFinishesInTheGapIsNotCarriedAtAll() {
        service.save("2026-01", 58000,
                List.of(loan("Ending soon", 10982, 2), loan("Long one", 5000, 30)));

        Map<String, Object> april = service.month("2026-04");
        List<Map<String, Object>> loans = list(april, "loans");

        assertEquals(1, loans.size(), "the finished EMI must drop off");
        assertEquals("Long one", loans.get(0).get("name"));
        assertEquals(5000.0, (double) april.get("totalEmi"), 0.001,
                "and the freed EMI must stop counting against the month");
    }

    @Test
    void readingAnUnrecordedMonthNeverPersistsIt() {
        service.save("2026-01", 58000, List.of(expense("Rent", 7500)));

        service.month("2026-02");
        service.month("2026-03");

        assertEquals(1, repo.count(),
                "browsing months must not create empty records that skew the year totals");
    }

    @Test
    void theFirstEverMonthComesBackEmptyRatherThanFailing() {
        Map<String, Object> row = service.month("2026-01");

        assertFalse((boolean) row.get("saved"));
        assertNull(row.get("carriedFrom"));
        assertEquals(0.0, (double) row.get("income"), 0.001);
        assertTrue(list(row, "loans").isEmpty());
    }

    @Test
    void savingAMonthTwiceReplacesItRatherThanAppending() {
        service.save("2026-01", 58000, List.of(expense("Rent", 7500), expense("Food", 3000)));
        Map<String, Object> row = service.save("2026-01", 60000, List.of(expense("Rent", 8000)));

        assertEquals(1, repo.count());
        assertEquals(1, list(row, "expenses").size());
        assertEquals(60000.0, (double) row.get("income"), 0.001);
        assertEquals(8000.0, (double) row.get("totalExpenses"), 0.001);
    }

    @Test
    void aYearAddsUpEveryRecordedMonth() {
        service.save("2026-01", 58000, List.of(expense("Rent", 7500)));
        service.save("2026-02", 58000, List.of(expense("Rent", 7500)));
        service.save("2026-03", 62000, List.of(expense("Rent", 8000)));
        service.save("2025-12", 50000, List.of(expense("Rent", 7000)));   // a different year

        Map<String, Object> year = service.year(2026);

        assertEquals(3, year.get("monthsRecorded"));
        assertEquals(178000.0, (double) year.get("totalIncome"), 0.001);
        assertEquals(23000.0, (double) year.get("totalExpenses"), 0.001);
        assertEquals(155000.0, (double) year.get("totalSavings"), 0.001);
        assertEquals(3, list(year, "months").size());
        assertEquals("2026-01", list(year, "months").get(0).get("period"), "calendar order");
    }

    @Test
    void yearOnYearReportsTheChangeAgainstThePreviousYear() {
        service.save("2025-01", 50000, List.of(expense("Rent", 10000)));   // saved 40000
        service.save("2026-01", 58000, List.of(expense("Rent", 10000)));   // saved 48000

        List<Map<String, Object>> years = list(service.yearOnYear(), "years");

        assertEquals(2, years.size());
        assertEquals(2025, years.get(0).get("year"), "oldest first");
        assertNull(years.get(0).get("changeVsPreviousYear"), "nothing to compare the first year to");
        assertEquals(8000.0, (double) years.get(1).get("changeVsPreviousYear"), 0.001);
        assertFalse((boolean) years.get(0).get("complete"), "one recorded month is not a full year");
    }

    @Test
    void lifetimeSavingsSpansEveryTrackedYear() {
        service.save("2025-01", 50000, List.of(expense("Rent", 10000)));
        service.save("2026-01", 58000, List.of(expense("Rent", 10000)));

        assertEquals(88000.0, (double) service.yearOnYear().get("lifetimeSavings"), 0.001);
    }

    @Test
    void theOverviewFallsBackToTheLatestMonthWhenThisOneIsBlank() {
        service.save("2020-05", 40000, List.of(expense("Rent", 5000)));

        Map<String, Object> overview = service.overview();

        assertFalse((boolean) overview.get("currentMonthRecorded"));
        assertNull(overview.get("currentMonth"));
        assertNotNull(overview.get("latestMonth"), "the card still has something true to show");
        assertEquals(1L, overview.get("monthsTracked"));
    }

    @Test
    void aBadPeriodIsRejectedBeforeAnythingIsWritten() {
        assertThrows(Exception.class, () -> service.save("not-a-month", 1000, List.of()));
        assertEquals(0, repo.count());
    }

    @Test
    void anUnnamedRowIsStoredWithAPlaceholderRatherThanBlank() {
        Map<String, Object> row = service.save("2026-01", 1000,
                List.of(new ExpenseService.ItemInput("EXPENSE", "   ", 100, null)));

        assertEquals("Untitled", list(row, "expenses").get(0).get("name"));
    }
}
