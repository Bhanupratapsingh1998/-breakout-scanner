package com.javawarriors.breakout.expense;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * The expense tracker's API.
 *
 * <p>A month is addressed by its ISO period ("2026-09"). Reading a month that has no record yet
 * returns a draft carried forward from the previous one rather than a 404 - the caller wants a
 * month to edit, and an empty form would throw away every recurring row.
 */
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    public record ItemRequest(String kind, String name, double amount, Integer remainingMonths) {}

    public record SaveMonthRequest(double income, List<ItemRequest> items) {}

    /** Every recorded month, newest first, totals only. */
    @GetMapping("/months")
    public List<Map<String, Object>> months() {
        return service.months();
    }

    /** One month, seeded from the previous one when it has not been saved yet. */
    @GetMapping("/months/{period}")
    public ResponseEntity<?> month(@PathVariable String period) {
        try {
            return ResponseEntity.ok(service.month(period));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad period '" + period + "', expected YYYY-MM"));
        }
    }

    @PutMapping("/months/{period}")
    public ResponseEntity<?> save(@PathVariable String period, @RequestBody SaveMonthRequest req) {
        if (req == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        try {
            List<ExpenseService.ItemInput> items = (req.items() == null ? List.<ItemRequest>of() : req.items())
                    .stream()
                    .map(i -> new ExpenseService.ItemInput(i.kind(), i.name(), i.amount(), i.remainingMonths()))
                    .toList();
            return ResponseEntity.ok(service.save(period, req.income(), items));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/months/{period}")
    public ResponseEntity<Void> delete(@PathVariable String period) {
        return service.delete(period) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** One calendar year, month by month, with the year's totals. Defaults to the current year. */
    @GetMapping("/year")
    public Map<String, Object> year(@RequestParam(value = "year", required = false) Integer year) {
        return service.year(year != null ? year : YearMonth.now().getYear());
    }

    /** Every tracked year with its totals and the change against the year before. */
    @GetMapping("/year-on-year")
    public Map<String, Object> yearOnYear() {
        return service.yearOnYear();
    }

    /** The compact figures the main dashboard card shows. */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return service.overview();
    }
}
