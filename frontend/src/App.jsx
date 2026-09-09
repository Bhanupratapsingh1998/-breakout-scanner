import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  createChart, CandlestickSeries, HistogramSeries, createSeriesMarkers, LineStyle,
} from 'lightweight-charts'

const CUSTOM_STORAGE_KEY = 'breakout-scanner:custom-symbols'

function Logo({ size = 22 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <rect x="3" y="14" width="3.2" height="7" rx="0.6" fill="var(--logo-candle)" opacity="0.45" />
      <rect x="8.4" y="10" width="3.2" height="11" rx="0.6" fill="var(--logo-candle)" opacity="0.7" />
      <rect x="13.8" y="6" width="3.2" height="15" rx="0.6" fill="var(--logo-candle)" />
      <path d="M17.5 8.5L21.5 3M21.5 3H17.7M21.5 3V6.8" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function ExpandIcon({ size = 13 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M15 3h6v6M21 3l-7 7M9 21H3v-6M3 21l7-7" />
    </svg>
  )
}

function LogoBadge({ size = 36 }) {
  return (
    <div
      className="flex shrink-0 items-center justify-center rounded-xl"
      style={{ width: size, height: size, background: 'var(--logo-bg)', color: 'var(--accent)' }}
    >
      <Logo size={size * 0.52} />
    </div>
  )
}

function MiniStat({ label, value }) {
  return (
    <div>
      <div className="text-[10px] font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
        {label}
      </div>
      <div className="mt-0.5 text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{value}</div>
    </div>
  )
}

/**
 * One decision vocabulary shared by the Breakout and Reversal views. The analyzers emit
 * finer-grained classifications (BUY NOW / WAIT FOR PULLBACK / HIGH_QUALITY_REVERSAL / …);
 * this collapses every one of them onto the only question a table row needs to answer — act,
 * hold, or skip. The per-check breakdown is still in the detail panel, so the nuance is one
 * click away rather than competing for attention in the row.
 *
 * Near Breakout deliberately does NOT use this: nothing there has broken out yet, so every row
 * would read WAIT. That view keeps its own readiness scale (see NEAR_BREAKOUT_META).
 */
const DECISION_META = {
  'BUY NOW': { color: 'var(--status-good)', label: 'BUY NOW' },
  WAIT: { color: 'var(--status-warning)', label: 'WAIT' },
  REJECT: { color: 'var(--status-critical)', label: 'REJECT' },
}

const DECISIONS = ['ALL', 'BUY NOW', 'WAIT', 'REJECT']

/**
 * Any analyzer classification, breakout or reversal, mapped onto the shared three.
 *
 * Every value the backend actually emits is listed explicitly, including the near-breakout
 * readiness levels. The Near Breakout view renders NEAR_BREAKOUT_META rather than a decision
 * (each of its rows is structurally WAIT, so the column would say nothing), but leaving those
 * values to the default branch would quietly turn an entire watchlist into rejects.
 */
function decisionOf(classification) {
  switch (classification) {
    case 'BUY NOW':
    case 'HIGH_QUALITY_REVERSAL':
    case 'GOOD_REVERSAL':
      return 'BUY NOW'
    case 'WAIT FOR PULLBACK':
    case 'WAIT FOR BREAKOUT/RETEST':
    case 'AVOID CHASING':
    case 'WATCH':
    case 'WAIT_FOR_CONFIRMATION':
    case 'COILING':
    case 'TIGHTENING':
    case 'NEAR':
      return 'WAIT'
    case 'REJECTED':
    case 'CONFIRMED_BUT_NOT_TRADEABLE':
      return 'REJECT'
    default:
      // Reaching here means the backend vocabulary drifted. REJECT is the safe direction: an
      // unrecognised classification must never earn a green light.
      return 'REJECT'
  }
}

const UNIVERSES = [
  { key: 'ALL', label: 'All' },
  { key: 'NIFTY_50', label: 'Nifty 50', color: 'var(--cat-nifty50)' },
  { key: 'NEXT_50', label: 'Next 50', color: 'var(--cat-next50)' },
  { key: 'NIFTY_500', label: 'Nifty 500', color: 'var(--cat-nifty500)' },
  { key: 'CUSTOM', label: 'Custom', color: 'var(--cat-custom)' },
]

function Dot({ color, size = 8 }) {
  return (
    <span
      className="inline-block shrink-0 rounded-full"
      style={{ width: size, height: size, background: color }}
    />
  )
}

function DecisionBadge({ classification }) {
  const meta = DECISION_META[decisionOf(classification)]
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-semibold tracking-wide" style={{ color: 'var(--text-primary)' }}>
      <Dot color={meta.color} size={7} />
      <span>{meta.label}</span>
    </span>
  )
}

const EXHAUSTION_META = {
  LOW: { color: 'var(--status-good)', label: 'LOW' },
  MEDIUM: { color: 'var(--status-warning)', label: 'MEDIUM' },
  HIGH: { color: 'var(--status-critical)', label: 'HIGH' },
}

const BREAKOUT_STATUS_META = {
  CONFIRMED_HOLDING: { color: 'var(--status-good)', label: 'CONFIRMED & HOLDING' },
  CONFIRMED_RETESTING: { color: 'var(--status-warning)', label: 'CONFIRMED — RETESTING' },
  FAILED: { color: 'var(--status-critical)', label: 'FAILED' },
  NOT_CONFIRMED: { color: 'var(--status-critical)', label: 'NOT CONFIRMED' },
}

function BreakoutStatusBadge({ status }) {
  const meta = BREAKOUT_STATUS_META[status] ?? BREAKOUT_STATUS_META.NOT_CONFIRMED
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-semibold tracking-wide" style={{ color: 'var(--text-primary)' }}>
      <Dot color={meta.color} size={7} />
      <span>{meta.label}</span>
    </span>
  )
}

function CheckDots({ checks }) {
  return (
    <div className="flex gap-1">
      {checks.map((c) => (
        <span
          key={c.label}
          title={`${c.pass ? 'PASS' : 'FAIL'} — ${c.label}`}
          className="h-2.5 w-2.5 rounded-[2px]"
          style={{ background: c.pass ? 'var(--status-good)' : 'var(--gridline)' }}
        />
      ))}
    </div>
  )
}

function UniverseTag({ universe }) {
  const meta = UNIVERSES.find((u) => u.key === universe)
  if (!meta || meta.key === 'ALL') return null
  return (
    <span
      className="inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
      style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}
    >
      <Dot color={meta.color} size={6} />
      {meta.label}
    </span>
  )
}

function fmtPrice(v) {
  return v.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtVolume(v) {
  return Math.round(v).toLocaleString('en-IN')
}

function fmtValue(key, v) {
  return key.toLowerCase().includes('volume') ? fmtVolume(v) : fmtPrice(v)
}

function StatTile({ label, value, color }) {
  return (
    <div
      className="flex flex-1 flex-col gap-2 rounded-xl border p-4"
      style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}
    >
      <div className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
        {color && <Dot color={color} size={7} />}
        {label}
      </div>
      <div className="tabular text-2xl font-semibold" style={{ color: 'var(--text-primary)' }}>
        {value}
      </div>
    </div>
  )
}

function PriceHeader({ values }) {
  const close = values.Close
  const prevClose = values['Prev Close']
  const delta = prevClose != null ? close - prevClose : null
  const pct = delta != null && prevClose ? (delta / prevClose) * 100 : null
  const up = delta != null && delta >= 0
  const deltaColor = up ? 'var(--status-good)' : 'var(--status-critical)'
  return (
    <div className="flex items-baseline gap-3">
      <span className="tabular text-3xl font-semibold" style={{ color: 'var(--text-primary)' }}>
        &#8377;{fmtPrice(close)}
      </span>
      {delta != null && (
        <span className="tabular inline-flex items-center gap-1 text-sm font-semibold" style={{ color: deltaColor }}>
          {up ? '▲' : '▼'} {fmtPrice(Math.abs(delta))} ({up ? '+' : ''}
          {pct.toFixed(2)}%)
        </span>
      )}
    </div>
  )
}

function OhlcStrip({ values }) {
  const cells = [
    ['Open', values.Open],
    ['High', values.High],
    ['Low', values.Low],
    ['Prev Close', values['Prev Close']],
  ].filter(([, v]) => v != null)

  return (
    <div className="flex overflow-hidden rounded-lg border" style={{ borderColor: 'var(--border)' }}>
      {cells.map(([label, v], i) => (
        <div
          key={label}
          className="flex-1 px-3 py-2 text-center"
          style={{ borderLeft: i > 0 ? `1px solid ${'var(--border)'}` : 'none' }}
        >
          <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            {label}
          </div>
          <div className="tabular mt-0.5 text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
            {fmtPrice(v)}
          </div>
        </div>
      ))}
    </div>
  )
}

function TrendLadder({ values }) {
  const steps = [
    ['Price', values.Close],
    ['EMA20', values.EMA20],
    ['EMA50', values.EMA50],
    ['EMA200', values.EMA200],
  ]
  return (
    <div>
      <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
        Trend (Price &gt; EMA20 &gt; EMA50 &gt; EMA200)
      </h4>
      <div className="flex flex-wrap items-center gap-1.5 rounded-lg border p-3" style={{ borderColor: 'var(--border)' }}>
        {steps.map(([label, v], i) => {
          const prevVal = i > 0 ? steps[i - 1][1] : null
          const ok = prevVal == null || prevVal > v
          return (
            <Fragment key={label}>
              {i > 0 && (
                <span className="text-sm font-bold" style={{ color: ok ? 'var(--status-good)' : 'var(--status-critical)' }}>
                  &rarr;
                </span>
              )}
              <div className="flex flex-col items-center rounded px-2 py-1">
                <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                  {label}
                </span>
                <span className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                  {fmtPrice(v)}
                </span>
              </div>
            </Fragment>
          )
        })}
      </div>
    </div>
  )
}

function Meter({ label, statusLabel, statusColor, primary, secondary, fillPct }) {
  return (
    <div className="rounded-lg border p-3" style={{ borderColor: 'var(--border)' }}>
      <div className="flex items-center justify-between text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
        <span>{label}</span>
        <span style={{ color: statusColor }}>{statusLabel}</span>
      </div>
      <div className="mt-1.5 flex items-baseline justify-between">
        <span className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{primary}</span>
        <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>{secondary}</span>
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
        <div
          className="h-full rounded-full transition-all"
          style={{ width: `${Math.min(100, Math.max(4, fillPct))}%`, background: statusColor }}
        />
      </div>
    </div>
  )
}

function ResistanceMeter({ values }) {
  const price = values.Close
  const resistance = values['Prev resistance']
  const confirmLevel = values['Breakout Confirm Level'] ?? resistance
  const confirmed = price > confirmLevel
  const pct = resistance ? ((price - resistance) / resistance) * 100 : 0
  const color = confirmed ? 'var(--status-good)' : 'var(--status-critical)'
  return (
    <Meter
      label="Resistance"
      statusLabel={confirmed ? 'CONFIRMED ✓' : 'NOT CONFIRMED'}
      statusColor={color}
      primary={`₹${fmtPrice(resistance)}`}
      secondary={`${pct >= 0 ? '+' : ''}${pct.toFixed(2)}% vs price (need +0.5%)`}
      fillPct={50 + pct * 4}
    />
  )
}

function VolumeMeter({ values }) {
  const vol = values.Volume
  const avg = values['Volume MA(20)']
  const ratio = avg ? vol / avg : 1
  const above = ratio >= 1
  const color = above ? 'var(--status-good)' : 'var(--status-muted)'
  return (
    <Meter
      label="Volume vs 20d avg"
      statusLabel={`${ratio.toFixed(2)}×`}
      statusColor={color}
      primary={fmtVolume(vol)}
      secondary={`avg ${fmtVolume(avg)}`}
      fillPct={ratio * 50}
    />
  )
}

