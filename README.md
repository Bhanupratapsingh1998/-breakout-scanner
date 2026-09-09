# Breakout Scanner (Java)

Java port of the 5-point technical breakout checklist:

- **A.** Price > 20 EMA
- **B.** 20 EMA > 50 EMA
- **C.** 50 EMA > 200 EMA
- **D.** Price breaks previous resistance (prior swing high)
- **E.** Breakout volume > 20-period average volume

Computes everything from **real OHLCV data** (Yahoo Finance), not chart images.

## Requirements

- JDK 17 or newer
- Maven 3.6+

## Build

```bash
cd breakout-java
mvn clean package
```

This produces a single runnable fat-jar at `target/breakout-scanner.jar`.

## Run

```bash
java -jar target/breakout-scanner.jar
```

It scans the watchlist in `Main.java`, prints a one-line score per stock
(`STRONG` or `3/5`), then a full checklist report for those meeting `MIN_PASS`.

If the network is unavailable it automatically falls back to synthetic demo
data so you can still see the output format.

## Customize

- **Watchlist** — edit the `WATCHLIST` list in `Main.java`. NSE symbols use the
  `.NS` suffix (e.g. `RELIANCE.NS`), BSE uses `.BO`.
- **Strictness** — change `MIN_PASS` (5 = only perfect setups, 4 = near-misses too).
- **Parameters** — `new BreakoutAnalyzer(lookback, exclude, volMaPeriod)` to tune
  the resistance window and volume MA length.

## Project layout

```
src/main/java/com/javawarriors/breakout/
  Bar.java               # OHLCV record
  BreakoutAnalyzer.java  # EMA + checklist math (pure, unit-testable)
  BreakoutResult.java    # holds checks + values, prints report
  YahooDataSource.java   # OkHttp fetch + Jackson parse
  Main.java              # watchlist runner + offline fallback
```

## Note

This confirms the *technical pattern* is present — it does not predict the price
will rise. False breakouts are common. Use as a screening/timing aid alongside
fundamentals and sensible position sizing.
