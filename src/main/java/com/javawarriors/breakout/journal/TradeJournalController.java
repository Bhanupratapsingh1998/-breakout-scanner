package com.javawarriors.breakout.journal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/journal")
public class TradeJournalController {

    private final TradeJournalService service;

    public TradeJournalController(TradeJournalService service) {
        this.service = service;
    }

    public record AddTradeRequest(String stock, String buyDate, double qty, double buyPrice,
                                   Double stopLoss, Double target) {}

    public record UpdateTradeRequest(Double stopLoss, Double target) {}

    public record CloseTradeRequest(String sellDate, double sellPrice) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    /** ?month=yyyy-MM scopes the dashboard to trades bought that month; omitted = overall. */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(value = "month", required = false) String month) {
        return service.dashboard(month);
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddTradeRequest req) {
        try {
            Map<String, Object> row = service.add(req.stock(), LocalDate.parse(req.buyDate()),
                    req.qty(), req.buyPrice(), req.stopLoss(), req.target());
            return ResponseEntity.status(HttpStatus.CREATED).body(row);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") Long id, @RequestBody UpdateTradeRequest req) {
        try {
            return ResponseEntity.ok(service.update(id, req.stopLoss(), req.target()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable("id") Long id, @RequestBody CloseTradeRequest req) {
        try {
            return ResponseEntity.ok(service.close(id, LocalDate.parse(req.sellDate()), req.sellPrice()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
