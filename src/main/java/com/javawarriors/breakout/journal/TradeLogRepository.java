package com.javawarriors.breakout.journal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeLogRepository extends JpaRepository<TradeLogEntry, Long> {
}