function RsiMeter({ values }) {
  const rsi = values['RSI(14)']
  if (rsi == null) return null
  const overbought = rsi >= 70
  const oversold = rsi <= 30
  const color = overbought ? 'var(--status-critical)' : oversold ? 'var(--status-warning)' : 'var(--status-good)'
  const statusLabel = overbought ? 'OVERBOUGHT' : oversold ? 'OVERSOLD' : 'HEALTHY'
  return (
    <Meter
      label="RSI (14)"
      statusLabel={statusLabel}
      statusColor={color}
      primary={rsi.toFixed(1)}
      secondary={overbought ? '≥ 70' : oversold ? '≤ 30' : '30–70'}
      fillPct={rsi}
    />
  )
}

function TradeRow({ label, value, color }) {
  return (
    <div className="flex items-baseline justify-between py-1">
      <dt className="text-sm" style={{ color: 'var(--text-secondary)' }}>{label}</dt>
      <dd className="tabular text-sm font-semibold" style={{ color: color ?? 'var(--text-primary)' }}>
        ₹{fmtPrice(value)}
      </dd>
    </div>
  )
}

function TradePlan({ values }) {
  const entry = values.Close
  const stop = values['Stop Loss']
  const risk = values.Risk
  const target = values.Target
  const reward = values.Reward
  const rr = values['Risk:Reward']

  return (
    <div>
      <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
        Trade Plan
      </h4>
      {risk == null || risk <= 0 ? (
        <div className="rounded-lg border p-4 text-sm" style={{ borderColor: 'var(--border)', color: 'var(--text-muted)' }}>
          Not broken out yet — price is still below ₹{fmtPrice(values['Prev resistance'])}, the resistance it needs to clear first. No valid trade plan until it does.
        </div>
      ) : (
        <div className="rounded-lg border p-3" style={{ borderColor: 'var(--border)' }}>
          <dl>
            <TradeRow label="Entry" value={entry} />
            <TradeRow label="Stop-loss" value={stop} color="var(--status-critical)" />
            <TradeRow label="Risk" value={risk} color="var(--status-critical)" />
          </dl>
          <div className="my-2 border-t" style={{ borderColor: 'var(--gridline)' }} />
          <dl>
            <TradeRow label="Target" value={target} color="var(--status-good)" />
            <TradeRow label="Reward" value={reward} color="var(--status-good)" />
          </dl>
          {rr != null && (
            <div className="mt-2 flex items-center justify-between border-t pt-2 text-xs" style={{ borderColor: 'var(--gridline)' }}>
              <span style={{ color: 'var(--text-muted)' }}>Risk : Reward</span>
              <span
                className="tabular font-semibold"
                style={{ color: rr >= 2 ? 'var(--status-good)' : 'var(--status-critical)' }}
              >
                {rr.toFixed(2)} : 1
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function ScoreTile({ label, score, max }) {
  const pct = max ? (score / max) * 100 : 0
  const color = pct >= 80 ? 'var(--status-good)' : pct >= 40 ? 'var(--status-warning)' : 'var(--status-critical)'
  return (
    <div className="flex flex-1 flex-col gap-1.5 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>{label}</div>
      <div className="tabular text-2xl font-semibold" style={{ color }}>{score}/{max}</div>
    </div>
  )
}

/**
 * Setup Quality alone answers "is this stock worth trading" — it's what the old single
 * checklist measured. Entry Quality answers "is NOW the right time," and is what was missing
 * when a 10/10 setup got bought right into a profit-booking selloff. A stock can be
 * Setup 10/10 + Entry 2/10 — that combination should read as WAIT, not BUY.
 */
function EntryQualityPanel({ row }) {
  const { values } = row
  const exhaustionMeta = EXHAUSTION_META[row.exhaustionRisk] ?? EXHAUSTION_META.LOW
  const aggressive = values['Aggressive Entry']
  const conservative = values['Conservative Entry']
  const preferred = values['Preferred Entry']

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Setup vs. Entry
        </h4>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <BreakoutStatusBadge status={row.breakoutStatus} />
          <DecisionBadge classification={row.classification} />
        </div>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <ScoreTile label="Setup Quality" score={row.setupScore} max={row.setupTotal} />
        <ScoreTile label="Entry Quality" score={row.entryScore} max={row.entryTotal} />
        <div className="flex flex-1 flex-col gap-1.5 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <div className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>Profit-Booking Risk</div>
          <div className="tabular text-2xl font-semibold" style={{ color: exhaustionMeta.color }}>{exhaustionMeta.label}</div>
        </div>
      </div>
      {aggressive != null && (
        <div className="mt-3 rounded-lg border p-3" style={{ borderColor: 'var(--border)' }}>
          <dl>
            <TradeRow label="Aggressive entry (now)" value={aggressive} />
            <TradeRow label="Conservative entry (retest/HL)" value={conservative} color="var(--cat-next50)" />
            <TradeRow label="Preferred entry" value={preferred} color="var(--accent)" />
          </dl>
        </div>
      )}
    </div>
  )
}

function checkDetail(label, values) {
  if (label.startsWith('A.')) return `${fmtPrice(values.Close)} vs ${fmtPrice(values.EMA20)}`
  if (label.startsWith('B.')) return `${fmtPrice(values.EMA20)} vs ${fmtPrice(values.EMA50)}`
  if (label.startsWith('C.')) return `${fmtPrice(values.EMA50)} vs ${fmtPrice(values.EMA200)}`
  if (label.startsWith('D.')) return `${fmtPrice(values.Close)} vs ${fmtPrice(values['Breakout Confirm Level'])} (resistance ${fmtPrice(values['Prev resistance'])} +0.5%)`
  if (label.startsWith('E.')) return `${fmtVolume(values.Volume)} vs ${fmtVolume(1.5 * values['Volume MA(20)'])} (1.5× avg)`
  if (label.startsWith('F.')) return `RSI ${values['RSI(14)']?.toFixed(1)} (need 55–70)`
  if (label.startsWith('G.')) return `ADX ${values['ADX(14)']?.toFixed(1)} vs 25`
  if (label.startsWith('H.')) {
    const stock = values['Stock Return (20d) %']
    const bench = values['Benchmark Return (20d) %']
    if (stock == null || bench == null) return 'no benchmark data'
    return `${stock.toFixed(2)}% vs ${bench.toFixed(2)}%`
  }
  if (label.startsWith('I.')) {
    const rr = values['Risk:Reward']
    return rr == null ? 'n/a (not broken out)' : `${rr.toFixed(2)}:1 vs 2:1`
  }
  if (label.startsWith('J.')) {
    const ext = values['Extension Above Breakout %']
    return ext == null ? 'n/a (not broken out)' : `+${ext.toFixed(1)}% above breakout`
  }
  return null
}

const KNOWN_VALUE_KEYS = new Set([
  'Open', 'High', 'Low', 'Close', 'Prev Close', 'EMA20', 'EMA50', 'EMA200', 'Prev resistance', 'Breakout Confirm Level',
  'Volume', 'Volume MA(20)', 'RSI(14)', 'ADX(14)', 'ATR(14)', 'Stop Loss', 'Risk', 'Target', 'Reward', 'Risk:Reward',
  'Extension Above Breakout %', 'Major Resistance (52w)', 'Nearest Resistance Above', '3D Return %', '5D Return %',
  '10D Return %', 'Aggressive Entry', 'Conservative Entry', 'Preferred Entry',
])

function DetailPanel({ row }) {
  const extras = Object.entries(row.values).filter(([k]) => !KNOWN_VALUE_KEYS.has(k))
  const [chartBars, setChartBars] = useState(null)
  const [chartError, setChartError] = useState(null)
  const [fullChart, setFullChart] = useState(false)

  useEffect(() => {
    let cancelled = false
    setChartBars(null)
    setChartError(null)
    fetch(`/api/chart?symbol=${encodeURIComponent(row.symbol)}`)
      .then((r) => r.json())
      .then((body) => {
        if (cancelled) return
        if (body.error) setChartError(body.error)
        else setChartBars(body.bars)
      })
      .catch((e) => { if (!cancelled) setChartError(e.message || 'Could not load chart') })
    return () => { cancelled = true }
  }, [row.symbol])

  return (
    <div className="space-y-4 border-t p-5" style={{ borderColor: 'var(--gridline)', background: 'var(--page-plane)' }}>
      <PriceHeader values={row.values} />
      <OhlcStrip values={row.values} />

      <div>
        <div className="mb-2 flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Price Chart
          </h4>
          <button
            onClick={() => setFullChart(true)}
            className="flex items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium transition-opacity hover:opacity-70"
            style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}
          >
            <ExpandIcon /> Full chart
          </button>
        </div>
        {chartError ? (
          <p className="text-sm" style={{ color: 'var(--status-serious)' }}>{chartError}</p>
        ) : chartBars ? (
          <div
            role="button"
            tabIndex={0}
            title="Open full chart"
            onClick={() => setFullChart(true)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setFullChart(true) }
            }}
          >
            <CandlestickChart
              bars={chartBars}
              resistance={row.values['Prev resistance']}
              breakoutConfirmLevel={row.values['Breakout Confirm Level']}
              breakoutBarTime={row.values['Breakout Bar Time']}
            />
          </div>
        ) : (
          <div className="flex items-center justify-center text-sm" style={{ height: 200, color: 'var(--text-muted)' }}>
            Loading chart…
          </div>
        )}
      </div>
      {fullChart && <FullChartModal row={row} onClose={() => setFullChart(false)} />}

      <TrendLadder values={row.values} />
      <TradePlan values={row.values} />
      <EntryQualityPanel row={row} />

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <ResistanceMeter values={row.values} />
        <VolumeMeter values={row.values} />
        <RsiMeter values={row.values} />
      </div>

      <div>
        <div className="mb-2 flex items-center justify-between">
          <h4 className="text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Hard Gates &amp; Checks
          </h4>
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            raw pass/fail — see Setup vs. Entry above for the score
          </span>
        </div>
        {row.passedAllGates === false && (
          <div
            className="mb-2 rounded-lg border px-3 py-2 text-sm"
            style={{ borderColor: 'var(--status-critical)', color: 'var(--status-critical)', background: 'var(--page-plane)' }}
          >
            ❌ Rejected — failed hard gate{row.checks.filter((c) => c.gate && !c.pass).length > 1 ? 's' : ''}:{' '}
            {row.checks.filter((c) => c.gate && !c.pass).map((c) => c.label).join(', ')}
          </div>
        )}
        <ul className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
          {row.checks.map((c) => (
            <li
              key={c.label}
              className="flex items-center justify-between gap-3 rounded-lg border px-3 py-2"
              style={{ borderColor: c.gate && !c.pass ? 'var(--status-critical)' : 'var(--border)' }}
            >
              <span className="flex items-center gap-2 text-sm">
                <Dot color={c.pass ? 'var(--status-good)' : 'var(--status-critical)'} size={7} />
                <span style={{ color: 'var(--text-primary)' }}>{c.label}</span>
                {c.gate && (
                  <span
                    className="rounded px-1 py-0.5 text-[9px] font-bold uppercase tracking-wide"
                    style={{ background: 'var(--gridline)', color: 'var(--text-secondary)' }}
                  >
                    Gate
                  </span>
                )}
              </span>
              <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>{checkDetail(c.label, row.values)}</span>
            </li>
          ))}
        </ul>
      </div>

      {extras.length > 0 && (
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-xs" style={{ color: 'var(--text-muted)' }}>
          {extras.map(([k, v]) => (
            <div key={k} className="flex gap-1.5">
              <dt>{k}:</dt>
              <dd className="tabular font-medium" style={{ color: 'var(--text-secondary)' }}>{fmtValue(k, v)}</dd>
            </div>
          ))}
        </dl>
      )}

      <p className="text-sm" style={{ color: 'var(--text-secondary)' }}>{row.verdict}</p>
    </div>
  )
}

function loadSavedSymbols() {
  try {
    return JSON.parse(localStorage.getItem(CUSTOM_STORAGE_KEY) ?? '[]')
  } catch {
    return []
  }
}

function saveSymbols(symbols) {
  localStorage.setItem(CUSTOM_STORAGE_KEY, JSON.stringify(symbols))
}

const inputStyle = {
  background: 'var(--surface-1)',
  borderColor: 'var(--border)',
  color: 'var(--text-primary)',
}

function FilterPill({ active, onClick, children }) {
  return (
    <button
      onClick={onClick}
      className="rounded-full border px-3 py-1.5 text-xs font-semibold transition-colors"
      style={
        active
          ? { background: 'var(--text-primary)', color: 'var(--surface-1)', borderColor: 'var(--text-primary)' }
          : { background: 'transparent', color: 'var(--text-secondary)', borderColor: 'var(--border)' }
      }
    >
      {children}
    </button>
  )
}

/** The Breakout Scanner / Near Breakout / Reversal Watch / Trade Journal tab row — shared by the
 *  main app shell AND the "no scan data yet" / "loading" screens, so every tab is reachable from
 *  anywhere, not just after a scan has completed. */
function ViewTabs({ view, setView, reversalCount, nearBreakoutCount }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      <FilterPill active={view === 'breakout'} onClick={() => setView('breakout')}>
        Breakout Scanner
      </FilterPill>
      <FilterPill active={view === 'nearBreakout'} onClick={() => setView('nearBreakout')}>
        Near Breakout{nearBreakoutCount > 0 ? ` (${nearBreakoutCount})` : ''}
      </FilterPill>
      <FilterPill active={view === 'reversal'} onClick={() => setView('reversal')}>
        Reversal Watch{reversalCount > 0 ? ` (${reversalCount})` : ''}
      </FilterPill>
      <FilterPill active={view === 'journal'} onClick={() => setView('journal')}>
        Trade Journal
      </FilterPill>
    </div>
  )
}

/** Readiness, not a decision — every row in this view is by definition still waiting. */
const NEAR_BREAKOUT_META = {
  COILING: { color: 'var(--status-good)', label: 'COILING' },
  TIGHTENING: { color: 'var(--cat-next50)', label: 'TIGHTENING' },
  NEAR: { color: 'var(--status-warning)', label: 'NEAR' },
}

function NearBreakoutBadge({ classification }) {
  const meta = NEAR_BREAKOUT_META[classification] ?? NEAR_BREAKOUT_META.NEAR
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-semibold tracking-wide" style={{ color: 'var(--text-primary)' }}>
      <Dot color={meta.color} size={7} />
      <span>{meta.label}</span>
    </span>
  )
}

