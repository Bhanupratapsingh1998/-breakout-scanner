package com.javawarriors.breakout.expense;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonthlyBudgetRepository extends JpaRepository<MonthlyBudget, Long> {

    Optional<MonthlyBudget> findByPeriod(String period);

    /** Newest first. The period is ISO "YYYY-MM", so lexical order is chronological order. */
    List<MonthlyBudget> findAllByOrderByPeriodDesc();
}
