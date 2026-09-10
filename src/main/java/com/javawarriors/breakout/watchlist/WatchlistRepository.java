package com.javawarriors.breakout.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<WatchlistSymbol, Long> {

    List<WatchlistSymbol> findAllByOrderByAddedAtAsc();

    Optional<WatchlistSymbol> findBySymbol(String symbol);

    boolean existsBySymbol(String symbol);
}