/**
 * Stocks that HAVEN'T broken out yet but are coiling right under resistance — proximity +
 * volatility contraction + volume dry-up. Deliberately no entry/stop/target here: recommending
 * a price before the breakout is actually confirmed would be exactly the chasing this scanner
 * was built to avoid. Wait for it to show up in Breakout Scanner once confirmed.
 */
function NearBreakoutTable({ rows }) {
  return (
    <div className="overflow-x-auto rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <table className="w-full text-sm">
        <thead>
          <tr style={{ borderBottom: `1px solid ${'var(--gridline)'}` }}>
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Symbol</th>
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Readiness</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Close</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Resistance</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Distance</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>ATR Contraction</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Vol Ratio</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Confirms Above</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.symbol} style={{ borderTop: `1px solid ${'var(--gridline)'}` }}>
              <td className="px-3 py-2.5">
                <div className="flex items-center gap-2">
                  <span className="font-medium" style={{ color: 'var(--text-primary)' }}>{row.name ?? row.symbol}</span>
                  {(row.universe === 'CUSTOM' || row.universe === 'NIFTY_500') && <UniverseTag universe={row.universe} />}
                </div>
                <div className="text-xs" style={{ color: 'var(--text-muted)' }}>{row.symbol}</div>
              </td>
              <td className="px-3 py-2.5"><NearBreakoutBadge classification={row.classification} /></td>
              <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-primary)' }}>{fmtPrice(row.currentPrice)}</td>
              <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>{fmtPrice(row.resistance)}</td>
              <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--status-warning)' }}>{row.distanceToResistancePct.toFixed(2)}%</td>
              <td className="tabular px-3 py-2.5 text-right" style={{ color: row.contracting ? 'var(--status-good)' : 'var(--text-secondary)' }}>
                {row.atrContractionRatio.toFixed(2)}×
              </td>
              <td className="tabular px-3 py-2.5 text-right" style={{ color: row.volumeDriedUp ? 'var(--status-good)' : 'var(--text-secondary)' }}>
                {row.volumeDryUpRatio.toFixed(2)}×
              </td>
              <td className="tabular px-3 py-2.5 text-right font-semibold" style={{ color: 'var(--status-good)' }}>
                {fmtPrice(row.breakoutConfirmLevel)}
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={8} className="px-4 py-8 text-center" style={{ color: 'var(--text-muted)' }}>
                No coiling setups right now — a stock needs to be trending, within 5% of its
                60-day resistance, and not yet confirmed above it.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

const SETUP_TYPE_META = {
  BREAKOUT_SETUP: { label: 'Breakout', color: 'var(--cat-nifty50)' },
  REVERSAL_SETUP: { label: 'Reversal', color: 'var(--cat-next50)' },
}

const REVERSAL_SORT_KEYS = {
  score: (r) => r.candlestickScore,
  rr: (r) => r.riskReward ?? -Infinity,
  volume: (r) => r.volumeRatio,
  fiftyTwoWeek: (r) => r.fiftyTwoWeekReturnPct ?? -Infinity,
}

/**
 * Candlestick-confirmed reversal setups — a flat table, not the row-expand pattern used for
 * breakouts, since there's no A-J checklist behind a reversal candidate. Sortable by the fields
 * that matter for triage: candlestick score, R:R, volume ratio, 6-month return.
 */
function ReversalTable({ rows }) {
  const [sortKey, setSortKey] = useState('score')
  const [sortDesc, setSortDesc] = useState(true)

  const sorted = useMemo(() => {
    const pick = REVERSAL_SORT_KEYS[sortKey]
    return [...rows].sort((a, b) => (sortDesc ? pick(b) - pick(a) : pick(a) - pick(b)))
  }, [rows, sortKey, sortDesc])

  function sortHeader(key, label, align = 'right') {
    return (
      <th
        className={`cursor-pointer select-none px-3 py-3 text-${align} text-xs font-semibold uppercase tracking-wide`}
        style={{ color: sortKey === key ? 'var(--text-primary)' : 'var(--text-muted)' }}
        onClick={() => (sortKey === key ? setSortDesc((s) => !s) : (setSortKey(key), setSortDesc(true)))}
      >
        {label} {sortKey === key ? (sortDesc ? '↓' : '↑') : ''}
      </th>
    )
  }

  return (
    <div className="overflow-x-auto rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <table className="w-full text-sm">
        <thead>
          <tr style={{ borderBottom: `1px solid ${'var(--gridline)'}` }}>
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Symbol</th>
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Setup</th>
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Pattern</th>
            {sortHeader('score', 'Score')}
            {sortHeader('fiftyTwoWeek', '52W Return')}
            {sortHeader('volume', 'Vol Ratio')}
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Support</th>
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Confirmation</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Entry</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Stop</th>
            <th className="px-3 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Target</th>
            {sortHeader('rr', 'R:R')}
            <th className="px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Decision</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => {
            const setupMeta = SETUP_TYPE_META[row.setupType] ?? SETUP_TYPE_META.REVERSAL_SETUP
            return (
              <tr key={row.symbol} style={{ borderTop: `1px solid ${'var(--gridline)'}` }}>
                <td className="px-3 py-2.5">
                  <div className="flex items-center gap-2">
                    <span className="font-medium" style={{ color: 'var(--text-primary)' }}>{row.name ?? row.symbol}</span>
                    {(row.universe === 'CUSTOM' || row.universe === 'NIFTY_500') && <UniverseTag universe={row.universe} />}
                  </div>
                  <div className="text-xs" style={{ color: 'var(--text-muted)' }}>{row.symbol}</div>
                </td>
                <td className="px-3 py-2.5">
                  <span className="inline-flex items-center gap-1.5 text-xs font-medium" style={{ color: 'var(--text-secondary)' }}>
                    <Dot color={setupMeta.color} size={6} />
                    {setupMeta.label}
                  </span>
                </td>
                <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>{row.patternLabel}</td>
                <td className="tabular px-3 py-2.5 text-right font-semibold" style={{ color: 'var(--text-primary)' }}>
                  {row.candlestickScore}/{row.candlestickScoreMax}
                </td>
                <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--status-critical)' }}>
                  {row.fiftyTwoWeekReturnPct != null ? `${row.fiftyTwoWeekReturnPct.toFixed(1)}%` : '—'}
                </td>
                <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>
                  {row.volumeRatio.toFixed(2)}×
                </td>
                <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                  {row.majorSupport != null
                    ? `₹${fmtPrice(row.majorSupport)} (${row.distanceFromSupportPct.toFixed(1)}%)`
                    : '—'}
                </td>
                <td className="px-3 py-2.5 text-xs" style={{ color: row.confirmed ? 'var(--status-good)' : 'var(--status-warning)' }}>
                  {row.confirmed ? '✓ Confirmed' : 'Pending'}
                </td>
                <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-primary)' }}>{fmtPrice(row.entry)}</td>
                <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--status-critical)' }}>
                  {row.stop != null ? fmtPrice(row.stop) : '—'}
                </td>
                <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--status-good)' }}>
                  {row.target != null ? fmtPrice(row.target) : '—'}
                </td>
                <td className="tabular px-3 py-2.5 text-right font-semibold" style={{ color: 'var(--text-primary)' }}>
                  {row.riskReward != null ? `${row.riskReward.toFixed(2)}:1` : '—'}
                </td>
                <td className="px-3 py-2.5">
                  <DecisionBadge classification={row.classification} />
                </td>
              </tr>
            )
          })}
          {rows.length === 0 && (
            <tr>
              <td colSpan={13} className="px-4 py-8 text-center" style={{ color: 'var(--text-muted)' }}>
                No reversal setups right now — a stock needs a ~52-week downtrend and a detected
                candlestick reversal pattern (hammer, bullish engulfing, or morning star).
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}

// ==================== TRADE JOURNAL ====================

function fmtMoney(v) {
  return v == null ? '—' : `₹${fmtPrice(v)}`
}

function fmtPct(v) {
  return v == null ? '—' : `${v.toFixed(1)}%`
}

function pnlColor(v) {
  if (v == null) return 'var(--text-primary)'
  return v > 0 ? 'var(--status-good)' : v < 0 ? 'var(--status-critical)' : 'var(--text-primary)'
}

/** Backend sends "Infinity" (a string) for ratios with a zero denominator — e.g. a month with
 *  wins but no losses yet. Never call .toFixed() on that directly; format through here instead. */
function fmtRatio(v) {
  if (v == null) return '—'
  if (v === 'Infinity' || v === Infinity) return '∞'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

function StatusBadge({ status }) {
  const open = status === 'Open'
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium" style={{ color: 'var(--text-primary)' }}>
      <Dot color={open ? 'var(--status-warning)' : 'var(--text-muted)'} size={6} />
      {status}
    </span>
  )
}

const RESULT_META = {
  WIN: { color: 'var(--status-good)', label: 'Profit' },
  LOSS: { color: 'var(--status-critical)', label: 'LOSS' },
  OPEN: { color: 'var(--status-warning)', label: 'OPEN' },
}

function ResultBadge({ result }) {
  const meta = RESULT_META[result] ?? RESULT_META.OPEN
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-semibold" style={{ color: meta.color }}>
      <Dot color={meta.color} size={6} />
      {meta.label}
    </span>
  )
}

