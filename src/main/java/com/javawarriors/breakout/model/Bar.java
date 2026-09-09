package com.javawarriors.breakout.model;

/** One trading day of OHLCV data. */
public record Bar(long time, double open, double high, double low, double close, double volume) {
}