/** Cumulative realized P/L, trade-indexed (not date-indexed — gaps between trades aren't
 *  meaningful for an equity curve), sorted by sell date. */
function useEquityCurve(rows) {
  return useMemo(() => {
    const closed = rows
      .filter((r) => r.pl != null && r.sellDate)
      .slice()
      .sort((a, b) => a.sellDate.localeCompare(b.sellDate))
    let cum = 0
    return closed.map((r) => {
      cum += r.pl
      return { stock: r.stock, date: r.sellDate, pl: r.pl, cum }
    })
  }, [rows])
}

/** A minimal, dependency-free equity-curve line chart: single series (no legend needed — the
 *  title names it), zero-reference baseline, hover crosshair + tooltip. */
function EquityCurveChart({ rows }) {
  const points = useEquityCurve(rows)
  const [hover, setHover] = useState(null)
  const width = 640
  const height = 180
  const padX = 14
  const padY = 16

  if (points.length < 2) {
    return (
      <div className="flex items-center justify-center text-sm" style={{ height, color: 'var(--text-muted)' }}>
        Close at least 2 trades to see an equity curve.
      </div>
    )
  }

  const cums = points.map((p) => p.cum)
  const minCum = Math.min(0, ...cums)
  const maxCum = Math.max(0, ...cums)
  const range = maxCum - minCum || 1
  const xFor = (i) => padX + (i / (points.length - 1)) * (width - 2 * padX)
  const yFor = (v) => padY + (1 - (v - minCum) / range) * (height - 2 * padY)
  const zeroY = yFor(0)

  const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${xFor(i).toFixed(1)} ${yFor(p.cum).toFixed(1)}`).join(' ')
  const areaPath = `${linePath} L ${xFor(points.length - 1).toFixed(1)} ${zeroY.toFixed(1)} L ${xFor(0).toFixed(1)} ${zeroY.toFixed(1)} Z`
  const finalCum = points[points.length - 1].cum
  const lineColor = finalCum >= 0 ? 'var(--status-good)' : 'var(--status-critical)'

  function handleMove(e) {
    const rect = e.currentTarget.getBoundingClientRect()
    const relX = ((e.clientX - rect.left) / rect.width) * width
    let nearest = 0
    let best = Infinity
    points.forEach((p, i) => {
      const d = Math.abs(xFor(i) - relX)
      if (d < best) { best = d; nearest = i }
    })
    setHover(nearest)
  }

  return (
    <div>
      <div className="relative">
        <svg
          viewBox={`0 0 ${width} ${height}`}
          className="w-full cursor-crosshair"
          style={{ height }}
          onMouseMove={handleMove}
          onMouseLeave={() => setHover(null)}
        >
          <line x1={padX} y1={zeroY} x2={width - padX} y2={zeroY} stroke="var(--gridline)" strokeWidth="1" strokeDasharray="3,3" />
          <path d={areaPath} fill={lineColor} opacity="0.1" stroke="none" />
          <path d={linePath} fill="none" stroke={lineColor} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          {hover != null && (
            <>
              <line x1={xFor(hover)} y1={padY} x2={xFor(hover)} y2={height - padY} stroke="var(--text-muted)" strokeWidth="1" strokeDasharray="2,2" />
              <circle cx={xFor(hover)} cy={yFor(points[hover].cum)} r="4.5" fill={pnlColor(points[hover].cum)} stroke="var(--surface-1)" strokeWidth="2" />
            </>
          )}
        </svg>
        {hover != null && (
          <div
            className="pointer-events-none absolute top-0 z-10 whitespace-nowrap rounded-lg border px-2.5 py-1.5 text-xs shadow-md"
            style={{
              borderColor: 'var(--border)', background: 'var(--surface-1)', color: 'var(--text-primary)',
              left: `${(xFor(hover) / width) * 100}%`,
              transform: `translateX(${hover > points.length / 2 ? '-108%' : '8%'})`,
            }}
          >
            <div className="font-semibold">{points[hover].stock}</div>
            <div style={{ color: 'var(--text-muted)' }}>{points[hover].date} &middot; {fmtMoney(points[hover].pl)}</div>
            <div className="tabular font-semibold" style={{ color: pnlColor(points[hover].cum) }}>
              Cumulative {fmtMoney(points[hover].cum)}
            </div>
          </div>
        )}
      </div>
      <div className="mt-1.5 flex items-center justify-between text-xs" style={{ color: 'var(--text-muted)' }}>
        <span>{points[0].date}</span>
        <span className="tabular font-semibold" style={{ color: lineColor }}>{fmtMoney(finalCum)} total</span>
        <span>{points[points.length - 1].date}</span>
      </div>
    </div>
  )
}

/**
 * A daily OHLCV candlestick chart with a volume strip underneath, resistance / breakout-confirm
 * reference lines, and a marker on the exact candle where the breakout was confirmed. Hand-built
 * SVG (no charting library loaded anywhere in this app), following the same hover-crosshair
 * pattern as EquityCurveChart above.
 */
function CandlestickChart({ bars, resistance, breakoutConfirmLevel, breakoutBarTime }) {
  const [hover, setHover] = useState(null)
  const width = 700
  const priceH = 200
  const volH = 46
  const gap = 8
  const height = priceH + gap + volH
  const padX = 6
  const padTop = 10
  const padBottom = 8

  if (!bars || bars.length < 2) {
    return (
      <div className="flex items-center justify-center text-sm" style={{ height: priceH, color: 'var(--text-muted)' }}>
        No chart data available.
      </div>
    )
  }

  const n = bars.length
  let maxP = Math.max(...bars.map((b) => b.high));
  let minP = Math.min(...bars.map((b) => b.low));
  if (resistance != null) maxP = Math.max(maxP, resistance)
  if (breakoutConfirmLevel != null) maxP = Math.max(maxP, breakoutConfirmLevel)
  const pad = (maxP - minP) * 0.06 || maxP * 0.02
  maxP += pad
  minP -= pad
  const priceRange = maxP - minP || 1
  const maxVol = Math.max(...bars.map((b) => b.volume)) || 1

  const slot = (width - 2 * padX) / n
  const candleW = Math.max(1.5, slot * 0.62)
  const xFor = (i) => padX + i * slot + slot / 2
  const yFor = (v) => padTop + (1 - (v - minP) / priceRange) * (priceH - padTop - padBottom)
  const volYFor = (v) => priceH + gap + volH - (v / maxVol) * (volH - 4)

  const breakoutIndex = breakoutBarTime != null ? bars.findIndex((b) => b.time === breakoutBarTime) : -1

  function handleMove(e) {
    const rect = e.currentTarget.getBoundingClientRect()
    const relX = ((e.clientX - rect.left) / rect.width) * width
    const idx = Math.min(n - 1, Math.max(0, Math.floor((relX - padX) / slot)))
    setHover(idx)
  }

  const dateLabel = (t) => new Date(t * 1000).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: '2-digit' })

  return (
    <div>
      <div className="relative">
        <svg
          viewBox={`0 0 ${width} ${height}`}
          className="w-full cursor-crosshair"
          style={{ height }}
          onMouseMove={handleMove}
          onMouseLeave={() => setHover(null)}
        >
          {resistance != null && (
            <>
              <line x1={padX} y1={yFor(resistance)} x2={width - padX} y2={yFor(resistance)}
                stroke="var(--status-warning)" strokeWidth="1" strokeDasharray="4,3" />
              <text x={width - padX} y={yFor(resistance) - 3} textAnchor="end" fontSize="9" fill="var(--status-warning)">
                Resistance {fmtPrice(resistance)}
              </text>
            </>
          )}
          {breakoutConfirmLevel != null && (
            <>
              <line x1={padX} y1={yFor(breakoutConfirmLevel)} x2={width - padX} y2={yFor(breakoutConfirmLevel)}
                stroke="var(--status-good)" strokeWidth="1" strokeDasharray="4,3" />
              <text x={width - padX} y={yFor(breakoutConfirmLevel) + 11} textAnchor="end" fontSize="9" fill="var(--status-good)">
                Confirms {fmtPrice(breakoutConfirmLevel)}
              </text>
            </>
          )}

          {bars.map((b, i) => {
            const up = b.close >= b.open
            const color = up ? 'var(--status-good)' : 'var(--status-critical)'
            const bodyTop = yFor(Math.max(b.open, b.close))
            const bodyBottom = yFor(Math.min(b.open, b.close))
            return (
              <g key={b.time}>
                <line x1={xFor(i)} y1={yFor(b.high)} x2={xFor(i)} y2={yFor(b.low)} stroke={color} strokeWidth="1" />
                <rect x={xFor(i) - candleW / 2} y={bodyTop} width={candleW} height={Math.max(1, bodyBottom - bodyTop)} fill={color} />
                <rect x={xFor(i) - candleW / 2} y={volYFor(b.volume)} width={candleW}
                  height={Math.max(1, priceH + gap + volH - volYFor(b.volume))} fill={color} opacity="0.5" />
              </g>
            )
          })}

          {breakoutIndex >= 0 && (
            <>
              <line x1={xFor(breakoutIndex)} y1={padTop} x2={xFor(breakoutIndex)} y2={priceH - padBottom}
                stroke="var(--accent)" strokeWidth="1.5" strokeDasharray="2,2" />
              <polygon
                points={`${xFor(breakoutIndex) - 5},${padTop - 1} ${xFor(breakoutIndex) + 5},${padTop - 1} ${xFor(breakoutIndex)},${padTop + 7}`}
                fill="var(--accent)"
              />
              <text x={xFor(breakoutIndex)} y={padTop - 3} textAnchor="middle" fontSize="9" fontWeight="600" fill="var(--accent)">
                Breakout
              </text>
            </>
          )}

          {hover != null && (
            <line x1={xFor(hover)} y1={padTop} x2={xFor(hover)} y2={priceH + gap + volH}
              stroke="var(--text-muted)" strokeWidth="1" strokeDasharray="2,2" />
          )}
        </svg>
        {hover != null && (
          <div
            className="pointer-events-none absolute top-0 z-10 whitespace-nowrap rounded-lg border px-2.5 py-1.5 text-xs shadow-md"
            style={{
              borderColor: 'var(--border)', background: 'var(--surface-1)', color: 'var(--text-primary)',
              left: `${(xFor(hover) / width) * 100}%`,
              transform: `translateX(${hover > n / 2 ? '-108%' : '8%'})`,
            }}
          >
            <div className="font-semibold">{dateLabel(bars[hover].time)}{hover === breakoutIndex ? ' — Breakout' : ''}</div>
            <div style={{ color: 'var(--text-muted)' }}>
              O {fmtPrice(bars[hover].open)} H {fmtPrice(bars[hover].high)} L {fmtPrice(bars[hover].low)} C {fmtPrice(bars[hover].close)}
            </div>
            <div className="tabular" style={{ color: 'var(--text-secondary)' }}>Vol {fmtVolume(bars[hover].volume)}</div>
          </div>
        )}
      </div>
      <div className="mt-1.5 flex items-center justify-between text-xs" style={{ color: 'var(--text-muted)' }}>
        <span>{dateLabel(bars[0].time)}</span>
        <span>{dateLabel(bars[n - 1].time)}</span>
      </div>
    </div>
  )
}

// Every entry is a range Yahoo still serves at daily granularity, so the candles on screen are
// always the same bars the scan's levels were computed from. (Its "max" range is not: it comes
// back as monthly candles regardless of the interval requested, so 10Y is as far back as we go.)
const CHART_RANGES = [
  { key: '1mo', label: '1M' },
  { key: '3mo', label: '3M' },
  { key: '6mo', label: '6M' },
  { key: '1y', label: '1Y' },
  { key: '5y', label: '5Y' },
  { key: '10y', label: '10Y' },
]

/** Lightweight Charts needs concrete color strings, so the themed custom properties get resolved. */
function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

/** Volume bars sit under the candles, so they take the same hue at reduced weight. */
function withAlpha(color, aa) {
  return /^#[0-9a-f]{6}$/i.test(color) ? color + aa : color
}

function chartTheme() {
  const muted = cssVar('--text-muted', '#898781')
  const grid = cssVar('--gridline', '#e1e0d9')
  const accent = cssVar('--accent', '#2a78d6')
  return {
    layout: { background: { color: cssVar('--surface-1', '#ffffff') }, textColor: muted },
    grid: { vertLines: { color: grid }, horzLines: { color: grid } },
    rightPriceScale: { borderColor: grid },
    timeScale: { borderColor: grid },
    crosshair: {
      vertLine: { color: muted, labelBackgroundColor: accent },
      horzLine: { color: muted, labelBackgroundColor: accent },
    },
  }
}

/**
 * Fullscreen price chart in the style of a broker app — timeframe tabs, a crosshair OHLC readout,
 * real pan/zoom — with this scan's own levels drawn on top. Uses TradingView's Lightweight Charts;
 * the inline `CandlestickChart` above stays hand-rolled SVG because at 700px it only ever needs to
 * be glanced at, whereas this one is meant to be worked in.
 */
function FullChartModal({ row, onClose }) {
  const symbol = row.symbol
  const resistance = row.values['Prev resistance']
  const breakoutConfirmLevel = row.values['Breakout Confirm Level']
  const breakoutBarTime = row.values['Breakout Bar Time']

  const [range, setRange] = useState('6mo')
  const [bars, setBars] = useState(null)
  const [error, setError] = useState(null)
  const [hover, setHover] = useState(null)

  const containerRef = useRef(null)
  const chartRef = useRef(null)
  const candleRef = useRef(null)
  const volumeRef = useRef(null)
  const markersRef = useRef(null)
  const priceLinesRef = useRef([])

  // Esc closes; the page behind must not scroll while the overlay is up.
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [onClose])

  useEffect(() => {
    let cancelled = false
    setBars(null)
    setError(null)
    fetch(`/api/chart?symbol=${encodeURIComponent(symbol)}&range=${range}`)
      .then((r) => r.json())
      .then((body) => {
        if (cancelled) return
        if (body.error) setError(body.error)
        else setBars(body.bars)
      })
      .catch((e) => { if (!cancelled) setError(e.message || 'Could not load chart') })
    return () => { cancelled = true }
  }, [symbol, range])

  // Built once. Timeframe switches only replace series data below, so the chart instance — and
  // the user's pan/zoom — survives them.
  useEffect(() => {
    const up = cssVar('--status-good', '#0ca30c')
    const down = cssVar('--status-critical', '#d13438')

    const chart = createChart(containerRef.current, { autoSize: true, ...chartTheme() })
    const candles = chart.addSeries(CandlestickSeries, {
      upColor: up, downColor: down,
      borderUpColor: up, borderDownColor: down,
      wickUpColor: up, wickDownColor: down,
    })
    candles.priceScale().applyOptions({ scaleMargins: { top: 0.08, bottom: 0.28 } })

    const volume = chart.addSeries(HistogramSeries, {
      priceFormat: { type: 'volume' },
      priceScaleId: '',            // overlay scale, so volume keeps its own strip at the bottom
      lastValueVisible: false,
      priceLineVisible: false,
    })
    volume.priceScale().applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })

    markersRef.current = createSeriesMarkers(candles, [])

    chart.subscribeCrosshairMove((param) => {
      const candle = param.seriesData && param.seriesData.get(candles)
      if (!candle) { setHover(null); return }
      const vol = param.seriesData.get(volume)
      setHover({ ...candle, time: param.time, volume: vol ? vol.value : null })
    })

    chartRef.current = chart
    candleRef.current = candles
    volumeRef.current = volume
    return () => {
      chart.remove()
      chartRef.current = null
      candleRef.current = null
      volumeRef.current = null
      markersRef.current = null
      priceLinesRef.current = []
    }
  }, [])

  // The app follows the OS color scheme rather than an in-app toggle, so re-theme on that change.
  useEffect(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      if (!chartRef.current) return
      chartRef.current.applyOptions(chartTheme())
      const up = cssVar('--status-good', '#0ca30c')
      const down = cssVar('--status-critical', '#d13438')
      candleRef.current.applyOptions({
        upColor: up, downColor: down,
        borderUpColor: up, borderDownColor: down,
        wickUpColor: up, wickDownColor: down,
      })
    }
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])

  useEffect(() => {
    if (!bars || !candleRef.current) return
    const candles = candleRef.current
    const up = cssVar('--status-good', '#0ca30c')
    const down = cssVar('--status-critical', '#d13438')

    candles.setData(bars.map((b) => ({
      time: b.time, open: b.open, high: b.high, low: b.low, close: b.close,
    })))
    volumeRef.current.setData(bars.map((b) => ({
      time: b.time,
      value: b.volume,
      color: withAlpha(b.close >= b.open ? up : down, '66'),
    })))

    // Levels belong to the series, so replacing series data drops them — re-add each time.
    priceLinesRef.current.forEach((line) => candles.removePriceLine(line))
    priceLinesRef.current = []
    const addLevel = (price, color, title) => {
      if (price == null) return
      priceLinesRef.current.push(candles.createPriceLine({
        price, color, title, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: true,
      }))
    }
    addLevel(resistance, cssVar('--status-warning', '#c98500'), 'Resistance')
    addLevel(breakoutConfirmLevel, up, 'Confirms')

    // A short timeframe can start after the breakout bar, in which case there is nothing to mark.
    const hasBreakoutBar = breakoutBarTime != null && bars.some((b) => b.time === breakoutBarTime)
    markersRef.current.setMarkers(hasBreakoutBar ? [{
      time: breakoutBarTime,
      position: 'aboveBar',
      color: cssVar('--accent', '#2a78d6'),
      shape: 'arrowDown',
      text: 'Breakout',
    }] : [])

    chartRef.current.timeScale().fitContent()
  }, [bars, resistance, breakoutConfirmLevel, breakoutBarTime])

  const last = bars && bars.length ? bars[bars.length - 1] : null
  const prev = bars && bars.length > 1 ? bars[bars.length - 2] : null
  const change = last && prev ? last.close - prev.close : null
  const changePct = change != null && prev.close ? (change / prev.close) * 100 : null
  const changeColor = change == null ? 'var(--text-muted)'
    : change >= 0 ? 'var(--status-good)' : 'var(--status-critical)'

  const shown = hover || last
  const dateLabel = (t) => new Date(t * 1000).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })

  // Portalled to <body>: the detail panel that opens this sits inside a <td>, and a fullscreen
  // overlay has no business inheriting the table's stacking and overflow context.
  return createPortal(
    <div
      className="fixed inset-0 z-50 flex flex-col"
      style={{ background: 'var(--surface-1)' }}
      role="dialog"
      aria-modal="true"
      aria-label={`${symbol} price chart`}
    >
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b px-4 py-3" style={{ borderColor: 'var(--gridline)' }}>
        <div>
          <div className="text-base font-semibold" style={{ color: 'var(--text-primary)' }}>{symbol}</div>
          {last && (
            <div className="tabular flex items-baseline gap-2 text-sm">
              <span style={{ color: 'var(--text-primary)' }}>{fmtPrice(last.close)}</span>
              {changePct != null && (
                <span style={{ color: changeColor }}>
                  {change >= 0 ? '+' : ''}{fmtPrice(change)} ({change >= 0 ? '+' : ''}{changePct.toFixed(2)}%)
                </span>
              )}
            </div>
          )}
        </div>

        <div className="flex gap-1">
          {CHART_RANGES.map((r) => (
            <FilterPill key={r.key} active={range === r.key} onClick={() => setRange(r.key)}>
              {r.label}
            </FilterPill>
          ))}
        </div>

        <div className="ml-auto flex items-center gap-3">
          <span className="hidden text-xs sm:inline" style={{ color: 'var(--text-muted)' }}>Esc to close</span>
          <button
            onClick={onClose}
            className="rounded-md border px-2.5 py-1 text-sm transition-opacity hover:opacity-70"
            style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)' }}
            aria-label="Close chart"
          >
            ✕
          </button>
        </div>
      </div>

      <div className="relative flex-1">
        <div ref={containerRef} className="absolute inset-0" />

        {shown && (
          <div
            className="pointer-events-none absolute left-3 top-3 z-10 rounded-lg border px-2.5 py-1.5 text-xs shadow-sm"
            style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', color: 'var(--text-primary)' }}
          >
            <div className="font-semibold">{dateLabel(shown.time)}</div>
            <div className="tabular" style={{ color: 'var(--text-muted)' }}>
              O {fmtPrice(shown.open)} H {fmtPrice(shown.high)} L {fmtPrice(shown.low)} C {fmtPrice(shown.close)}
            </div>
            {shown.volume != null && (
              <div className="tabular" style={{ color: 'var(--text-secondary)' }}>Vol {fmtVolume(shown.volume)}</div>
            )}
          </div>
        )}

        {(error || !bars) && (
          <div
            className="absolute inset-0 z-20 flex items-center justify-center text-sm"
            style={{ background: 'var(--surface-1)', color: error ? 'var(--status-serious)' : 'var(--text-muted)' }}
          >
            {error || 'Loading chart…'}
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-x-5 gap-y-1 border-t px-4 py-2 text-xs" style={{ borderColor: 'var(--gridline)', color: 'var(--text-secondary)' }}>
        {resistance != null && (
          <span className="flex items-center gap-1.5"><Dot color="var(--status-warning)" size={7} /> Resistance {fmtPrice(resistance)}</span>
        )}
        {breakoutConfirmLevel != null && (
          <span className="flex items-center gap-1.5"><Dot color="var(--status-good)" size={7} /> Confirms {fmtPrice(breakoutConfirmLevel)}</span>
        )}
        {breakoutBarTime != null && (
          <span className="flex items-center gap-1.5"><Dot color="var(--accent)" size={7} /> Breakout bar {dateLabel(breakoutBarTime)}</span>
        )}
        <span className="ml-auto" style={{ color: 'var(--text-muted)' }}>Daily candles · Yahoo Finance</span>
      </div>
    </div>,
    document.body,
  )
}


/** Win/loss split as a single proportion bar — two status colors, direct labels, no legend box
 *  needed (each segment carries its own label + dot, so identity is never color-alone). */
function WinLossBar({ wins, losses }) {
  const total = wins + losses
  if (total === 0) {
    return (
      <div className="flex items-center justify-center text-sm" style={{ height: 180, color: 'var(--text-muted)' }}>
        No closed trades yet.
      </div>
    )
  }
  const winPct = (wins / total) * 100
  const lossPct = (losses / total) * 100
  return (
    <div className="flex flex-col justify-center" style={{ minHeight: 180 }}>
      <div className="tabular text-center text-3xl font-bold" style={{ color: winPct >= 50 ? 'var(--status-good)' : 'var(--status-critical)' }}>
        {winPct.toFixed(0)}%
      </div>
      <div className="mb-4 text-center text-xs" style={{ color: 'var(--text-muted)' }}>win rate</div>
      <div className="flex h-3 overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
        {wins > 0 && <div style={{ width: `${winPct}%`, background: 'var(--status-good)' }} />}
        {wins > 0 && losses > 0 && <div style={{ width: 2, background: 'var(--surface-1)' }} />}
        {losses > 0 && <div style={{ width: `${lossPct}%`, background: 'var(--status-critical)' }} />}
      </div>
      <div className="mt-2.5 flex items-center justify-between text-xs">
        <span className="flex items-center gap-1.5" style={{ color: 'var(--text-secondary)' }}>
          <Dot color="var(--status-good)" size={7} /> {wins} win{wins !== 1 ? 's' : ''}
        </span>
        <span className="flex items-center gap-1.5" style={{ color: 'var(--text-secondary)' }}>
          {losses} loss{losses !== 1 ? 'es' : ''} <Dot color="var(--status-critical)" size={7} />
        </span>
      </div>
    </div>
  )
}

function JournalCharts({ rows, dashboard }) {
  if (!dashboard) return null
  return (
    <div className="mb-6 flex flex-col gap-3 lg:flex-row">
      <div className="flex-[2] rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Equity Curve &middot; Closed Trades
        </h4>
        <EquityCurveChart rows={rows} />
      </div>
      <div className="flex-1 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Win / Loss Split
        </h4>
        <WinLossBar wins={dashboard.tradeCount.wins} losses={dashboard.tradeCount.losses} />
      </div>
    </div>
  )
}

/** 1:2 risk/reward math — a fixed 2x-risk target, matching the scanner's own trade-plan convention. */
function computeRiskReward({ entry, stopLoss, capital, riskPct }) {
  const riskPerShare = entry - stopLoss
  if (!(riskPerShare > 0) || !(capital > 0) || !(riskPct > 0)) return null
  const riskAmount = capital * (riskPct / 100)
  const qty = Math.floor(riskAmount / riskPerShare)
  if (qty <= 0) return null
  const target = entry + 2 * riskPerShare
  return {
    riskPerShare,
    riskAmount,
    qty,
    target,
    invested: qty * entry,
    potentialLoss: qty * riskPerShare,
    potentialProfit: qty * (target - entry),
  }
}

function CalcField({ label, value, onChange, step = '0.01' }) {
  return (
    <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
      {label}
      <input
        type="number"
        step={step}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-lg border px-2.5 py-1.5 text-sm outline-none focus:ring-2"
        style={{ ...inputStyle, '--tw-ring-color': 'var(--accent)' }}
      />
    </label>
  )
}

/**
 * Standalone 1:2 R:R position-size calculator. Also usable from the Trade Journal's "add trade"
 * form via onUseInTrade, which carries entry/stop/target/qty straight into that form's state.
 */
function RiskRewardCalculator({ onUseInTrade }) {
  const [entry, setEntry] = useState('')
  const [stopLoss, setStopLoss] = useState('')
  const [capital, setCapital] = useState('')
  const [riskPct, setRiskPct] = useState('1')

  const result = useMemo(
    () => computeRiskReward({
      entry: parseFloat(entry), stopLoss: parseFloat(stopLoss),
      capital: parseFloat(capital), riskPct: parseFloat(riskPct),
    }),
    [entry, stopLoss, capital, riskPct]
  )

  return (
    <div className="rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <h3 className="mb-3 flex items-center gap-1.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
        <span aria-hidden="true">🧮</span> 1:2 Risk/Reward Calculator
      </h3>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <CalcField label="Entry" value={entry} onChange={setEntry} />
        <CalcField label="Stop-loss" value={stopLoss} onChange={setStopLoss} />
        <CalcField label="Capital" value={capital} onChange={setCapital} step="1" />
        <CalcField label="Risk % / trade" value={riskPct} onChange={setRiskPct} step="0.1" />
      </div>
      {result ? (
        <>
          <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2 border-t pt-3 text-sm sm:grid-cols-3" style={{ borderColor: 'var(--gridline)' }}>
            <div><span style={{ color: 'var(--text-muted)' }}>Qty: </span><span className="tabular font-semibold" style={{ color: 'var(--text-primary)' }}>{result.qty}</span></div>
            <div><span style={{ color: 'var(--text-muted)' }}>Target: </span><span className="tabular font-semibold" style={{ color: 'var(--status-good)' }}>{fmtMoney(result.target)}</span></div>
            <div><span style={{ color: 'var(--text-muted)' }}>Invested: </span><span className="tabular font-semibold" style={{ color: 'var(--text-primary)' }}>{fmtMoney(result.invested)}</span></div>
            <div><span style={{ color: 'var(--text-muted)' }}>Risk amount: </span><span className="tabular font-semibold" style={{ color: 'var(--status-critical)' }}>{fmtMoney(result.potentialLoss)}</span></div>
            <div><span style={{ color: 'var(--text-muted)' }}>Potential profit: </span><span className="tabular font-semibold" style={{ color: 'var(--status-good)' }}>{fmtMoney(result.potentialProfit)}</span></div>
            <div><span style={{ color: 'var(--text-muted)' }}>R:R: </span><span className="tabular font-semibold" style={{ color: 'var(--text-primary)' }}>2.00:1</span></div>
          </div>
          {onUseInTrade && (
            <button
              onClick={() => onUseInTrade({
                entry: parseFloat(entry), stopLoss: parseFloat(stopLoss), target: result.target, qty: result.qty,
              })}
              className="mt-3 rounded-lg px-4 py-1.5 text-sm font-semibold text-white transition-opacity hover:opacity-90"
              style={{ background: 'var(--accent)' }}
            >
              Use in new trade
            </button>
          )}
        </>
      ) : (
        <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>
          Enter entry, a stop-loss below entry, capital, and risk % to compute quantity and the 1:2 target.
        </p>
      )}
    </div>
  )
}

/** A KPI card with a "hero" headline metric up top and compact secondary stats below a divider —
 *  the first number a reader should see gets the visual weight, the rest is reference detail. */
function DashboardCard({ title, icon, accent, headline, rows }) {
  return (
    <div className="flex-1 overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', minWidth: 240 }}>
      <div style={{ height: 3, background: accent ?? 'var(--accent)' }} />
      <div className="p-4">
        <h4 className="mb-3 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          {icon && <span aria-hidden="true">{icon}</span>} {title}
        </h4>
        {headline && (
          <div className="mb-3">
            <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>{headline.label}</div>
            <div className="tabular text-2xl font-bold" style={{ color: headline.color ?? 'var(--text-primary)' }}>{headline.value}</div>
          </div>
        )}
        <dl className="space-y-1.5 border-t pt-2.5" style={{ borderColor: 'var(--gridline)' }}>
          {rows.map(([label, value, color]) => (
            <div key={label} className="flex items-baseline justify-between gap-3 text-sm">
              <dt style={{ color: 'var(--text-secondary)' }}>{label}</dt>
              <dd className="tabular font-semibold" style={{ color: color ?? 'var(--text-primary)' }}>{value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  )
}

function JournalDashboard({ d }) {
  if (!d) return null
  const cp = d.capitalAndPl, pq = d.performanceQuality, tc = d.tradeCount, em = d.edgeMetrics
  return (
    <div className="mb-3 flex flex-wrap gap-3">
      <DashboardCard
        title="Capital & P/L" icon="💰" accent={pnlColor(cp.realizedPl)}
        headline={{ label: 'Realized P/L', value: fmtMoney(cp.realizedPl), color: pnlColor(cp.realizedPl) }}
        rows={[
          ['Return on closed capital', fmtPct(cp.returnOnClosedCapitalPct), pnlColor(cp.returnOnClosedCapitalPct)],
          ['Capital deployed (open)', fmtMoney(cp.capitalDeployedOpen)],
          ['Capital at risk (open)', fmtMoney(cp.capitalAtRiskOpen)],
          ['Total capital invested', fmtMoney(cp.totalCapitalInvested)],
        ]}
      />
      <DashboardCard
        title="Performance Quality" icon="🎯"
        headline={{ label: 'Win rate', value: fmtPct(pq.winRatePct) }}
        rows={[
          ['Avg win', fmtMoney(pq.avgWin), 'var(--status-good)'],
          ['Avg loss', fmtMoney(pq.avgLoss), 'var(--status-critical)'],
          ['Largest win', fmtMoney(pq.largestWin), 'var(--status-good)'],
          ['Largest loss', fmtMoney(pq.largestLoss), 'var(--status-critical)'],
        ]}
      />
      <DashboardCard
        title="Trade Count" icon="📋"
        headline={{ label: 'Total trades', value: tc.total }}
        rows={[
          ['Closed', tc.closed],
          ['Open', tc.open],
          ['Wins', tc.wins, 'var(--status-good)'],
          ['Losses', tc.losses, 'var(--status-critical)'],
        ]}
      />
      <DashboardCard
        title="Edge Metrics" icon="⚡" accent={pnlColor(em.expectancyPerTrade)}
        headline={{ label: 'Expectancy / trade', value: fmtMoney(em.expectancyPerTrade), color: pnlColor(em.expectancyPerTrade) }}
        rows={[
          ['Profit factor', fmtRatio(em.profitFactor)],
          ['Win : Loss ratio', fmtRatio(em.winLossRatio)],
          ['Avg hold (closed days)', em.avgHoldClosedDays.toFixed(1)],
          ['Risk on open capital', fmtPct(em.riskOnOpenCapitalPct)],
        ]}
      />
    </div>
  )
}

function AddTradeForm({ form, setForm, onSubmit, error }) {
  return (
    <form onSubmit={onSubmit} className="rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <h3 className="mb-3 flex items-center gap-1.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
        <span aria-hidden="true">➕</span> Add trade
      </h3>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          Stock
          <input required value={form.stock} onChange={(e) => setForm((f) => ({ ...f, stock: e.target.value }))}
            className="rounded-lg border px-2.5 py-1.5 text-sm" style={inputStyle} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          Buy date
          <input required type="date" value={form.buyDate} onChange={(e) => setForm((f) => ({ ...f, buyDate: e.target.value }))}
            className="rounded-lg border px-2.5 py-1.5 text-sm" style={inputStyle} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          Qty
          <input required type="number" value={form.qty} onChange={(e) => setForm((f) => ({ ...f, qty: e.target.value }))}
            className="rounded-lg border px-2.5 py-1.5 text-sm" style={inputStyle} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          Buy price
          <input required type="number" step="0.01" value={form.buyPrice} onChange={(e) => setForm((f) => ({ ...f, buyPrice: e.target.value }))}
            className="rounded-lg border px-2.5 py-1.5 text-sm" style={inputStyle} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          Stop-loss
          <input type="number" step="0.01" value={form.stopLoss} onChange={(e) => setForm((f) => ({ ...f, stopLoss: e.target.value }))}
            className="rounded-lg border px-2.5 py-1.5 text-sm" style={inputStyle} />
        </label>
        <label className="flex flex-col gap-1 text-xs font-medium" style={{ color: 'var(--text-muted)' }}>
          Target
          <input type="number" step="0.01" value={form.target} onChange={(e) => setForm((f) => ({ ...f, target: e.target.value }))}
            className="rounded-lg border px-2.5 py-1.5 text-sm" style={inputStyle} />
        </label>
      </div>
      <div className="mt-3 flex items-center gap-3">
        <button type="submit" className="rounded-lg px-4 py-1.5 text-sm font-semibold text-white transition-opacity hover:opacity-90" style={{ background: 'var(--accent)' }}>
          Add trade
        </button>
        {error && <span className="text-sm" style={{ color: 'var(--status-serious)' }}>{error}</span>}
      </div>
    </form>
  )
}

const EMPTY_TRADE_FORM = { stock: '', buyDate: '', qty: '', buyPrice: '', stopLoss: '', target: '' }

/** "2026-08" -> "Aug 2026". */
function monthLabel(m) {
  const [y, mo] = m.split('-').map(Number)
  return new Date(y, mo - 1, 1).toLocaleDateString('en-US', { month: 'short', year: 'numeric' })
}

function TradeJournalView() {
  const [rows, setRows] = useState([])
  const [dashboard, setDashboardData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(null)
  const [form, setForm] = useState(EMPTY_TRADE_FORM)
  const [formError, setFormError] = useState(null)
  const [closingId, setClosingId] = useState(null)
  const [closeForm, setCloseForm] = useState({ sellDate: '', sellPrice: '' })
  const [selectedMonth, setSelectedMonth] = useState('overall')

  // Months a trade could belong to, from its buy date — newest first.
  const months = useMemo(() => {
    const set = new Set(rows.map((r) => r.buyDate.slice(0, 7)))
    return [...set].sort().reverse()
  }, [rows])

  // The dashboard cards/charts/table below all scope to whichever month is selected.
  const scopedRows = useMemo(() => {
    if (selectedMonth === 'overall') return rows
    return rows.filter((r) => r.buyDate.slice(0, 7) === selectedMonth)
  }, [rows, selectedMonth])

  async function loadRows() {
    setLoading(true)
    setLoadError(null)
    try {
      const rowsBody = await fetch('/api/journal').then((r) => r.json())
      setRows(rowsBody)
    } catch (e) {
      setLoadError(e.message || 'Could not reach the API server')
    } finally {
      setLoading(false)
    }
  }

  async function reload() {
    await loadRows()
  }

  useEffect(() => {
    loadRows()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Re-fetch the (possibly month-scoped) dashboard whenever the month changes or the trade
  // list changes (add/close/delete) — the dashboard's own aggregation always runs server-side.
  useEffect(() => {
    const qs = selectedMonth === 'overall' ? '' : `?month=${selectedMonth}`
    fetch(`/api/journal/dashboard${qs}`)
      .then((r) => r.json())
      .then(setDashboardData)
      .catch((e) => setLoadError(e.message || 'Could not reach the API server'))
  }, [selectedMonth, rows])

  async function submitAdd(e) {
    e.preventDefault()
    setFormError(null)
    try {
      const res = await fetch('/api/journal', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          stock: form.stock,
          buyDate: form.buyDate,
          qty: parseFloat(form.qty),
          buyPrice: parseFloat(form.buyPrice),
          stopLoss: form.stopLoss ? parseFloat(form.stopLoss) : null,
          target: form.target ? parseFloat(form.target) : null,
        }),
      })
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
      setForm(EMPTY_TRADE_FORM)
      await reload()
    } catch (e) {
      setFormError(e.message || 'Could not add trade')
    }
  }

  async function submitClose(id) {
    setFormError(null)
    try {
      const res = await fetch(`/api/journal/${id}/close`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sellDate: closeForm.sellDate, sellPrice: parseFloat(closeForm.sellPrice) }),
      })
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
      setClosingId(null)
      setCloseForm({ sellDate: '', sellPrice: '' })
      await reload()
    } catch (e) {
      setFormError(e.message || 'Could not close trade')
    }
  }

  async function removeTrade(id) {
    await fetch(`/api/journal/${id}`, { method: 'DELETE' })
    await reload()
  }

  function useCalcInForm({ entry, stopLoss, target, qty }) {
    setForm((f) => ({
      ...f,
      buyPrice: Number.isFinite(entry) ? String(entry) : f.buyPrice,
      stopLoss: Number.isFinite(stopLoss) ? String(stopLoss) : f.stopLoss,
      target: Number.isFinite(target) ? String(target) : f.target,
      qty: Number.isFinite(qty) ? String(qty) : f.qty,
    }))
  }

  if (loading && rows.length === 0 && !dashboard) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-16">
        <div className="animate-pulse" style={{ color: 'var(--accent)' }}>
          <Logo size={30} />
        </div>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading trade journal…</p>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-1.5">
        <FilterPill active={selectedMonth === 'overall'} onClick={() => setSelectedMonth('overall')}>
          All
        </FilterPill>
        {months.map((m) => (
          <FilterPill key={m} active={selectedMonth === m} onClick={() => setSelectedMonth(m)}>
            {monthLabel(m)}
          </FilterPill>
        ))}
      </div>

      <JournalDashboard d={dashboard} />
      <JournalCharts rows={scopedRows} dashboard={dashboard} />
      <RiskRewardCalculator onUseInTrade={useCalcInForm} />
      <AddTradeForm form={form} setForm={setForm} onSubmit={submitAdd} error={formError} />

      {loadError && <p className="text-sm" style={{ color: 'var(--status-serious)' }}>{loadError}</p>}

      <div>
        <h3 className="mb-2.5 flex items-center gap-1.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
          <span aria-hidden="true">📒</span> Trades{selectedMonth !== 'overall' ? ` — ${monthLabel(selectedMonth)}` : ''}
        </h3>
        <div className="overflow-x-auto rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <table className="w-full text-sm">
            <thead>
              <tr style={{ borderBottom: `1px solid ${'var(--gridline)'}` }}>
                {['Stock', 'Status', 'Buy Date', 'Sell Date', 'Hold Days', 'Qty', 'Buy Price', 'Sell Price',
                  'Stop', 'Target', 'Invested', 'Exit Value', 'P/L', 'P/L %', 'Result', ''].map((h) => (
                  <th key={h} className="whitespace-nowrap px-3 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {scopedRows.map((row, i) => (
                <tr
                  key={row.id}
                  style={{ borderTop: `1px solid ${'var(--gridline)'}`, background: i % 2 === 1 ? 'var(--page-plane)' : 'transparent' }}
                >
                  <td className="whitespace-nowrap px-3 py-2.5 font-medium" style={{ color: 'var(--text-primary)' }}>{row.stock}</td>
                  <td className="whitespace-nowrap px-3 py-2.5"><StatusBadge status={row.status} /></td>
                  <td className="whitespace-nowrap px-3 py-2.5" style={{ color: 'var(--text-secondary)' }}>{row.buyDate}</td>
                  <td className="whitespace-nowrap px-3 py-2.5" style={{ color: 'var(--text-secondary)' }}>{row.sellDate ?? '—'}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--text-secondary)' }}>{row.holdDays}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--text-secondary)' }}>{row.qty}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--text-primary)' }}>{fmtPrice(row.buyPrice)}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--text-primary)' }}>{row.sellPrice != null ? fmtPrice(row.sellPrice) : '—'}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--status-critical)' }}>{row.stopLoss != null ? fmtPrice(row.stopLoss) : '—'}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--status-good)' }}>{row.target != null ? fmtPrice(row.target) : '—'}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--text-secondary)' }}>{fmtPrice(row.invested)}</td>
                  <td className="tabular px-3 py-2.5" style={{ color: 'var(--text-secondary)' }}>{row.exitValue != null ? fmtPrice(row.exitValue) : '—'}</td>
                  <td className="tabular px-3 py-2.5 font-semibold" style={{ color: pnlColor(row.pl) }}>{row.pl != null ? fmtMoney(row.pl) : '—'}</td>
                  <td className="tabular px-3 py-2.5 font-semibold" style={{ color: pnlColor(row.plPct) }}>{row.plPct != null ? fmtPct(row.plPct) : '—'}</td>
                  <td className="whitespace-nowrap px-3 py-2.5"><ResultBadge result={row.result} /></td>
                  <td className="whitespace-nowrap px-3 py-2.5 text-right">
                    {row.status === 'Open' && closingId !== row.id && (
                      <button onClick={() => { setClosingId(row.id); setCloseForm({ sellDate: '', sellPrice: '' }) }}
                        className="mr-2 text-xs font-semibold transition-opacity hover:opacity-70" style={{ color: 'var(--accent)' }}>
                        Close
                      </button>
                    )}
                    <button onClick={() => removeTrade(row.id)} className="transition-opacity hover:opacity-70" style={{ color: 'var(--text-muted)' }} title="Delete">✕</button>
                    {closingId === row.id && (
                      <div className="mt-2 flex items-center gap-1.5">
                        <input type="date" value={closeForm.sellDate} onChange={(e) => setCloseForm((f) => ({ ...f, sellDate: e.target.value }))}
                          className="rounded border px-1.5 py-1 text-xs" style={inputStyle} />
                        <input type="number" step="0.01" placeholder="Sell price" value={closeForm.sellPrice}
                          onChange={(e) => setCloseForm((f) => ({ ...f, sellPrice: e.target.value }))}
                          className="w-24 rounded border px-1.5 py-1 text-xs" style={inputStyle} />
                        <button onClick={() => submitClose(row.id)} className="rounded px-2 py-1 text-xs font-semibold text-white transition-opacity hover:opacity-90" style={{ background: 'var(--accent)' }}>
                          Save
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
              {scopedRows.length === 0 && !loading && (
                <tr>
                  <td colSpan={16} className="px-4 py-12 text-center" style={{ color: 'var(--text-muted)' }}>
                    <div className="mx-auto mb-2 text-2xl" aria-hidden="true">📭</div>
                    {selectedMonth === 'overall'
                      ? 'No trades logged yet — add your first one above.'
                      : `No trades entered in ${monthLabel(selectedMonth)}.`}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

export default function App() {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [view, setView] = useState('breakout')
  const [decision, setDecision] = useState('ALL')
  const [universe, setUniverse] = useState('ALL')
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState(null)
  const [sortDesc, setSortDesc] = useState(true)

  const [customRows, setCustomRows] = useState([])
  const [customInput, setCustomInput] = useState('')
  const [customLoading, setCustomLoading] = useState(false)
  const [customError, setCustomError] = useState(null)

  const [refreshing, setRefreshing] = useState(false)
  const [refreshProgress, setRefreshProgress] = useState(null)
  const [refreshError, setRefreshError] = useState(null)
  const pollRef = useRef(null)

  function loadResults() {
    return fetch('/api/results')
      .then((r) => {
        if (!r.ok) throw new Error('no scan results yet')
        return r.json()
      })
      .then((d) => {
        setData(d)
        setError(null)
      })
  }

  useEffect(() => {
    loadResults().catch((e) => setError(e.message))
    return () => clearInterval(pollRef.current)
  }, [])

  async function refreshAll() {
    if (refreshing) return
    setRefreshError(null)
    setRefreshing(true)
    setRefreshProgress('Starting scan…')
    try {
      const res = await fetch('/api/scan', { method: 'POST' })
      if (res.status !== 202 && res.status !== 409) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.error ?? `HTTP ${res.status}`)
      }
      pollRef.current = setInterval(async () => {
        try {
          const s = await fetch('/api/scan/status').then((r) => r.json())
          if (s.running) {
            setRefreshProgress(s.progress ?? 'Scanning…')
            return
          }
          clearInterval(pollRef.current)
          if (s.lastResult?.error) {
            setRefreshError(s.lastResult.error)
          } else {
            await loadResults()
            const saved = loadSavedSymbols()
            saved.forEach((sym) => fetchCustom(sym, { silent: true }))
          }
          setRefreshing(false)
          setRefreshProgress(null)
        } catch (e) {
          clearInterval(pollRef.current)
          setRefreshing(false)
          setRefreshProgress(null)
          setRefreshError(e.message || 'Lost connection to the API server')
        }
      }, 1500)
    } catch (e) {
      setRefreshing(false)
      setRefreshProgress(null)
      setRefreshError(e.message || 'Could not reach the API server')
    }
  }

  useEffect(() => {
    const saved = loadSavedSymbols()
    saved.forEach((sym) => fetchCustom(sym, { silent: true }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function fetchCustom(rawSymbol, { silent = false } = {}) {
    const symbol = rawSymbol.trim().toUpperCase()
    if (!symbol) return
    setCustomLoading(!silent)
    setCustomError(null)
    try {
      const res = await fetch(`/api/analyze?symbol=${encodeURIComponent(symbol)}`)
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
      setCustomRows((prev) => [body, ...prev.filter((r) => r.symbol !== body.symbol)])
      const saved = loadSavedSymbols()
      if (!saved.includes(body.symbol)) saveSymbols([...saved, body.symbol])
    } catch (e) {
      if (!silent) setCustomError(e.message || 'Could not reach the API server')
    } finally {
      setCustomLoading(false)
    }
  }

  function removeCustom(symbol) {
    setCustomRows((prev) => prev.filter((r) => r.symbol !== symbol))
    saveSymbols(loadSavedSymbols().filter((s) => s !== symbol))
  }

  const allRows = useMemo(() => {
    const base = data?.results ?? []
    return [...customRows, ...base]
  }, [data, customRows])

  const rows = useMemo(() => {
    let r = allRows
    if (decision !== 'ALL') r = r.filter((row) => decisionOf(row.classification) === decision)
    if (universe !== 'ALL') r = r.filter((row) => (row.universe ?? 'NIFTY_50') === universe)
    if (query.trim()) {
      const q = query.trim().toUpperCase()
      r = r.filter(
        (row) => row.symbol.toUpperCase().includes(q) || (row.name ?? '').toUpperCase().includes(q)
      )
    }
    return [...r].sort((a, b) => {
      const diff = sortDesc ? b.setupScore - a.setupScore : a.setupScore - b.setupScore
      return diff !== 0 ? diff : (sortDesc ? b.entryScore - a.entryScore : a.entryScore - b.entryScore)
    })
  }, [allRows, decision, universe, query, sortDesc])

  const counts = useMemo(
    () =>
      allRows.reduce((acc, r) => {
        const d = decisionOf(r.classification)
        acc[d] = (acc[d] ?? 0) + 1
        return acc
      }, {}),
    [allRows]
  )

  if (view === 'journal' && (error || !data)) {
    return (
      <div className="min-h-screen" style={{ background: 'var(--page-plane)' }}>
        <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
          <header className="mb-6">
            <h1 className="text-2xl font-semibold tracking-tight" style={{ color: 'var(--text-primary)' }}>
              Trade Journal
            </h1>
            <p className="mt-1 text-sm" style={{ color: 'var(--text-secondary)' }}>
              Your delivery/swing trade log, auto-calculated performance dashboard, and 1:2 R:R calculator.
            </p>
            <div className="mt-3">
              <ViewTabs view={view} setView={setView} reversalCount={data?.reversals?.length ?? 0}
                nearBreakoutCount={data?.nearBreakouts?.length ?? 0} />
            </div>
          </header>
          <TradeJournalView />
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div
        className="flex min-h-screen flex-col items-center justify-center gap-8 p-6"
        style={{
          background:
            'radial-gradient(ellipse 800px 500px at 50% 20%, var(--accent-wash), transparent), var(--page-plane)',
        }}
      >
        <div className="flex items-center gap-2.5">
          <LogoBadge size={32} />
          <span className="text-lg font-semibold tracking-tight" style={{ color: 'var(--text-primary)' }}>
            Breakout Scanner
          </span>
        </div>

        <div
          className="w-full max-w-md rounded-2xl border p-8 text-center"
          style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', boxShadow: 'var(--shadow-md)' }}
        >
          <div className="mx-auto w-fit">
            <LogoBadge size={56} />
          </div>
          <p className="mt-5 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>No scan data yet</p>
          <p className="mx-auto mt-2 max-w-xs text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            Run your first scan to screen the full universe against a 10-point breakout checklist.
          </p>

          <button
            onClick={refreshAll}
            disabled={refreshing}
            className="mt-6 inline-flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-opacity hover:opacity-90 disabled:opacity-50"
            style={{ background: 'var(--accent)' }}
          >
            {refreshing && <span className="inline-block animate-spin">&#8635;</span>}
            {refreshing ? refreshProgress ?? 'Scanning…' : 'Run first scan'}
          </button>

          {refreshError && <p className="mt-3 text-sm" style={{ color: 'var(--status-serious)' }}>{refreshError}</p>}

          <div className="mt-7 grid grid-cols-3 gap-3 border-t pt-6" style={{ borderColor: 'var(--gridline)' }}>
            <MiniStat label="Universe" value="Nifty 500" />
            <MiniStat label="Checklist" value="10-point" />
            <MiniStat label="Data" value="Live NSE" />
          </div>

          <div className="mt-5 border-t pt-5" style={{ borderColor: 'var(--gridline)' }}>
            <ViewTabs view={view} setView={setView} reversalCount={0} nearBreakoutCount={0} />
          </div>
        </div>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4" style={{ background: 'var(--page-plane)' }}>
        <div className="animate-pulse" style={{ color: 'var(--accent)' }}>
          <Logo size={34} />
        </div>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading scan results…</p>
        <ViewTabs view={view} setView={setView} reversalCount={0} nearBreakoutCount={0} />
      </div>
    )
  }

  return (
    <div className="min-h-screen" style={{ background: 'var(--page-plane)' }}>
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <header className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight" style={{ color: 'var(--text-primary)' }}>
              {view === 'reversal' ? 'Reversal Watch' : view === 'journal' ? 'Trade Journal' : 'Breakout Scanner'}
            </h1>
            <p className="mt-1 text-sm" style={{ color: 'var(--text-secondary)' }}>
              {view === 'journal'
                ? 'Your delivery/swing trade log, auto-calculated performance dashboard, and 1:2 R:R calculator.'
                : <>Nifty 500 &middot; {data.universe} stocks scanned &middot; updated{' '}
                    {new Date(data.generatedAt).toLocaleString()}</>}
            </p>
            <div className="mt-3">
              <ViewTabs view={view} setView={setView} reversalCount={data.reversals?.length ?? 0}
                nearBreakoutCount={data.nearBreakouts?.length ?? 0} />
            </div>
          </div>
          <div className="flex flex-col items-end gap-1.5">
            <button
              onClick={refreshAll}
              disabled={refreshing}
              className="flex items-center gap-2 rounded-lg border px-3.5 py-1.5 text-sm font-semibold transition-opacity hover:opacity-80 disabled:opacity-60"
              style={{ borderColor: 'var(--btn-scan-border)', background: 'var(--btn-scan-bg)', color: 'var(--text-primary)' }}
            >
              <span className={refreshing ? 'inline-block animate-spin' : 'inline-block'}>&#8635;</span>
              {refreshing ? 'Scanning…' : 'Scan Stocks'}
            </button>
            {refreshing && refreshProgress && (
              <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>{refreshProgress}</span>
            )}
            {refreshError && (
              <span className="max-w-xs text-right text-xs" style={{ color: 'var(--status-serious)' }}>{refreshError}</span>
            )}
          </div>
        </header>

        {view === 'breakout' && (
          <div className="mb-6 flex flex-wrap gap-3">
            <StatTile label="Scanned" value={allRows.length} />
            <StatTile label="Buy Now" value={counts['BUY NOW'] ?? 0} color="var(--status-good)" />
            <StatTile label="Wait" value={counts.WAIT ?? 0} color="var(--status-warning)" />
            <StatTile label="Reject" value={counts.REJECT ?? 0} color="var(--status-critical)" />
          </div>
        )}
        {view === 'reversal' && (
          <div className="mb-6 flex flex-wrap gap-3">
            <StatTile label="Confirmed signals" value={data.reversals?.length ?? 0} color="var(--status-good)" />
          </div>
        )}
        {view === 'nearBreakout' && (
          <div className="mb-6 flex flex-wrap gap-3">
            <StatTile label="Coiling setups" value={data.nearBreakouts?.length ?? 0} color="var(--status-warning)" />
          </div>
        )}

        {view !== 'journal' && (
        <div className="mb-6 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <h2 className="mb-3 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
            Look up any other stock
          </h2>
          <form
            onSubmit={(e) => {
              e.preventDefault()
              fetchCustom(customInput)
              setCustomInput('')
            }}
            className="flex flex-wrap gap-2"
          >
            <input
              value={customInput}
              onChange={(e) => setCustomInput(e.target.value)}
              placeholder="e.g. TATAELXSI or TATAELXSI.NS"
              className="w-60 rounded-lg border px-3 py-1.5 text-sm outline-none focus:ring-2"
              style={{ ...inputStyle, '--tw-ring-color': 'var(--accent)' }}
            />
            <button
              type="submit"
              disabled={customLoading || !customInput.trim()}
              className="rounded-lg px-4 py-1.5 text-sm font-semibold text-white transition-opacity disabled:opacity-40"
              style={{ background: 'var(--accent)' }}
            >
              {customLoading ? 'Fetching…' : 'Analyze'}
            </button>
          </form>
          {customError ? (
            <p className="mt-2.5 text-sm" style={{ color: 'var(--status-serious)' }}>{customError}</p>
          ) : (
            <p className="mt-2.5 text-xs" style={{ color: 'var(--text-muted)' }}>
              No suffix defaults to NSE (.NS). Use .BO for BSE.
            </p>
          )}
        </div>
        )}

        {view === 'journal' && <TradeJournalView />}
        {view === 'reversal' && <ReversalTable rows={data.reversals ?? []} />}
        {view === 'nearBreakout' && <NearBreakoutTable rows={data.nearBreakouts ?? []} />}

        {view === 'breakout' && (
        <>
        <div className="mb-4 flex flex-wrap items-center gap-x-6 gap-y-3">
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Universe</span>
            <div className="flex gap-1.5">
              {UNIVERSES.map((u) => (
                <FilterPill key={u.key} active={universe === u.key} onClick={() => setUniverse(u.key)}>
                  {u.label}
                </FilterPill>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Decision</span>
            <div className="flex gap-1.5">
              {DECISIONS.map((d) => (
                <FilterPill key={d} active={decision === d} onClick={() => setDecision(d)}>
                  {d === 'ALL' ? 'All' : d}
                </FilterPill>
              ))}
            </div>
          </div>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search name or symbol…"
            className="ml-auto w-52 rounded-lg border px-3 py-1.5 text-sm outline-none focus:ring-2"
            style={{ ...inputStyle, '--tw-ring-color': 'var(--accent)' }}
          />
        </div>

        <div className="overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <table className="w-full text-sm">
            <thead>
              <tr style={{ borderBottom: `1px solid ${'var(--gridline)'}` }}>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Symbol</th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>A–J</th>
                <th
                  className="cursor-pointer select-none px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide"
                  style={{ color: 'var(--text-muted)' }}
                  onClick={() => setSortDesc((s) => !s)}
                >
                  Setup / Entry {sortDesc ? '↓' : '↑'}
                </th>
                <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Decision</th>
                <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Close</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <Fragment key={row.symbol}>
                  <tr
                    onClick={() => setExpanded(expanded === row.symbol ? null : row.symbol)}
                    className="cursor-pointer transition-colors"
                    style={{ borderTop: `1px solid ${'var(--gridline)'}` }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--page-plane)')}
                    onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                  >
                    <td className="px-4 py-2.5">
                      <div className="flex items-center gap-2">
                        <span className="font-medium" style={{ color: 'var(--text-primary)' }}>{row.name ?? row.symbol}</span>
                        {(row.universe === 'CUSTOM' || row.universe === 'NIFTY_500') && (
                          <UniverseTag universe={row.universe} />
                        )}
                      </div>
                      <div className="text-xs" style={{ color: 'var(--text-muted)' }}>{row.symbol}</div>
                    </td>
                    <td className="px-4 py-2.5">
                      <CheckDots checks={row.checks} />
                    </td>
                    <td className="tabular px-4 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                      S {row.setupScore}/{row.setupTotal} &middot; E {row.entryScore}/{row.entryTotal}
                    </td>
                    <td className="px-4 py-2.5">
                      <DecisionBadge classification={row.classification} />
                    </td>
                    <td className="tabular px-4 py-2.5 text-right" style={{ color: 'var(--text-primary)' }}>{row.values.Close?.toFixed(2)}</td>
                    <td className="px-4 py-2.5 text-right">
                      {row.universe === 'CUSTOM' && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation()
                            removeCustom(row.symbol)
                          }}
                          style={{ color: 'var(--text-muted)' }}
                          title="Remove from custom list"
                        >
                          ✕
                        </button>
                      )}
                    </td>
                  </tr>
                  {expanded === row.symbol && (
                    <tr>
                      <td colSpan={7} className="p-0">
                        <DetailPanel row={row} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-4 py-8 text-center" style={{ color: 'var(--text-muted)' }}>
                    No stocks match this filter.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        </>
        )}
      </div>
    </div>
  )
}
