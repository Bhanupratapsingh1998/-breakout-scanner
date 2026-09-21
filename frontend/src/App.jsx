import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  createChart, CandlestickSeries, HistogramSeries, createSeriesMarkers, LineStyle,
} from 'lightweight-charts'


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

/**
 * One decision vocabulary shared by the Breakout and Reversal views. The analyzers emit
 * finer-grained classifications (BUY NOW / WAIT FOR PULLBACK / HIGH_QUALITY_REVERSAL / …);
 * this collapses every one of them onto the only question a table row needs to answer — act,
 * hold, or skip. The per-check breakdown is still in the detail panel, so the nuance is one
 * click away rather than competing for attention in the row.
 */
const DECISION_META = {
  'BUY NOW': { color: 'var(--status-good)', label: 'BUY NOW' },
  WAIT: { color: 'var(--status-warning)', label: 'WAIT' },
  REJECT: { color: 'var(--status-critical)', label: 'REJECT' },
}

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

/**
 * The watchlist lives in the database, not localStorage: it should follow the user across
 * browsers and survive a cache clear, and the server needs to be able to see it. A failed read
 * returns an empty list rather than throwing — an unreachable API should not blank the page.
 */
async function loadSavedSymbols() {
  try {
    const res = await fetch('/api/watchlist')
    if (!res.ok) return []
    return (await res.json()).map((r) => r.symbol)
  } catch {
    return []
  }
}

async function saveSymbol(symbol) {
  try {
    await fetch('/api/watchlist', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ symbol }),
    })
  } catch {
    // Non-fatal: the lookup still shows this session. It just will not survive a reload.
  }
}

async function forgetSymbol(symbol) {
  try {
    await fetch(`/api/watchlist/${encodeURIComponent(symbol)}`, { method: 'DELETE' })
  } catch {
    // Non-fatal, as above.
  }
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

/** The Dashboard / Bullish Stocks / Reversal Watch / My Watchlist / Trade Journal tab row —
 *  shared by the main app shell AND the "no scan data yet" / "loading" screens, so every tab is
 *  reachable from anywhere, not just after a scan has completed. */
function ViewTabs({ view, setView, reversalCount, watchlistCount = 0 }) {
  return (
    <div className="flex flex-wrap gap-1.5">
      <FilterPill active={view === 'dashboard'} onClick={() => setView('dashboard')}>
        Dashboard
      </FilterPill>
      <FilterPill active={view === 'bullish'} onClick={() => setView('bullish')}>
        Bullish Stocks
      </FilterPill>
      <FilterPill active={view === 'index500'} onClick={() => setView('index500')}>
        Index 500 Analysis
      </FilterPill>
      <FilterPill active={view === 'reversal'} onClick={() => setView('reversal')}>
        Reversal Watch{reversalCount > 0 ? ` (${reversalCount})` : ''}
      </FilterPill>
      <FilterPill active={view === 'lookup'} onClick={() => setView('lookup')}>
        My Watchlist{watchlistCount > 0 ? ` (${watchlistCount})` : ''}
      </FilterPill>
      <FilterPill active={view === 'journal'} onClick={() => setView('journal')}>
        Trade Journal
      </FilterPill>
      <FilterPill active={view === 'expenses'} onClick={() => setView('expenses')}>
        Expenses
      </FilterPill>
    </div>
  )
}

/**
 * The user's own followed symbols, assessed by the bullish engine.
 *
 * <p>These rows used to come from the Breakout Scanner's A-J checklist. That feature has been
 * removed, so each followed symbol is now scored by {@code /api/bullish-stocks/{symbol}} instead -
 * the same 100-point assessment the ranked table uses, which means a watchlist row and a ranked
 * row say exactly the same thing about the same stock. The expanded detail is the identical panel.
 */
function WatchlistTable({ rows, expanded, setExpanded, onRemove, onOpenChart }) {
  return (
    <div className="overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[52rem] text-sm">
          <thead>
            <tr style={{ borderBottom: '1px solid var(--gridline)' }}>
              {['Stock', 'Score', 'Pattern', 'Trend', 'RSI', 'Price', 'Status', ''].map((h, i) => (
                <th
                  key={h || i}
                  className={`px-4 py-3 text-xs font-semibold uppercase tracking-wide ${i >= 4 && i <= 5 ? 'text-right' : 'text-left'}`}
                  style={{ color: 'var(--text-muted)' }}
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const meta = BULLISH_STATUS_META[row.tradeStatus] ?? { color: 'var(--text-muted)', label: row.tradeStatus }
              return (
                <Fragment key={row.symbol}>
                  <tr
                    onClick={() => setExpanded(expanded === row.symbol ? null : row.symbol)}
                    className="cursor-pointer transition-colors"
                    style={{ borderTop: '1px solid var(--gridline)' }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--page-plane)')}
                    onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                  >
                    <td className="px-4 py-2.5">
                      <div className="font-medium" style={{ color: 'var(--text-primary)' }}>{row.name ?? row.symbol}</div>
                      <div className="text-xs" style={{ color: 'var(--text-muted)' }}>
                        {row.symbol}{row.sector ? ` · ${row.sector}` : ''}
                      </div>
                    </td>
                    <td className="px-4 py-2.5">
                      <div className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                        {num(row.score, 0)}
                      </div>
                      <div className="text-xs" style={{ color: CLASSIFICATION_COLOR[row.classification] ?? 'var(--text-muted)' }}>
                        {row.classification}
                      </div>
                    </td>
                    <td className="px-4 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>{row.pattern.name}</td>
                    <td className="px-4 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>{row.trend.label}</td>
                    <td className="tabular px-4 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                      {num(row.summary.rsi, 0)}
                    </td>
                    <td className="tabular px-4 py-2.5 text-right" style={{ color: 'var(--text-primary)' }}>
                      {fmtPrice(row.price)}
                    </td>
                    <td className="px-4 py-2.5">
                      <span className="flex items-center gap-1.5 whitespace-nowrap text-xs font-semibold" style={{ color: meta.color }}>
                        <Dot color={meta.color} size={7} />
                        {meta.label}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <button
                        onClick={(e) => { e.stopPropagation(); onRemove(row.symbol) }}
                        className="transition-opacity hover:opacity-70"
                        style={{ color: 'var(--text-muted)' }}
                        title="Stop following this stock"
                      >
                        &#10005;
                      </button>
                    </td>
                  </tr>
                  {expanded === row.symbol && (
                    <tr>
                      <td colSpan={8} className="p-0">
                        <BullishDetailPanel row={row} onOpenChart={onOpenChart} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center" style={{ color: 'var(--text-muted)' }}>
                  Nothing followed yet. Search for any NSE or BSE symbol above — it is saved to your
                  account, so it will still be here on another browser or after clearing your cache.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
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
 *  profits but no losses yet. Never call .toFixed() on that directly; format through here. */
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

// Keys are the backend's result() values (WIN / LOSS / OPEN); the labels are what the journal
// actually calls them.
const RESULT_META = {
  WIN: { color: 'var(--status-good)', label: 'Profit' },
  LOSS: { color: 'var(--status-critical)', label: 'Loss' },
  OPEN: { color: 'var(--status-warning)', label: 'Open' },
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


/** Profit/loss split as a single proportion bar — two status colors, direct labels, no legend
 *  box needed (each segment carries its own label + dot, so identity is never color-alone). */
function ProfitLossBar({ profits, losses }) {
  const total = profits + losses
  if (total === 0) {
    return (
      <div className="flex items-center justify-center text-sm" style={{ height: 180, color: 'var(--text-muted)' }}>
        No closed trades yet.
      </div>
    )
  }
  const profitPct = (profits / total) * 100
  const lossPct = (losses / total) * 100
  return (
    <div className="flex flex-col justify-center" style={{ minHeight: 180 }}>
      <div className="tabular text-center text-3xl font-bold" style={{ color: profitPct >= 50 ? 'var(--status-good)' : 'var(--status-critical)' }}>
        {profitPct.toFixed(0)}%
      </div>
      <div className="mb-4 text-center text-xs" style={{ color: 'var(--text-muted)' }}>profit rate</div>
      <div className="flex h-3 overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
        {profits > 0 && <div style={{ width: `${profitPct}%`, background: 'var(--status-good)' }} />}
        {profits > 0 && losses > 0 && <div style={{ width: 2, background: 'var(--surface-1)' }} />}
        {losses > 0 && <div style={{ width: `${lossPct}%`, background: 'var(--status-critical)' }} />}
      </div>
      <div className="mt-2.5 flex items-center justify-between text-xs">
        <span className="flex items-center gap-1.5" style={{ color: 'var(--text-secondary)' }}>
          <Dot color="var(--status-good)" size={7} /> {profits} profit{profits !== 1 ? 's' : ''}
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
          Profit / Loss Split
        </h4>
        <ProfitLossBar profits={dashboard.tradeCount.wins} losses={dashboard.tradeCount.losses} />
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
        headline={{ label: 'Profit rate', value: fmtPct(pq.winRatePct) }}
        rows={[
          ['Avg profit', fmtMoney(pq.avgWin), 'var(--status-good)'],
          ['Avg loss', fmtMoney(pq.avgLoss), 'var(--status-critical)'],
          ['Largest profit', fmtMoney(pq.largestWin), 'var(--status-good)'],
          ['Largest loss', fmtMoney(pq.largestLoss), 'var(--status-critical)'],
        ]}
      />
      <DashboardCard
        title="Trade Count" icon="📋"
        headline={{ label: 'Total trades', value: tc.total }}
        rows={[
          ['Closed', tc.closed],
          ['Open', tc.open],
          ['Profits', tc.wins, 'var(--status-good)'],
          ['Losses', tc.losses, 'var(--status-critical)'],
        ]}
      />
      <DashboardCard
        title="Edge Metrics" icon="⚡" accent={pnlColor(em.expectancyPerTrade)}
        headline={{ label: 'Expectancy / trade', value: fmtMoney(em.expectancyPerTrade), color: pnlColor(em.expectancyPerTrade) }}
        rows={[
          ['Profit factor', fmtRatio(em.profitFactor)],
          ['Profit : Loss ratio', fmtRatio(em.winLossRatio)],
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

const BULLISH_STATUS_META = {
  'BUY NOW': { color: 'var(--status-good)', label: 'Buy now' },
  'WAIT FOR BREAKOUT': { color: 'var(--accent)', label: 'Wait for breakout' },
  'WAIT FOR RETEST': { color: 'var(--status-warning)', label: 'Wait for retest' },
  'WAIT FOR PULLBACK': { color: 'var(--status-warning)', label: 'Wait for pullback' },
  'AVOID CHASING': { color: 'var(--status-serious)', label: 'Avoid chasing' },
  'FAILED SETUP': { color: 'var(--status-critical)', label: 'Failed setup' },
}

const CLASSIFICATION_COLOR = {
  'A+ BULLISH': 'var(--status-good)',
  'STRONG BULLISH': 'var(--status-good)',
  'BULLISH WATCHLIST': 'var(--accent)',
  'NEUTRAL / DEVELOPING': 'var(--text-muted)',
  WEAK: 'var(--status-muted)',
}

const REGIME_META = {
  BULLISH: { color: 'var(--status-good)', label: 'Bullish' },
  NEUTRAL: { color: 'var(--status-warning)', label: 'Neutral' },
  BEARISH: { color: 'var(--status-critical)', label: 'Bearish' },
}

/**
 * The summary tiles, each paired with the predicate the server counted it with.
 *
 * <p>These used to be static numbers. That made them unreachable: "Breakouts 47" counts a confirmed
 * breakout, but the STATUS pills filter on trade status, and those 47 stocks are spread across
 * WAIT FOR BREAKOUT, WAIT FOR RETEST, WAIT FOR PULLBACK and AVOID CHASING - so no pill could ever
 * select the group the tile was advertising. The predicates below are kept deliberately identical
 * to the server's, so a tile's count and the rows it filters to can never disagree.
 */
const BULLISH_GROUPS = [
  { key: 'ALL', label: 'Ranked', countKey: 'analyzedStockCount', color: 'var(--text-secondary)', match: () => true },
  { key: 'BULLISH', label: 'Bullish', countKey: 'bullishStockCount', color: 'var(--accent)',
    match: (r) => r.score >= 65 },
  { key: 'APLUS', label: 'A+ setups', countKey: 'aPlusCount', color: 'var(--status-good)',
    match: (r) => r.score >= 85 },
  { key: 'BREAKOUT', label: 'Breakouts', countKey: 'breakoutCount', color: 'var(--status-good)',
    match: (r) => r.breakout?.confirmed && !r.breakout?.failed },
  { key: 'PULLBACK', label: 'Pullbacks / retests', countKey: 'pullbackCount', color: 'var(--status-warning)',
    match: (r) => r.setupStage === 'PULLBACK OPPORTUNITY' || r.setupStage === 'BREAKOUT RETEST' },
  { key: 'BUYNOW', label: 'Buy now', countKey: 'buyNowCount', color: 'var(--status-good)',
    match: (r) => r.tradeStatus === 'BUY NOW' },
]

/** A summary tile that is also the filter for the group it counts. */
function GroupTile({ group, count, served, active, onClick }) {
  return (
    <button
      onClick={onClick}
      className="flex flex-1 flex-col gap-1.5 rounded-xl border p-4 text-left transition-all"
      style={{
        minWidth: '9rem',
        borderColor: active ? group.color : 'var(--border)',
        background: active ? 'var(--page-plane)' : 'var(--surface-1)',
        boxShadow: active ? 'var(--shadow-md)' : 'none',
      }}
    >
      <span className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide"
        style={{ color: active ? group.color : 'var(--text-secondary)' }}>
        <Dot color={group.color} size={7} />
        {group.label}
      </span>
      <span className="tabular text-2xl font-semibold" style={{ color: 'var(--text-primary)' }}>{count}</span>
      {/* The tiles count every analysed stock; the table can only show the rows actually served.
          Saying so is better than a tile that seems to disagree with the list under it. */}
      {served != null && served < count && (
        <span className="text-[10px]" style={{ color: 'var(--text-muted)' }}>{served} in this list</span>
      )}
    </button>
  )
}

const BULLISH_SORTS = [
  { key: 'score', label: 'Score', get: (r) => r.score },
  { key: 'rs', label: 'Rel. strength', get: (r) => r.summary.rs3mPct ?? -999 },
  { key: 'volume', label: 'Volume', get: (r) => r.summary.volumeRatio ?? 0 },
  { key: 'rr', label: 'R:R', get: (r) => r.summary.riskReward ?? 0 },
]

function num(v, digits = 2) {
  return v == null || Number.isNaN(v) ? '—' : Number(v).toFixed(digits)
}

function signedPct(v, digits = 1) {
  if (v == null || Number.isNaN(v)) return '—'
  return `${v >= 0 ? '+' : ''}${Number(v).toFixed(digits)}%`
}

function StatusBadgeBullish({ status }) {
  const meta = BULLISH_STATUS_META[status] ?? { color: 'var(--text-muted)', label: status }
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs font-semibold" style={{ color: meta.color }}>
      <Dot color={meta.color} size={7} />
      {meta.label}
    </span>
  )
}

/** The market-condition banner the spec asks to show above everything else. */
function MarketRegimeBanner({ regime }) {
  if (!regime) return null
  const meta = REGIME_META[regime.regime] ?? { color: 'var(--text-muted)', label: regime.regime }
  return (
    <div
      className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-xl border px-4 py-3"
      style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}
    >
      <span className="flex items-center gap-2">
        <Dot color={meta.color} size={9} />
        <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Market regime
        </span>
        <span className="text-sm font-semibold" style={{ color: meta.color }}>{meta.label}</span>
        <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>
          {regime.score}/{regime.maxScore}
        </span>
      </span>
      <span className="flex-1 text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
        {regime.summary}
      </span>
      {!regime.allowsBuyNow && (
        <span className="text-xs font-semibold" style={{ color: 'var(--status-critical)' }}>
          Entries withheld in this regime
        </span>
      )}
    </div>
  )
}

/** One component of the 100-point score, drawn as a filled bar so the shortfalls are visible. */
function ScoreBar({ label, points, maxPoints }) {
  const pct = maxPoints ? Math.max(0, Math.min(100, (points / maxPoints) * 100)) : 0
  const color = pct >= 75 ? 'var(--status-good)' : pct >= 45 ? 'var(--status-warning)' : 'var(--status-critical)'
  return (
    <div>
      <div className="flex items-baseline justify-between text-xs">
        <span style={{ color: 'var(--text-secondary)' }}>{label}</span>
        <span className="tabular font-semibold" style={{ color: 'var(--text-primary)' }}>
          {num(points, 1)}/{maxPoints}
        </span>
      </div>
      <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
        <div className="h-full rounded-full" style={{ width: `${pct}%`, background: color }} />
      </div>
    </div>
  )
}

function DetailRow({ label, value, color }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1">
      <span className="text-xs" style={{ color: 'var(--text-muted)' }}>{label}</span>
      <span className="tabular text-xs font-semibold" style={{ color: color ?? 'var(--text-primary)' }}>{value}</span>
    </div>
  )
}

const SCORE_COMPONENT_LABELS = {
  trend: 'Trend',
  relativeStrength: 'Relative strength',
  momentum: 'Momentum',
  volume: 'Volume',
  priceStructure: 'Price structure',
  patternQuality: 'Pattern quality',
  breakoutQuality: 'Breakout quality',
  riskReward: 'Risk / reward',
}

/** Everything section 14 asks to show when a stock is selected. */
/** Consistent chrome for every block in the detail panel, so nothing drifts out of alignment. */
function PanelCard({ title, meta, children }) {
  return (
    <section className="rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <header className="flex items-baseline justify-between gap-2 border-b px-3.5 py-2.5" style={{ borderColor: 'var(--gridline)' }}>
        <h4 className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
          {title}
        </h4>
        {meta && <span className="tabular shrink-0 text-[10px]" style={{ color: 'var(--text-muted)' }}>{meta}</span>}
      </header>
      <div className="px-3.5 py-3">{children}</div>
    </section>
  )
}

/**
 * The score as an arc rather than a number in a box.
 *
 * <p>"78.5" alone does not say what it is out of, and the table already shows the digits. The ring
 * is here to answer "how far along is this" at a glance, which is the one thing the number cannot
 * do on its own.
 */
function ScoreDial({ score, color, size = 74 }) {
  const r = (size - 9) / 2
  const circumference = 2 * Math.PI * r
  const pct = Math.max(0, Math.min(1, (score ?? 0) / 100))
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90" aria-hidden="true">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--gridline)" strokeWidth="6" />
        <circle
          cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth="6" strokeLinecap="round"
          strokeDasharray={`${circumference * pct} ${circumference}`}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="tabular text-lg font-semibold leading-none" style={{ color: 'var(--text-primary)' }}>
          {num(score, 0)}
        </span>
        <span className="text-[9px] leading-none" style={{ color: 'var(--text-muted)' }}>/ 100</span>
      </div>
    </div>
  )
}

/** One figure in the trade-plan strip: a label, a price, and what that price means as a move. */
function PlanFigure({ label, value, sub, color, emphasis }) {
  return (
    <div className="min-w-[5.5rem] flex-1">
      <div className="text-[10px] font-medium uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
        {label}
      </div>
      <div className={`tabular mt-1 ${emphasis ? 'text-lg' : 'text-base'} font-semibold leading-none`}
        style={{ color: color ?? 'var(--text-primary)' }}>
        {value}
      </div>
      {sub && (
        <div className="tabular mt-1 text-[11px]" style={{ color: color ?? 'var(--text-muted)' }}>{sub}</div>
      )}
    </div>
  )
}

/**
 * Entry, stop and targets drawn to scale on one axis.
 *
 * <p>A 7:1 risk/reward is a claim about proportion, and four prices in a column do not show a
 * proportion — you have to do the subtraction yourself. Drawn to scale, a thin red band against a
 * wide green one says it immediately, and a setup whose stop is nearly as far as its target cannot
 * hide behind a flattering ratio.
 */
function RiskRewardBar({ entry, stopLoss, target1, target2 }) {
  if (entry == null || stopLoss == null || target1 == null) return null
  const top = target2 ?? target1
  const lo = Math.min(stopLoss, entry)
  const hi = Math.max(top, entry)
  const span = hi - lo
  if (!(span > 0)) return null

  const at = (v) => ((v - lo) / span) * 100
  const entryAt = at(entry)
  const t1At = at(target1)

  return (
    <div className="mt-4">
      <div className="flex h-2.5 w-full overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
        <span style={{ width: `${entryAt}%`, background: 'var(--status-critical)' }} />
        <span style={{ width: `${t1At - entryAt}%`, background: 'var(--status-good)' }} />
        <span style={{ width: `${100 - t1At}%`, background: 'var(--status-good)', opacity: 0.4 }} />
      </div>
      <div className="mt-1.5 flex text-[10px]" style={{ color: 'var(--text-muted)' }}>
        <span style={{ width: `${entryAt}%` }}>risk</span>
        <span style={{ width: `${t1At - entryAt}%` }}>to target 1</span>
        {target2 != null && <span className="flex-1 text-right">target 2</span>}
      </div>
    </div>
  )
}

/**
 * A signed percentage with a bar proportional to the largest value on show.
 *
 * <p>Seven stacked percentages are seven separate readings; scaled against each other they become
 * one shape, and it is obvious at a glance whether relative strength is broad or rests on a single
 * window. Zero sits at the centre so a negative reading is visibly different in kind, not just in
 * sign.
 */
function ReturnRow({ label, value, peak }) {
  const v = value == null || Number.isNaN(value) ? null : Number(value)
  const positive = (v ?? 0) >= 0
  const width = v == null || !(peak > 0) ? 0 : Math.min(50, (Math.abs(v) / peak) * 50)
  const color = v == null ? 'var(--text-muted)' : positive ? 'var(--status-good)' : 'var(--status-critical)'
  return (
    <div className="flex items-center gap-2 py-[3px]">
      <span className="w-24 shrink-0 text-xs" style={{ color: 'var(--text-muted)' }}>{label}</span>
      <span className="relative h-1.5 flex-1 rounded-full" style={{ background: 'var(--gridline)' }}>
        <span className="absolute inset-y-0" style={{ left: '50%', width: 1, background: 'var(--border-strong)' }} />
        <span
          className="absolute inset-y-0 rounded-full"
          style={{ background: color, width: `${width}%`, left: positive ? '50%' : `${50 - width}%` }}
        />
      </span>
      <span className="tabular w-14 shrink-0 text-right text-xs font-semibold" style={{ color }}>
        {signedPct(v)}
      </span>
    </div>
  )
}

const OVEREXTENSION_COLOR = {
  NONE: 'var(--status-good)',
  MODERATE: 'var(--status-warning)',
}

/**
 * Everything the engine concluded about one stock, ordered by what a decision actually needs.
 *
 * <p>The old layout was eight boxes of identical weight, which left the trade plan — the only part
 * that is acted on — sitting between "Levels" and "Extension check" and looking no more important
 * than either. Here the thesis and the plan come first at full width, and the supporting evidence
 * sits underneath in three columns for anyone who wants to check the reasoning.
 */
function BullishDetailPanel({ row, onOpenChart }) {
  const c = row.scoreBreakdown.components
  const plan = row.tradePlan
  const statusMeta = BULLISH_STATUS_META[row.tradeStatus]
    ?? { color: 'var(--text-muted)', label: row.tradeStatus }
  const classColor = CLASSIFICATION_COLOR[row.classification] ?? 'var(--text-muted)'

  const returns = [
    ['1M return', row.momentum.return1mPct],
    ['3M return', row.momentum.return3mPct],
    ['6M return', row.momentum.return6mPct],
    ['1M vs market', row.relativeStrength.excess1mPct],
    ['3M vs market', row.relativeStrength.excess3mPct],
    ['6M vs market', row.relativeStrength.excess6mPct],
    ['3M vs Nifty 50', row.relativeStrength.excessVsNifty50_3mPct],
  ]
  // One scale across all seven, so the bars are comparable with each other rather than each
  // being drawn against its own maximum.
  const peak = Math.max(...returns.map(([, v]) => (v == null || Number.isNaN(v) ? 0 : Math.abs(v))), 1)

  const move = (to) => (plan.entry > 0 && to != null ? (to / plan.entry - 1) * 100 : null)

  return (
    <div
      className="border-t px-4 py-5"
      style={{
        borderColor: 'var(--gridline)',
        background: 'var(--page-plane)',
        position: 'sticky',
        left: 0,
        width: 'min(100%, calc(100vw - 2rem))',
      }}
    >
      {/* ---- the thesis, with the score it produced ---- */}
      <div className="mb-4 flex flex-col gap-4 rounded-xl border p-4 sm:flex-row sm:items-start"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <div className="flex items-center gap-3 sm:flex-col sm:gap-2">
          <ScoreDial score={row.score} color={classColor} />
          <div className="text-center">
            <div className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: classColor }}>
              {row.classification}
            </div>
            {row.scoreBreakdown.regimeMultiplier !== 1 && (
              <div className="tabular mt-0.5 text-[10px]" style={{ color: 'var(--text-muted)' }}>
                raw {num(row.scoreBreakdown.rawTotal, 1)} × {row.scoreBreakdown.regimeMultiplier}
              </div>
            )}
          </div>
        </div>

        <div className="min-w-0 flex-1">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <StatusBadgeBullish status={row.tradeStatus} />
            {row.setupStage && row.setupStage !== row.tradeStatus && (
              <span className="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide"
                style={{ background: 'var(--accent-wash)', color: 'var(--accent)' }}>
                {row.setupStage}
              </span>
            )}
            <span className="rounded px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide"
              style={{ background: 'var(--page-plane)', color: 'var(--text-muted)' }}>
              {row.pattern.name}
            </span>
          </div>
          <p className="max-w-3xl text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            {row.whyBullish}
          </p>
        </div>

        <button
          onClick={(e) => { e.stopPropagation(); onOpenChart(row) }}
          className="shrink-0 rounded-lg border px-3.5 py-2 text-xs font-semibold transition-opacity hover:opacity-80"
          style={{ borderColor: 'var(--border)', color: 'var(--text-primary)', background: 'var(--page-plane)' }}
        >
          Open chart
        </button>
      </div>

      {/* ---- the plan, at full width because it is the only part that gets acted on ---- */}
      <div className="mb-4 rounded-xl border p-4"
        style={{ borderColor: statusMeta.color, background: 'var(--surface-1)' }}>
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
          <h4 className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
            Trade plan
          </h4>
          {plan.present && (
            <span className="text-[10px] uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
              entry {plan.entryType.toLowerCase()}
            </span>
          )}
        </div>

        {plan.present ? (
          <>
            <div className="flex flex-wrap gap-x-6 gap-y-4">
              <PlanFigure label="Entry" value={fmtPrice(plan.entry)} emphasis />
              <PlanFigure label="Stop loss" value={fmtPrice(plan.stopLoss)} color="var(--status-critical)"
                sub={`${signedPct(move(plan.stopLoss))} · risk ${num(plan.riskPct, 1)}%`} />
              <PlanFigure label="Target 1" value={fmtPrice(plan.target1)} color="var(--status-good)"
                sub={signedPct(move(plan.target1))} />
              <PlanFigure label="Target 2" value={fmtPrice(plan.target2)} color="var(--status-good)"
                sub={signedPct(move(plan.target2))} />
              <PlanFigure label="Risk : reward" value={`${num(plan.riskReward, 1)} : 1`} emphasis
                sub={`reward ${num(plan.rewardPct, 1)}%`} />
            </div>
            <RiskRewardBar entry={plan.entry} stopLoss={plan.stopLoss}
              target1={plan.target1} target2={plan.target2} />
          </>
        ) : (
          <p className="text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{plan.explanation}</p>
        )}
      </div>

      {/* ---- the evidence ---- */}
      <div className="grid items-start gap-4 md:grid-cols-3">
        <PanelCard title="Score breakdown" meta={`${num(row.score, 1)} / 100`}>
          <div className="flex flex-col gap-2.5">
            {Object.entries(SCORE_COMPONENT_LABELS).map(([key, label]) => (
              <ScoreBar key={key} label={label} points={c[key].points} maxPoints={c[key].maxPoints} />
            ))}
          </div>
        </PanelCard>

        <div className="flex flex-col gap-4">
          <PanelCard title="Pattern"
            meta={row.pattern.confidence > 0 ? `${num(row.pattern.confidence, 0)}% confidence` : null}>
            <div className="mb-1.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
              {row.pattern.name}
            </div>
            <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              {row.pattern.explanation}
            </p>
          </PanelCard>

          <PanelCard title="Levels">
            <DetailRow label="Current price" value={fmtPrice(row.price)} />
            {row.breakout.level != null && <DetailRow label="Breakout level" value={fmtPrice(row.breakout.level)} />}
            {row.pattern.resistance != null && <DetailRow label="Resistance / neckline" value={fmtPrice(row.pattern.resistance)} />}
            {row.pattern.invalidationLevel != null && (
              <DetailRow label="Pattern invalidation" value={fmtPrice(row.pattern.invalidationLevel)} />
            )}
            <DetailRow label="Breakout status" value={row.breakout.label} />
            <DetailRow label="Setup stage" value={row.setupStage} />
          </PanelCard>
        </div>

        <div className="flex flex-col gap-4">
          <PanelCard title="Indicators">
            <DetailRow label="Trend" value={row.trend.label} />
            <DetailRow label="Structure" value={row.trend.structure} />
            <DetailRow label="Higher timeframes" value={row.higherTimeframes.verdict} />
            <DetailRow label="RSI(14)" value={num(row.momentum.rsi, 1)} />
            <DetailRow label="ADX(14)" value={num(row.momentum.adx, 1)} />
            <DetailRow label="Volume vs 20D" value={`${num(row.volume.currentRatio, 2)}×`} />
            <DetailRow label="Volume profile" value={row.volume.label} />
          </PanelCard>

          <PanelCard title="Returns & relative strength" meta="vs zero">
            {returns.map(([label, value]) => (
              <ReturnRow key={label} label={label} value={value} peak={peak} />
            ))}
          </PanelCard>
        </div>
      </div>

      {/* ---- how stretched it already is: a caveat, so it reads as one ---- */}
      <div className="mt-4 flex flex-col gap-2 rounded-xl border border-l-4 px-4 py-3 sm:flex-row sm:items-center sm:gap-5"
        style={{
          borderColor: 'var(--border)',
          borderLeftColor: OVEREXTENSION_COLOR[row.overextension.level] ?? 'var(--status-critical)',
          background: 'var(--surface-1)',
        }}>
        <div className="shrink-0">
          <div className="text-[10px] font-semibold uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
            Extension check
          </div>
          <div className="text-sm font-semibold"
            style={{ color: OVEREXTENSION_COLOR[row.overextension.level] ?? 'var(--status-critical)' }}>
            {row.overextension.level}
          </div>
        </div>
        <div className="flex shrink-0 gap-5">
          <div>
            <div className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
              {num(row.overextension.atrsAboveEma20, 1)}
            </div>
            <div className="text-[10px]" style={{ color: 'var(--text-muted)' }}>ATRs above EMA20</div>
          </div>
          <div>
            <div className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
              {signedPct(row.overextension.pctAboveEma50)}
            </div>
            <div className="text-[10px]" style={{ color: 'var(--text-muted)' }}>above EMA50</div>
          </div>
        </div>
        <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
          {row.overextension.explanation}
        </p>
      </div>

      {/* The status reason is appended to whyBullish whenever the setup is not tradeable, so
          repeating it here would be the third time the same sentence appears on this panel. */}
      <p className="mt-4 text-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>
        {row.statusReason && !row.whyBullish.includes(row.statusReason) ? `${row.statusReason} ` : ''}
        This is a ranking of setup quality from price and volume data — not a forecast, and not a
        promise of any particular return.
      </p>
    </div>
  )
}

/**
 * The Bullish Stocks tab: a Nifty 500 ranking by setup quality.
 *
 * <p>The ranking itself is owned by App rather than by this component. The dashboard has to report
 * whether the ranking has run and what it found, and opening this tab is what starts the scan - so
 * the state has to outlive the tab being mounted. What stays local is only what nothing else cares
 * about: the filter, sort and expansion of the table.
 */
function BullishStocksView({ data, scanning, progress, scanError, loadError, onScan, queued }) {
  const [group, setGroup] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const [pattern, setPattern] = useState('ALL')
  const [sector, setSector] = useState('ALL')
  const [sortKey, setSortKey] = useState('score')
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState(null)
  const [chartRow, setChartRow] = useState(null)

  // Memoised so the filter/derivation hooks below do not see a fresh array identity every render.
  const stocks = useMemo(() => data?.stocks ?? [], [data])

  const patterns = useMemo(
    () => ['ALL', ...Array.from(new Set(stocks.map((r) => r.pattern.name))).sort()],
    [stocks]
  )
  const sectors = useMemo(
    () => ['ALL', ...Array.from(new Set(stocks.map((r) => r.sector).filter(Boolean))).sort()],
    [stocks]
  )
  const statuses = useMemo(
    () => ['ALL', ...Object.keys(BULLISH_STATUS_META).filter((s) => stocks.some((r) => r.tradeStatus === s))],
    [stocks]
  )

  const rows = useMemo(() => {
    let r = stocks
    const g = BULLISH_GROUPS.find((x) => x.key === group)
    if (g && g.key !== 'ALL') r = r.filter(g.match)
    if (status !== 'ALL') r = r.filter((x) => x.tradeStatus === status)
    if (pattern !== 'ALL') r = r.filter((x) => x.pattern.name === pattern)
    if (sector !== 'ALL') r = r.filter((x) => x.sector === sector)
    if (query.trim()) {
      const q = query.trim().toUpperCase()
      r = r.filter((x) => x.symbol.toUpperCase().includes(q) || (x.name ?? '').toUpperCase().includes(q))
    }
    const sort = BULLISH_SORTS.find((s) => s.key === sortKey) ?? BULLISH_SORTS[0]
    return [...r].sort((a, b) => sort.get(b) - sort.get(a))
  }, [stocks, group, status, pattern, sector, query, sortKey])

  // How many of each group the served list actually contains. The tiles count every analysed
  // stock, but the payload is capped, so the two legitimately differ on a large universe.
  const servedCounts = useMemo(() => {
    const out = {}
    BULLISH_GROUPS.forEach((g) => { out[g.key] = g.key === 'ALL' ? stocks.length : stocks.filter(g.match).length })
    return out
  }, [stocks])

  // No ranking yet. Opening this tab starts one, so the usual case is that a scan is already
  // under way by the time this renders — which is a progress report, not an empty state.
  if (!data) {
    return (
      <div
        className="mx-auto max-w-md rounded-2xl border p-8 text-center"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', boxShadow: 'var(--shadow-md)' }}
      >
        {queued && !scanning ? (
          <>
            <div className="mx-auto w-fit animate-pulse" style={{ color: 'var(--accent)' }}><Logo size={34} /></div>
            <p className="mt-4 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>Queued</p>
            <p className="mx-auto mt-2 max-w-xs text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              Waiting for the reversal scan to finish, then the ranking starts automatically. They
              share the same fetched bars, so running them one after the other is far faster.
            </p>
          </>
        ) : scanning ? (
          <>
            <div className="mx-auto w-fit animate-pulse" style={{ color: 'var(--accent)' }}><Logo size={34} /></div>
            <p className="mt-4 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>
              Ranking the Nifty 500…
            </p>
            <p className="tabular mx-auto mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>
              {progress ?? 'Starting…'}
            </p>
            <p className="mx-auto mt-3 max-w-xs text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              The first run of a session fetches around 500 symbols. You can switch tabs while it
              finishes — it keeps going in the background.
            </p>
          </>
        ) : (
          <>
            <p className="text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>
              {scanError || (loadError && loadError !== 'no-ranking-yet') ? 'Could not build the ranking' : 'No ranking yet'}
            </p>
            <p className="mx-auto mt-2 max-w-xs text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
              {scanError || (loadError && loadError !== 'no-ranking-yet')
                ? (scanError ?? loadError)
                : 'Rank the Nifty 500 on trend, relative strength, momentum, volume, structure, pattern, breakout quality and risk/reward.'}
            </p>
            <button
              onClick={onScan}
              className="mt-6 inline-flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-opacity hover:opacity-90"
              style={{ background: 'var(--accent)' }}
            >
              {scanError ? 'Try again' : 'Rank Nifty 500'}
            </button>
          </>
        )}
      </div>
    )
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {data.analyzedStockCount} of {data.universeSize} ranked
          {data.failedCount > 0 ? ` · ${data.failedCount} skipped for short history` : ''}
          {' · updated '}{new Date(data.timestamp).toLocaleString()}
        </p>
        <div className="flex flex-col items-end gap-1">
          <button
            onClick={onScan}
            disabled={scanning}
            className="flex items-center gap-2 rounded-lg border px-3.5 py-1.5 text-sm font-semibold transition-opacity hover:opacity-80 disabled:opacity-60"
            style={{ borderColor: 'var(--btn-scan-border)', background: 'var(--btn-scan-bg)', color: 'var(--text-primary)' }}
          >
            <span className={scanning ? 'inline-block animate-spin' : 'inline-block'}>&#8635;</span>
            {scanning ? 'Ranking…' : 'Re-rank'}
          </button>
          {scanning && progress && (
            <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>{progress}</span>
          )}
          {scanError && <span className="text-xs" style={{ color: 'var(--status-serious)' }}>{scanError}</span>}
        </div>
      </div>

      <MarketRegimeBanner regime={data.marketRegime} />

      {/* Each tile filters to the group it counts — see BULLISH_GROUPS. */}
      <div className="mb-2 flex flex-wrap gap-3">
        {BULLISH_GROUPS.map((g) => (
          <GroupTile
            key={g.key}
            group={g}
            count={data[g.countKey] ?? 0}
            served={servedCounts[g.key]}
            active={group === g.key}
            onClick={() => setGroup(group === g.key ? 'ALL' : g.key)}
          />
        ))}
      </div>
      <p className="mb-5 text-xs" style={{ color: 'var(--text-muted)' }}>
        Tap a tile to filter the table to that group.
      </p>

      <div className="mb-4 flex flex-wrap items-center gap-x-5 gap-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Status</span>
          <div className="flex flex-wrap gap-1.5">
            {statuses.map((s) => (
              <FilterPill key={s} active={status === s} onClick={() => setStatus(s)}>
                {s === 'ALL' ? 'All' : (BULLISH_STATUS_META[s]?.label ?? s)}
              </FilterPill>
            ))}
          </div>
        </div>

        <label className="flex items-center gap-2">
          <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Sort</span>
          <select
            value={sortKey}
            onChange={(e) => setSortKey(e.target.value)}
            className="rounded-lg border px-2 py-1.5 text-xs"
            style={inputStyle}
          >
            {BULLISH_SORTS.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}
          </select>
        </label>

        <label className="flex items-center gap-2">
          <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Pattern</span>
          <select
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            className="rounded-lg border px-2 py-1.5 text-xs"
            style={inputStyle}
          >
            {patterns.map((p) => <option key={p} value={p}>{p === 'ALL' ? 'All' : p}</option>)}
          </select>
        </label>

        {sectors.length > 1 && (
          <label className="flex items-center gap-2">
            <span className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Sector</span>
            <select
              value={sector}
              onChange={(e) => setSector(e.target.value)}
              className="max-w-[11rem] rounded-lg border px-2 py-1.5 text-xs"
              style={inputStyle}
            >
              {sectors.map((s) => <option key={s} value={s}>{s === 'ALL' ? 'All' : s}</option>)}
            </select>
          </label>
        )}

        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search name or symbol…"
          className="ml-auto w-52 rounded-lg border px-3 py-1.5 text-sm outline-none focus:ring-2"
          style={{ ...inputStyle, '--tw-ring-color': 'var(--accent)' }}
        />
      </div>

      <div className="overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[68rem] text-sm">
            <thead>
              <tr style={{ borderBottom: '1px solid var(--gridline)' }}>
                {['#', 'Stock', 'Score', 'Pattern', 'Trend', 'RS 3M', 'RSI', 'ADX', 'Vol', 'Entry', 'SL', 'Target', 'R:R', 'Status']
                  .map((h, i) => (
                    <th
                      key={h}
                      className={`px-3 py-3 text-xs font-semibold uppercase tracking-wide ${i >= 5 && i <= 12 ? 'text-right' : 'text-left'}`}
                      style={{ color: 'var(--text-muted)' }}
                    >
                      {h}
                    </th>
                  ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <Fragment key={row.symbol}>
                  <tr
                    onClick={() => setExpanded(expanded === row.symbol ? null : row.symbol)}
                    className="cursor-pointer transition-colors"
                    style={{ borderTop: '1px solid var(--gridline)' }}
                    onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--page-plane)')}
                    onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
                  >
                    <td className="tabular px-3 py-2.5 text-xs" style={{ color: 'var(--text-muted)' }}>{row.rank}</td>
                    <td className="px-3 py-2.5">
                      <div className="font-medium" style={{ color: 'var(--text-primary)' }}>{row.name ?? row.symbol}</div>
                      <div className="text-xs" style={{ color: 'var(--text-muted)' }}>
                        {row.symbol}{row.sector ? ` · ${row.sector}` : ''}
                      </div>
                    </td>
                    <td className="px-3 py-2.5">
                      <div className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
                        {num(row.score, 0)}
                      </div>
                      <div className="text-xs" style={{ color: CLASSIFICATION_COLOR[row.classification] ?? 'var(--text-muted)' }}>
                        {row.classification}
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                      {row.pattern.name}
                    </td>
                    <td className="px-3 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                      {row.trend.label}
                    </td>
                    <td
                      className="tabular px-3 py-2.5 text-right text-xs"
                      style={{ color: (row.summary.rs3mPct ?? 0) >= 0 ? 'var(--status-good)' : 'var(--status-critical)' }}
                    >
                      {signedPct(row.summary.rs3mPct)}
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                      {num(row.summary.rsi, 0)}
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                      {num(row.summary.adx, 0)}
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                      {num(row.summary.volumeRatio, 1)}×
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-primary)' }}>
                      {row.summary.entry != null ? fmtPrice(row.summary.entry) : '—'}
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--status-critical)' }}>
                      {row.summary.stopLoss != null ? fmtPrice(row.summary.stopLoss) : '—'}
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--status-good)' }}>
                      {row.summary.target != null ? fmtPrice(row.summary.target) : '—'}
                    </td>
                    <td className="tabular px-3 py-2.5 text-right text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>
                      {row.summary.riskReward != null ? `${num(row.summary.riskReward, 1)}:1` : '—'}
                    </td>
                    <td className="px-3 py-2.5">
                      <StatusBadgeBullish status={row.tradeStatus} />
                      {/* The stage is what the summary tiles group by, and it is not always
                          obvious from the status: a stock whose breakout is confirmed can still
                          read "Wait for breakout" when the trend is what is blocking the entry.
                          Showing both stops that looking like a contradiction. */}
                      {row.setupStage && row.setupStage !== row.tradeStatus && (
                        <div className="mt-0.5 text-[10px] uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                          {row.setupStage}
                        </div>
                      )}
                    </td>
                  </tr>
                  {expanded === row.symbol && (
                    <tr>
                      <td colSpan={14} className="p-0">
                        <BullishDetailPanel row={row} onOpenChart={setChartRow} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={14} className="px-4 py-8 text-center" style={{ color: 'var(--text-muted)' }}>
                    No stocks match this filter.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {chartRow && (
        <FullChartModal
          row={{
            symbol: chartRow.symbol,
            name: chartRow.name,
            values: {
              'Prev resistance': chartRow.breakout.level,
              'Breakout Confirm Level': chartRow.breakout.confirmedLevel,
            },
          }}
          onClose={() => setChartRow(null)}
        />
      )}
    </>
  )
}

const inr = (n) => '₹' + Math.round(Number(n) || 0).toLocaleString('en-IN')

/** Signed money, with the minus inside the currency rather than before it. */
const inrSigned = (n) => (Number(n) < 0 ? '−' + inr(Math.abs(n)).slice(1) : inr(n))

/** Compact money for chart labels, where four digits of precision is noise. */
const inrShort = (n) => {
  const v = Math.abs(Number(n) || 0)
  const sign = Number(n) < 0 ? '−' : ''
  if (v >= 10000000) return `${sign}₹${(v / 10000000).toFixed(1)}Cr`
  if (v >= 100000) return `${sign}₹${(v / 100000).toFixed(1)}L`
  if (v >= 1000) return `${sign}₹${Math.round(v / 1000)}k`
  return sign + '₹' + Math.round(v)
}

const MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

/** The three ways every rupee of income ends up, and the colour each carries throughout. */
const ALLOCATION = {
  emi: { label: 'Loan EMIs', color: 'var(--status-serious)' },
  expenses: { label: 'Fixed expenses', color: 'var(--accent)' },
  savings: { label: 'Left over', color: 'var(--status-good)' },
}

function periodLabel(period) {
  if (!period) return ''
  const [y, m] = period.split('-').map(Number)
  return `${MONTH_NAMES[m - 1]} ${y}`
}

function shiftPeriod(period, delta) {
  const [y, m] = period.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function thisPeriod() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function MoneyField({ value, onChange, align = 'right', bold = true }) {
  return (
    <span className="flex items-center">
      <span className="text-sm" style={{ color: 'var(--text-muted)' }}>₹</span>
      <input
        type="number"
        className="tabular w-28 rounded-md px-1.5 py-1 text-sm outline-none transition-colors focus:bg-[var(--accent-wash)]"
        style={{
          border: 'none', background: 'transparent', textAlign: align,
          fontWeight: bold ? 600 : 500, color: 'var(--text-primary)',
        }}
        value={value}
        onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
      />
    </span>
  )
}

/** Prev / label / next, as one segmented control rather than three loose buttons. */
function PeriodStepper({ label, onPrev, onNext, onToday, showToday, width = '7rem' }) {
  const btn = {
    background: 'transparent', border: 'none', color: 'var(--text-secondary)',
    padding: '0.4rem 0.7rem', cursor: 'pointer', fontSize: 14, lineHeight: 1,
  }
  return (
    <div className="flex items-center gap-2">
      <div className="flex items-center overflow-hidden rounded-lg border"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <button onClick={onPrev} style={btn} title="Previous">←</button>
        <span className="border-x px-3 py-1.5 text-center text-sm font-semibold"
          style={{ borderColor: 'var(--gridline)', color: 'var(--text-primary)', minWidth: width }}>
          {label}
        </span>
        <button onClick={onNext} style={btn} title="Next">→</button>
      </div>
      {showToday && (
        <button onClick={onToday} className="text-xs font-semibold" style={{ color: 'var(--accent)' }}>
          Today
        </button>
      )}
    </div>
  )
}

/**
 * Where the month's income actually went, as one stacked bar.
 *
 * <p>This replaced a single "committed" bar, which could only say how much was spent and not on
 * what. The split is the useful part: EMIs and rent are very different problems, and one bar that
 * merges them hides which of the two a month is actually losing to.
 */
function AllocationBar({ income, emi, expenses, savings }) {
  const total = Math.max(income, emi + expenses, 1)
  const seg = (v) => `${Math.max(0, (v / total) * 100)}%`
  const short = savings < 0

  return (
    <div>
      <div className="flex h-2.5 w-full overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
        <div style={{ width: seg(emi), background: ALLOCATION.emi.color }} title={`EMIs ${inr(emi)}`} />
        <div style={{ width: seg(expenses), background: ALLOCATION.expenses.color }} title={`Expenses ${inr(expenses)}`} />
        {!short && <div style={{ width: seg(savings), background: ALLOCATION.savings.color }} title={`Left ${inr(savings)}`} />}
      </div>
      <div className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1.5">
        {[
          ['emi', emi], ['expenses', expenses],
          ...(short ? [] : [['savings', savings]]),
        ].map(([k, v]) => (
          <span key={k} className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ background: ALLOCATION[k].color }} />
            <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>{ALLOCATION[k].label}</span>
            <span className="tabular text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{inr(v)}</span>
            {income > 0 && (
              <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>
                {Math.round((v / income) * 100)}%
              </span>
            )}
          </span>
        ))}
        {short && (
          <span className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full" style={{ background: 'var(--status-critical)' }} />
            <span className="text-xs font-semibold" style={{ color: 'var(--status-critical)' }}>
              Over by {inr(Math.abs(savings))}
            </span>
          </span>
        )}
      </div>
    </div>
  )
}

/** The headline: what is left, how that compares with last month, and where the rest went. */
function CashflowHero({ income, emi, expenses, savings, previousSavings, onIncomeChange }) {
  const short = savings < 0
  const accent = short ? 'var(--status-critical)' : 'var(--status-good)'
  const rate = income > 0 ? (savings / income) * 100 : null
  const delta = previousSavings == null ? null : savings - previousSavings

  return (
    <div className="mb-4 overflow-hidden rounded-xl border"
      style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', boxShadow: 'var(--shadow-md)' }}>
      <div className="h-1 w-full" style={{ background: accent }} />
      <div className="grid gap-5 p-5 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <div>
          <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            {short ? 'Short this month' : 'Left after everything'}
          </div>
          <div className="tabular mt-1 text-4xl font-semibold tracking-tight" style={{ color: accent }}>
            {inrSigned(savings)}
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            {rate != null && (
              <span className="rounded px-1.5 py-0.5 text-xs font-semibold"
                style={{ background: 'var(--accent-wash)', color: 'var(--accent)' }}>
                {Math.round(rate)}% of income saved
              </span>
            )}
            {delta != null && (
              <span className="tabular text-xs font-semibold"
                style={{ color: delta >= 0 ? 'var(--status-good)' : 'var(--status-critical)' }}>
                {delta >= 0 ? '▲' : '▼'} {inr(Math.abs(delta))} vs last month
              </span>
            )}
          </div>

          <div className="mt-4 flex items-center justify-between border-t pt-3" style={{ borderColor: 'var(--gridline)' }}>
            <span className="text-sm font-medium" style={{ color: 'var(--text-primary)' }}>Take-home salary</span>
            <MoneyField value={income} onChange={onIncomeChange} />
          </div>
        </div>

        <div className="flex flex-col justify-center lg:border-l lg:pl-5" style={{ borderColor: 'var(--gridline)' }}>
          <div className="mb-2.5 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Where it goes
          </div>
          <AllocationBar income={income} emi={emi} expenses={expenses} savings={savings} />
        </div>
      </div>
    </div>
  )
}

/**
 * What each finishing EMI hands back, as a timeline.
 *
 * <p>The most useful thing in a cashflow this loan-heavy: today's surplus is not the number that
 * matters, the one after the next EMI ends is. Drawn as dated milestones rather than bars, because
 * the question is <em>when</em> the money arrives, and a bar chart answers "how much" instead.
 */
function LoanForecast({ loans, surplus }) {
  const events = loans
    .filter((l) => Number(l.amount) > 0 && Number(l.remainingMonths) > 0)
    .map((l) => ({ month: Number(l.remainingMonths), emi: Number(l.amount), name: l.name }))
    .sort((a, b) => a.month - b.month)

  if (events.length === 0) return null

  const steps = []
  let running = surplus
  events.forEach((e) => {
    running += e.emi
    steps.push({ ...e, value: running })
  })
  const finalValue = running

  return (
    <div className="mb-4 overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b px-4 py-3" style={{ borderColor: 'var(--gridline)' }}>
        <div>
          <span className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>As your loans clear</span>
          <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
            Each EMI that ends frees up that cash every month afterwards
          </p>
        </div>
        <div className="text-right">
          <div className="tabular text-lg font-semibold" style={{ color: 'var(--status-good)' }}>{inrSigned(finalValue)}</div>
          <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            once all clear
          </div>
        </div>
      </div>

      <div className="px-4 py-3">
        <div className="flex items-center gap-3 pb-3">
          <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: 'var(--text-muted)' }} />
          <span className="text-xs" style={{ color: 'var(--text-secondary)' }}>Today</span>
          <span className="tabular ml-auto text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
            {inrSigned(surplus)}
          </span>
        </div>
        {steps.map((s, i) => (
          <div key={i} className="relative flex items-center gap-3 py-2">
            {/* connector back to the previous milestone */}
            <span className="absolute left-[4.5px] top-0 w-px" style={{ height: '50%', background: 'var(--gridline)' }} />
            {i < steps.length - 1 && (
              <span className="absolute left-[4.5px] bottom-0 w-px" style={{ height: '50%', background: 'var(--gridline)' }} />
            )}
            <span className="relative z-10 h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: 'var(--status-good)' }} />
            <div className="min-w-0">
              <div className="truncate text-xs font-medium" style={{ color: 'var(--text-primary)' }}>{s.name} clears</div>
              <div className="text-[11px]" style={{ color: 'var(--text-muted)' }}>in {s.month} month{s.month === 1 ? '' : 's'}</div>
            </div>
            <div className="ml-auto text-right">
              <div className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{inrSigned(s.value)}</div>
              <div className="tabular text-[11px] font-semibold" style={{ color: 'var(--status-good)' }}>
                +{inr(s.emi).slice(1)}/mo
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

/**
 * A ledger of loans or expenses.
 *
 * <p>Each row carries a bar showing its share of income. A column of numbers tells you the total;
 * the bars tell you which single line is the problem, which is the question someone opens this
 * page to answer.
 */
function Ledger({ title, hint, items, income, total, accent, showTenure, onChange, onAdd, onRemove }) {
  const peak = Math.max(...items.map((i) => Number(i.amount) || 0), 1)
  return (
    <div className="mb-4 overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b px-4 py-3" style={{ borderColor: 'var(--gridline)' }}>
        <div className="flex items-center gap-2">
          <span className="h-2 w-2 rounded-full" style={{ background: accent }} />
          <span className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</span>
          <span className="rounded px-1.5 py-0.5 text-[10px] font-semibold"
            style={{ background: 'var(--gridline)', color: 'var(--text-secondary)' }}>
            {items.length}
          </span>
        </div>
        <div className="text-right">
          <span className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{inr(total)}</span>
          {income > 0 && (
            <span className="tabular ml-1.5 text-xs" style={{ color: 'var(--text-muted)' }}>
              {Math.round((total / income) * 100)}%
            </span>
          )}
        </div>
      </div>

      {hint && <p className="px-4 pt-2.5 text-xs" style={{ color: 'var(--text-muted)' }}>{hint}</p>}

      <div className="px-4 pb-3 pt-1">
        {items.length === 0 && (
          <p className="py-4 text-center text-xs" style={{ color: 'var(--text-muted)' }}>Nothing here yet.</p>
        )}
        {items.map((item, i) => (
          <div key={i} className="border-b py-2 last:border-b-0" style={{ borderColor: 'var(--gridline)' }}>
            <div className="flex items-center gap-2">
              <input
                className="min-w-0 flex-1 rounded-md px-1 py-1 text-sm font-medium outline-none transition-colors focus:bg-[var(--accent-wash)]"
                style={{ border: 'none', background: 'transparent', color: 'var(--text-primary)' }}
                value={item.name}
                onChange={(e) => onChange(i, { ...item, name: e.target.value })}
              />
              {showTenure && (
                <span className="flex shrink-0 items-center gap-1" title="Months of EMI left after this one">
                  <input
                    type="number"
                    className="tabular w-11 rounded-md py-1 text-center text-xs font-semibold outline-none"
                    style={{ background: 'var(--gridline)', color: 'var(--text-primary)', border: 'none' }}
                    value={item.remainingMonths ?? ''}
                    onChange={(e) => onChange(i, { ...item, remainingMonths: e.target.value === '' ? null : Number(e.target.value) })}
                  />
                  <span className="text-[10px]" style={{ color: 'var(--text-muted)' }}>mo</span>
                </span>
              )}
              <MoneyField value={item.amount} onChange={(v) => onChange(i, { ...item, amount: v })} />
              <button onClick={() => onRemove(i)} className="shrink-0 px-1 text-sm transition-opacity hover:opacity-60"
                style={{ color: 'var(--text-muted)' }} title="Remove">&#10005;</button>
            </div>
            <div className="mt-1 h-1 w-full overflow-hidden rounded-full" style={{ background: 'var(--page-plane)' }}>
              <div className="h-full rounded-full"
                style={{ width: `${Math.max(1, ((Number(item.amount) || 0) / peak) * 100)}%`, background: accent, opacity: 0.55 }} />
            </div>
          </div>
        ))}
        <button onClick={onAdd}
          className="mt-3 flex w-full items-center justify-center gap-1.5 rounded-lg border border-dashed py-2 text-xs font-semibold transition-colors"
          style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)', background: 'transparent' }}
          onMouseEnter={(e) => { e.currentTarget.style.borderColor = accent; e.currentTarget.style.color = accent }}
          onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--border)'; e.currentTarget.style.color = 'var(--text-secondary)' }}>
          + Add {showTenure ? 'a loan' : 'an expense'}
        </button>
      </div>
    </div>
  )
}

/** One headline figure in a metric strip. */
function Metric({ label, value, color, sub }) {
  return (
    <div className="flex-1" style={{ minWidth: '8rem' }}>
      <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>{label}</div>
      <div className="tabular mt-0.5 text-xl font-semibold" style={{ color: color ?? 'var(--text-primary)' }}>{value}</div>
      {sub && <div className="mt-0.5 text-[11px]" style={{ color: 'var(--text-muted)' }}>{sub}</div>}
    </div>
  )
}

function MetricStrip({ children }) {
  return (
    <div className="mb-4 flex flex-wrap gap-x-8 gap-y-4 rounded-xl border p-4"
      style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      {children}
    </div>
  )
}

/** Savings per month, on a zero baseline so a short month reads as the deficit it is. */
function YearChart({ months }) {
  if (!months || months.length === 0) return null
  const peak = Math.max(...months.map((m) => Math.abs(m.savings)), 1)
  const anyNegative = months.some((m) => m.savings < 0)
  const plot = 120

  return (
    <div>
      <div className="flex items-stretch gap-2" style={{ height: anyNegative ? plot + 60 : plot + 26 }}>
        {months.map((m) => {
          const negative = m.savings < 0
          const h = Math.max(2, (Math.abs(m.savings) / peak) * plot)
          return (
            <div key={m.period} className="flex flex-col items-center" style={{ flex: '1 1 0', maxWidth: 68 }}
              title={`${periodLabel(m.period)} · ${inrSigned(m.savings)}`}>
              {/* above the baseline */}
              <div className="flex w-full flex-col justify-end" style={{ height: plot }}>
                {!negative && (
                  <>
                    <span className="tabular mb-1 text-center text-[10px] font-semibold" style={{ color: 'var(--text-secondary)' }}>
                      {inrShort(m.savings)}
                    </span>
                    <div className="w-full rounded-t" style={{ height: h, background: 'var(--status-good)' }} />
                  </>
                )}
              </div>
              <div className="h-px w-full" style={{ background: 'var(--border-strong)' }} />
              {/* below the baseline */}
              {anyNegative && (
                <div className="flex w-full flex-col justify-start" style={{ height: 34 }}>
                  {negative && (
                    <>
                      <div className="w-full rounded-b" style={{ height: Math.min(20, h), background: 'var(--status-critical)' }} />
                      {/* A deficit month needs its figure as much as a surplus one — without it the
                          bar below the line is an unexplained red stub. */}
                      <span className="tabular mt-0.5 text-center text-[10px] font-semibold"
                        style={{ color: 'var(--status-critical)' }}>
                        {inrShort(m.savings)}
                      </span>
                    </>
                  )}
                </div>
              )}
              <span className="mt-1 text-[10px]" style={{ color: 'var(--text-muted)' }}>
                {MONTH_NAMES[Number(m.period.split('-')[1]) - 1]}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function MoneyTable({ head, children, note }) {
  return (
    <div className="overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr style={{ borderBottom: '1px solid var(--gridline)' }}>
              {head.map((h, i) => (
                <th key={h} className={`px-3 py-3 text-xs font-semibold uppercase tracking-wide ${i === 0 ? 'text-left' : 'text-right'}`}
                  style={{ color: 'var(--text-muted)' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>{children}</tbody>
        </table>
      </div>
      {note && (
        <p className="border-t px-3 py-2.5 text-xs" style={{ borderColor: 'var(--gridline)', color: 'var(--text-muted)' }}>{note}</p>
      )}
    </div>
  )
}

function EmptyPanel({ children }) {
  return (
    <div className="rounded-xl border p-10 text-center" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <p className="mx-auto max-w-sm text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{children}</p>
    </div>
  )
}

/**
 * The expense tracker: one editable month, plus the year and year-on-year views over it.
 *
 * <p>Months are separate records rather than one rolling snapshot, so a change to this month never
 * rewrites last month's history - which is the whole point of tracking. Recurring rows carry
 * forward automatically, with loan tenures advanced, so a new month starts pre-filled rather than
 * blank.
 */
function ExpensesView() {
  const [tab, setTab] = useState('month')
  const [period, setPeriod] = useState(thisPeriod)
  const [month, setMonth] = useState(null)
  const [history, setHistory] = useState([])
  const [year, setYear] = useState(null)
  const [yoy, setYoy] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState(null)
  const [yearShown, setYearShown] = useState(new Date().getFullYear())

  function loadMonth(p) {
    setLoading(true)
    return fetch(`/api/expenses/months/${p}`)
      .then((r) => r.json())
      .then((d) => { setMonth(d); setDirty(false); setError(null) })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  }

  function loadHistory() {
    return fetch('/api/expenses/months').then((r) => r.json()).then(setHistory).catch(() => {})
  }

  function loadSummaries(y) {
    fetch(`/api/expenses/year?year=${y}`).then((r) => r.json()).then(setYear).catch(() => {})
    fetch('/api/expenses/year-on-year').then((r) => r.json()).then(setYoy).catch(() => {})
  }

  useEffect(() => { loadMonth(period) }, [period])
  useEffect(() => { loadHistory() }, [])
  useEffect(() => { loadSummaries(yearShown) }, [yearShown])

  async function save() {
    if (!month) return
    setSaving(true)
    setError(null)
    try {
      const items = [
        ...(month.loans ?? []).map((l) => ({ kind: 'LOAN', name: l.name, amount: Number(l.amount) || 0, remainingMonths: l.remainingMonths ?? null })),
        ...(month.expenses ?? []).map((e) => ({ kind: 'EXPENSE', name: e.name, amount: Number(e.amount) || 0, remainingMonths: null })),
      ]
      const res = await fetch(`/api/expenses/months/${period}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ income: Number(month.income) || 0, items }),
      })
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
      setMonth(body)
      setDirty(false)
      loadHistory()
      loadSummaries(yearShown)
    } catch (e) {
      setError(e.message || 'Could not save')
    } finally {
      setSaving(false)
    }
  }

  function edit(patch) {
    setMonth((m) => ({ ...m, ...patch }))
    setDirty(true)
  }

  // Totals are recomputed locally while editing so the figures move as you type; the server
  // recomputes them the same way on save, so the two cannot disagree once persisted.
  const loans = month?.loans ?? []
  const expenses = month?.expenses ?? []
  const totalEmi = loans.reduce((s, l) => s + (Number(l.amount) || 0), 0)
  const totalExp = expenses.reduce((s, e) => s + (Number(e.amount) || 0), 0)
  const income = Number(month?.income) || 0
  const surplus = income - totalEmi - totalExp

  // Month-on-month is the comparison this whole feature exists to enable, so it belongs on the
  // headline rather than only in the year table.
  const previousSavings = useMemo(() => {
    const prev = history.find((h) => h.period === shiftPeriod(period, -1))
    return prev ? prev.savings : null
  }, [history, period])

  if (loading && !month) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 py-16">
        <div className="animate-pulse" style={{ color: 'var(--accent)' }}><Logo size={30} /></div>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>Loading your cashflow…</p>
      </div>
    )
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {[['month', 'This month'], ['year', 'Year'], ['yoy', 'Year on year']].map(([k, label]) => (
          <FilterPill key={k} active={tab === k} onClick={() => setTab(k)}>{label}</FilterPill>
        ))}
      </div>

      {tab === 'month' && (
        <>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <PeriodStepper
              label={periodLabel(period)}
              onPrev={() => setPeriod(shiftPeriod(period, -1))}
              onNext={() => setPeriod(shiftPeriod(period, 1))}
              onToday={() => setPeriod(thisPeriod())}
              showToday={period !== thisPeriod()}
            />
            <div className="flex items-center gap-3">
              {month && !month.saved && (
                <span className="rounded px-2 py-1 text-xs font-medium"
                  style={{ background: 'var(--gridline)', color: 'var(--text-secondary)' }}>
                  {month.carriedFrom ? `Carried from ${periodLabel(month.carriedFrom)}` : 'Not recorded yet'}
                </span>
              )}
              {month?.saved && !dirty && !saving && (
                <span className="flex items-center gap-1.5 text-xs font-semibold" style={{ color: 'var(--status-good)' }}>
                  <Dot color="var(--status-good)" size={6} /> Saved
                </span>
              )}
              {(dirty || !month?.saved) && (
                <button onClick={save} disabled={saving}
                  className="rounded-lg px-4 py-1.5 text-sm font-semibold text-white shadow-sm transition-opacity disabled:opacity-50"
                  style={{ background: 'var(--accent)' }}>
                  {saving ? 'Saving…' : 'Save month'}
                </button>
              )}
            </div>
          </div>

          {error && <p className="mb-3 text-sm" style={{ color: 'var(--status-serious)' }}>{error}</p>}

          <CashflowHero
            income={income} emi={totalEmi} expenses={totalExp} savings={surplus}
            previousSavings={previousSavings}
            onIncomeChange={(v) => edit({ income: v })}
          />

          <LoanForecast loans={loans} surplus={surplus} />

          <div className="grid gap-0 lg:grid-cols-2 lg:gap-4">
            <Ledger
              title="Loan EMIs" accent={ALLOCATION.emi.color} showTenure
              hint="The small box is months left before that EMI ends"
              items={loans} income={income} total={totalEmi}
              onChange={(i, next) => edit({ loans: loans.map((x, j) => (j === i ? next : x)) })}
              onRemove={(i) => edit({ loans: loans.filter((_, j) => j !== i) })}
              onAdd={() => edit({ loans: [...loans, { name: 'New loan', amount: 0, remainingMonths: 12 }] })}
            />
            <Ledger
              title="Fixed expenses" accent={ALLOCATION.expenses.color}
              items={expenses} income={income} total={totalExp}
              onChange={(i, next) => edit({ expenses: expenses.map((x, j) => (j === i ? next : x)) })}
              onRemove={(i) => edit({ expenses: expenses.filter((_, j) => j !== i) })}
              onAdd={() => edit({ expenses: [...expenses, { name: 'New expense', amount: 0 }] })}
            />
          </div>
        </>
      )}

      {tab === 'year' && year && (
        <>
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <PeriodStepper
              label={String(yearShown)} width="4rem"
              onPrev={() => setYearShown(yearShown - 1)}
              onNext={() => setYearShown(yearShown + 1)}
              onToday={() => setYearShown(new Date().getFullYear())}
              showToday={yearShown !== new Date().getFullYear()}
            />
            <span className="text-xs" style={{ color: 'var(--text-muted)' }}>
              {year.monthsRecorded} of 12 months recorded
            </span>
          </div>

          <MetricStrip>
            <Metric label={`Saved in ${yearShown}`} value={inrSigned(year.totalSavings)}
              color={year.totalSavings < 0 ? 'var(--status-critical)' : 'var(--status-good)'}
              sub={Number.isNaN(year.savingsRatePct) ? null : `${Math.round(year.savingsRatePct)}% of income`} />
            <Metric label="Income" value={inr(year.totalIncome)} />
            <Metric label="Loan EMIs" value={inr(year.totalEmi)} color={ALLOCATION.emi.color} />
            <Metric label="Fixed expenses" value={inr(year.totalExpenses)} color={ALLOCATION.expenses.color} />
            <Metric label="Average / month" value={inrSigned(year.averageMonthlySavings)} />
          </MetricStrip>

          {year.monthsRecorded > 0 ? (
            <>
              <div className="mb-4 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
                <h3 className="mb-3 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                  Saved each month
                </h3>
                <YearChart months={year.months} />
              </div>
              <MoneyTable head={['Month', 'Income', 'EMIs', 'Expenses', 'Saved', 'Rate']}>
                {year.months.map((m) => (
                  <tr key={m.period} style={{ borderTop: '1px solid var(--gridline)' }}>
                    <td className="px-3 py-2.5 font-medium" style={{ color: 'var(--text-primary)' }}>{periodLabel(m.period)}</td>
                    <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>{inr(m.income)}</td>
                    <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>{inr(m.totalEmi)}</td>
                    <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>{inr(m.totalExpenses)}</td>
                    <td className="tabular px-3 py-2.5 text-right font-semibold"
                      style={{ color: m.savings < 0 ? 'var(--status-critical)' : 'var(--status-good)' }}>{inrSigned(m.savings)}</td>
                    <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-muted)' }}>
                      {Number.isNaN(m.savingsRatePct) || m.savingsRatePct == null ? '—' : `${Math.round(m.savingsRatePct)}%`}
                    </td>
                  </tr>
                ))}
              </MoneyTable>
            </>
          ) : (
            <EmptyPanel>
              Nothing recorded for {yearShown} yet. Save a month on the This month tab and it will appear here.
            </EmptyPanel>
          )}
        </>
      )}

      {tab === 'yoy' && yoy && (
        <>
          <MetricStrip>
            <Metric label="Saved all time" value={inrSigned(yoy.lifetimeSavings)}
              color={yoy.lifetimeSavings < 0 ? 'var(--status-critical)' : 'var(--status-good)'} />
            <Metric label="Years tracked" value={yoy.years.length} />
            <Metric label="Best year"
              value={yoy.years.length ? inrSigned(Math.max(...yoy.years.map((y) => y.totalSavings))) : '—'}
              sub={yoy.years.length
                ? String(yoy.years.reduce((a, b) => (b.totalSavings > a.totalSavings ? b : a)).year)
                : null} />
          </MetricStrip>

          {yoy.years.length === 0 ? (
            <EmptyPanel>No years tracked yet. Save your first month to start the record.</EmptyPanel>
          ) : (
            <MoneyTable
              head={['Year', 'Months', 'Income', 'Committed', 'Saved', 'Rate', 'vs prev year']}
              note="A year marked partial has fewer than twelve recorded months, so its total is not a like-for-like comparison against a complete one."
            >
              {[...yoy.years].reverse().map((y) => (
                <tr key={y.year} style={{ borderTop: '1px solid var(--gridline)' }}>
                  <td className="px-3 py-2.5 font-semibold" style={{ color: 'var(--text-primary)' }}>
                    {y.year}
                    {!y.complete && (
                      <span className="ml-1.5 rounded px-1 py-0.5 text-[10px] font-medium"
                        style={{ background: 'var(--gridline)', color: 'var(--text-muted)' }}>partial</span>
                    )}
                  </td>
                  <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-muted)' }}>{y.monthsRecorded}</td>
                  <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>{inr(y.totalIncome)}</td>
                  <td className="tabular px-3 py-2.5 text-right" style={{ color: 'var(--text-secondary)' }}>{inr(y.totalCommitted)}</td>
                  <td className="tabular px-3 py-2.5 text-right font-semibold"
                    style={{ color: y.totalSavings < 0 ? 'var(--status-critical)' : 'var(--status-good)' }}>{inrSigned(y.totalSavings)}</td>
                  <td className="tabular px-3 py-2.5 text-right text-xs" style={{ color: 'var(--text-muted)' }}>
                    {Number.isNaN(y.savingsRatePct) || y.savingsRatePct == null ? '—' : `${Math.round(y.savingsRatePct)}%`}
                  </td>
                  <td className="tabular px-3 py-2.5 text-right text-xs"
                    style={{ color: y.changeVsPreviousYear == null ? 'var(--text-muted)' : y.changeVsPreviousYear >= 0 ? 'var(--status-good)' : 'var(--status-critical)' }}>
                    {y.changeVsPreviousYear == null ? '—' : (y.changeVsPreviousYear >= 0 ? '▲ ' : '▼ ') + inr(Math.abs(y.changeVsPreviousYear)).slice(1)}
                  </td>
                </tr>
              ))}
            </MoneyTable>
          )}
        </>
      )}
    </>
  )
}

/**
 * The in-place empty state for a view whose scan has not produced anything yet.
 *
 * <p>Used by Reversal Watch, whose signals only exist once its scan has run. It sits
 * inside the normal shell rather than replacing the page, so the tab row stays usable while a scan
 * runs - the old behaviour took over the whole screen and made the rest of the app unreachable.
 */
function ScanPending({ scanning, progress, error, onScan, what, description, queued, queuedBehind }) {
  return (
    <div
      className="mx-auto max-w-md rounded-2xl border p-8 text-center"
      style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', boxShadow: 'var(--shadow-md)' }}
    >
      {queued && !scanning ? (
        <>
          <div className="mx-auto w-fit animate-pulse" style={{ color: 'var(--accent)' }}><Logo size={34} /></div>
          <p className="mt-4 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>Queued</p>
          <p className="mx-auto mt-2 max-w-xs text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            Waiting for the {queuedBehind} to finish, then this starts automatically. They share the
            same fetched bars, so running them one after the other is far faster than at once.
          </p>
        </>
      ) : scanning ? (
        <>
          <div className="mx-auto w-fit animate-pulse" style={{ color: 'var(--accent)' }}><Logo size={34} /></div>
          <p className="mt-4 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>Scanning the Nifty 500…</p>
          <p className="tabular mx-auto mt-2 text-sm" style={{ color: 'var(--text-muted)' }}>{progress ?? 'Starting…'}</p>
          <p className="mx-auto mt-3 max-w-xs text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            The first run of a session fetches around 500 symbols. You can switch tabs while it
            finishes — it keeps going in the background.
          </p>
        </>
      ) : (
        <>
          <div className="mx-auto w-fit"><LogoBadge size={48} /></div>
          <p className="mt-4 text-lg font-semibold" style={{ color: 'var(--text-primary)' }}>
            {error ? `Could not run the ${what} scan` : `${what} has not run yet`}
          </p>
          <p className="mx-auto mt-2 max-w-xs text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            {error ?? description}
          </p>
          <button
            onClick={onScan}
            className="mt-6 inline-flex items-center gap-2 rounded-lg px-5 py-2.5 text-sm font-semibold text-white shadow-sm transition-opacity hover:opacity-90"
            style={{ background: 'var(--accent)' }}
          >
            {error ? 'Try again' : 'Run the scan'}
          </button>
        </>
      )}
    </div>
  )
}

/** Segmented strength meter — reads at a glance in a way a bare "1/10" does not. */
function RegimeMeter({ score, maxScore, color }) {
  return (
    <div className="flex items-center gap-2">
      <div className="flex gap-[3px]">
        {Array.from({ length: maxScore }, (_, i) => (
          <span
            key={i}
            className="h-4 w-1.5 rounded-full"
            style={{ background: i < score ? color : 'var(--gridline)' }}
          />
        ))}
      </div>
      <span className="tabular text-xs font-semibold" style={{ color: 'var(--text-muted)' }}>
        {score}/{maxScore}
      </span>
    </div>
  )
}

/** Price against one moving average, as a compact above/below chip. */
function EmaChip({ label, above }) {
  return (
    <span
      className="rounded px-1.5 py-0.5 text-[10px] font-semibold"
      style={{
        background: above ? 'rgba(12, 163, 12, 0.12)' : 'rgba(208, 59, 59, 0.12)',
        color: above ? 'var(--status-good)' : 'var(--status-critical)',
      }}
    >
      {above ? '▲' : '▼'} {label}
    </span>
  )
}

function ReturnCell({ label, pct }) {
  const known = pct != null && !Number.isNaN(pct)
  const color = !known ? 'var(--text-muted)' : pct >= 0 ? 'var(--status-good)' : 'var(--status-critical)'
  return (
    <div>
      <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
        {label}
      </div>
      <div className="tabular text-sm font-semibold" style={{ color }}>
        {known ? `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%` : '—'}
      </div>
    </div>
  )
}

/** One benchmark index: where it trades relative to its own averages, and how it has moved. */
function IndexPanel({ index }) {
  if (!index?.known) {
    return (
      <div className="rounded-lg border p-3" style={{ borderColor: 'var(--gridline)' }}>
        <div className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{index?.name ?? 'Index'}</div>
        <div className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>No data</div>
      </div>
    )
  }
  return (
    <div className="rounded-lg border p-3" style={{ borderColor: 'var(--gridline)', background: 'var(--page-plane)' }}>
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{index.name}</span>
        <span className="tabular text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
          {fmtPrice(index.price)}
        </span>
      </div>
      <div className="mt-2.5 flex gap-4">
        <ReturnCell label="1M" pct={index.return1mPct} />
        <ReturnCell label="3M" pct={index.return3mPct} />
        <div>
          <div className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Structure
          </div>
          <div className="text-sm font-semibold" style={{
            color: index.structure === 'HH+HL' ? 'var(--status-good)'
              : index.structure === 'LH+LL' ? 'var(--status-critical)' : 'var(--text-secondary)',
          }}>
            {index.structure}
          </div>
        </div>
      </div>
      <div className="mt-2.5 flex flex-wrap gap-1">
        <EmaChip label="20 EMA" above={index.price > index.ema20} />
        <EmaChip label="50 EMA" above={index.price > index.ema50} />
        <EmaChip label="200 EMA" above={index.price > index.ema200} />
      </div>
    </div>
  )
}

/**
 * The market-condition panel that leads the dashboard.
 *
 * <p>It is the one thing the app can answer before any scan has run, so it gets the space: the
 * regime verdict, the meter behind it, and both benchmark indices broken out. Showing the two
 * separately matters because they disagree in a way that is worth seeing — a market carried by ten
 * large caps scores well on the Nifty 50 and badly on the Nifty 500.
 */
function RegimeHero({ regime, error }) {
  if (!regime) {
    return (
      <div className="mb-5 rounded-xl border px-5 py-6"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <div className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Market regime
        </div>
        <div className="mt-1.5 text-sm" style={{ color: error ? 'var(--status-serious)' : 'var(--text-muted)' }}>
          {error ? `Unavailable: ${error}` : 'Reading the index data…'}
        </div>
      </div>
    )
  }

  const meta = REGIME_META[regime.regime] ?? { color: 'var(--text-muted)', label: regime.regime }
  return (
    <div
      className="mb-5 overflow-hidden rounded-xl border"
      style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', boxShadow: 'var(--shadow-md)' }}
    >
      <div className="h-1 w-full" style={{ background: meta.color }} />
      <div className="grid gap-5 p-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
        <div>
          <div className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Market regime
          </div>
          <div className="mt-1.5 flex flex-wrap items-center gap-3">
            <span className="text-3xl font-semibold tracking-tight" style={{ color: meta.color }}>
              {meta.label}
            </span>
            <RegimeMeter score={regime.score} maxScore={regime.maxScore} color={meta.color} />
          </div>
          <p className="mt-3 max-w-md text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
            {regime.summary}
          </p>
          {!regime.allowsBuyNow && (
            <div
              className="mt-3 inline-flex items-center gap-2 rounded-lg px-3 py-1.5 text-xs font-semibold"
              style={{ background: 'rgba(208, 59, 59, 0.1)', color: 'var(--status-critical)' }}
            >
              <Dot color="var(--status-critical)" size={7} />
              No entries are called while the market is bearish
            </div>
          )}
        </div>
        {/* self-start so the index panels size to their own content rather than stretching to
            match the summary column, which leaves a block of empty card under them. */}
        <div className="grid gap-3 self-start sm:grid-cols-2">
          <IndexPanel index={regime.nifty50} />
          <IndexPanel index={regime.nifty500} />
        </div>
      </div>
    </div>
  )
}

/** Status pill on a feature card: never run / running / ready. */
function CardStatus({ state, detail }) {
  const meta = state === 'running'
    ? { color: 'var(--status-warning)', label: 'Scanning' }
    : state === 'ready'
      ? { color: 'var(--status-good)', label: 'Ready' }
      : { color: 'var(--text-muted)', label: 'Not run yet' }
  return (
    <span className="flex shrink-0 items-center gap-1.5 text-xs font-semibold" style={{ color: meta.color }}>
      {state === 'running'
        ? <span className="inline-block animate-spin" style={{ color: meta.color }}>&#8635;</span>
        : <Dot color={meta.color} size={7} />}
      {detail ?? meta.label}
    </span>
  )
}

/**
 * One feature on the dashboard. Clicking it opens that tab, which is also what starts its scan —
 * so the card is the affordance for "go look at this", not a second place to trigger work.
 */
function FeatureCard({ title, description, state, detail, metrics, accent, onOpen }) {
  const [hover, setHover] = useState(false)
  return (
    <button
      onClick={onOpen}
      className="group flex w-full flex-col overflow-hidden rounded-xl border text-left transition-all"
      style={{
        borderColor: hover ? 'var(--border-strong)' : 'var(--border)',
        background: 'var(--surface-1)',
        boxShadow: hover ? 'var(--shadow-md)' : 'none',
        transform: hover ? 'translateY(-1px)' : 'none',
      }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <div className="h-0.5 w-full" style={{ background: state === 'idle' ? 'var(--gridline)' : accent }} />
      <div className="flex flex-1 flex-col gap-2.5 p-4">
        <div className="flex items-start justify-between gap-3">
          <span className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>{title}</span>
          <CardStatus state={state} detail={detail} />
        </div>
        <p className="text-xs leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{description}</p>

        <div className="mt-auto flex items-end justify-between gap-3 pt-1">
          {metrics && metrics.length > 0 ? (
            <div className="flex flex-wrap gap-x-5 gap-y-2">
              {metrics.map((m) => (
                <div key={m.label}>
                  <div className="tabular text-xl font-semibold leading-none" style={{ color: m.color ?? 'var(--text-primary)' }}>
                    {m.value}
                  </div>
                  <div className="mt-1 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                    {m.label}
                  </div>
                </div>
              ))}
            </div>
          ) : <span />}
          <span
            className="shrink-0 text-xs font-semibold transition-opacity"
            style={{ color: 'var(--accent)', opacity: hover ? 1 : 0.55 }}
          >
            Open →
          </span>
        </div>
      </div>
    </button>
  )
}

/**
 * The highest-ranked setups, inline.
 *
 * <p>A dashboard made purely of navigation cards makes the user click through to learn anything.
 * Once the ranking exists, its top rows are the single most useful thing the app can show, so they
 * belong on the landing page rather than one tab away.
 */
function TopSetups({ stocks, onOpen }) {
  const top = stocks.slice(0, 5)
  if (top.length === 0) return null
  return (
    <div className="mb-5 overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="flex items-center justify-between gap-3 border-b px-4 py-3" style={{ borderColor: 'var(--gridline)' }}>
        <span className="text-xs font-semibold uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
          Top-ranked setups
        </span>
        <button onClick={onOpen} className="text-xs font-semibold" style={{ color: 'var(--accent)' }}>
          See all →
        </button>
      </div>
      <div className="divide-y" style={{ borderColor: 'var(--gridline)' }}>
        {top.map((s) => {
          const meta = BULLISH_STATUS_META[s.tradeStatus] ?? { color: 'var(--text-muted)', label: s.tradeStatus }
          return (
            <button
              key={s.symbol}
              onClick={onOpen}
              className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors"
              onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--page-plane)')}
              onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}
            >
              <span className="tabular w-5 shrink-0 text-xs" style={{ color: 'var(--text-muted)' }}>{s.rank}</span>
              <span
                className="tabular w-10 shrink-0 rounded px-1.5 py-0.5 text-center text-xs font-semibold"
                style={{ background: 'var(--accent-wash)', color: 'var(--accent)' }}
              >
                {Math.round(s.score)}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium" style={{ color: 'var(--text-primary)' }}>
                  {s.name ?? s.symbol}
                </span>
                <span className="block truncate text-xs" style={{ color: 'var(--text-muted)' }}>
                  {s.pattern.name}{s.sector ? ` · ${s.sector}` : ''}
                </span>
              </span>
              <span className="hidden shrink-0 text-xs sm:block" style={{ color: 'var(--text-secondary)' }}>
                {s.trend.label}
              </span>
              <span className="flex shrink-0 items-center gap-1.5 text-xs font-semibold" style={{ color: meta.color }}>
                <Dot color={meta.color} size={6} />
                <span className="hidden md:inline">{meta.label}</span>
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

/**
 * The landing view.
 *
 * <p>It deliberately does not require a scan. The market regime comes from two index series the
 * server warms at startup, so the dashboard can answer "what is the market doing" the instant the
 * app opens; everything else reports whether its scan has run and what it found, and opening a tab
 * is what starts that scan. The previous behaviour — a full-screen "No scan data yet" wall in front
 * of the entire app — made the first five minutes of every cold start show nothing at all.
 */
function DashboardView({ reversal, bullish, index500, watchlistCount, onOpen }) {
  const [regime, setRegime] = useState(null)
  const [regimeError, setRegimeError] = useState(null)
  const [money, setMoney] = useState(null)

  useEffect(() => {
    let cancelled = false
    fetch('/api/market-regime')
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json()
      })
      .then((d) => { if (!cancelled) setRegime(d) })
      .catch((e) => { if (!cancelled) setRegimeError(e.message) })

    // Cheap and always available — no scan stands behind it, so the card is useful on a cold start.
    fetch('/api/expenses/overview')
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (!cancelled) setMoney(d) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [])

  const reversals = reversal.data?.reversals?.length ?? 0

  const reversalState = reversal.scanning ? 'running' : reversal.data ? 'ready' : 'idle'
  const bullishState = bullish.scanning ? 'running' : bullish.data ? 'ready' : 'idle'

  return (
    <>
      <RegimeHero regime={regime} error={regimeError} />

      {bullish.data && <TopSetups stocks={bullish.data.stocks ?? []} onOpen={() => onOpen('bullish')} />}

      {money && (money.currentMonth || money.latestMonth) && (() => {
        const m = money.currentMonth ?? money.latestMonth
        const y = money.thisYear
        const short = m.savings < 0
        return (
          <button
            onClick={() => onOpen('expenses')}
            className="mb-5 flex w-full flex-col gap-4 overflow-hidden rounded-xl border p-5 text-left transition-colors sm:flex-row sm:items-center sm:justify-between"
            style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}
            onMouseEnter={(e) => (e.currentTarget.style.borderColor = 'var(--border-strong)')}
            onMouseLeave={(e) => (e.currentTarget.style.borderColor = 'var(--border)')}
          >
            <div>
              <div className="text-xs font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                {short ? 'Short in' : 'Saved in'} {periodLabel(m.period)}
                {!money.currentMonthRecorded && (
                  <span className="ml-1.5 normal-case" style={{ color: 'var(--text-muted)' }}>
                    · {periodLabel(money.currentPeriod)} not recorded yet
                  </span>
                )}
              </div>
              <div className="tabular mt-1 text-3xl font-semibold tracking-tight"
                style={{ color: short ? 'var(--status-critical)' : 'var(--status-good)' }}>
                {inrSigned(m.savings)}
              </div>
              <div className="mt-1 text-xs" style={{ color: 'var(--text-secondary)' }}>
                {inr(m.income)} in · {inr(m.committed)} committed
                {Number.isNaN(m.savingsRatePct) ? '' : ` · ${Math.round(m.savingsRatePct)}% saved`}
              </div>
            </div>
            <div className="flex flex-wrap gap-6 border-t pt-4 sm:border-l sm:border-t-0 sm:pl-6 sm:pt-0"
              style={{ borderColor: 'var(--gridline)' }}>
              <div>
                <div className="tabular text-xl font-semibold" style={{ color: y.totalSavings < 0 ? 'var(--status-critical)' : 'var(--status-good)' }}>
                  {inrSigned(y.totalSavings)}
                </div>
                <div className="mt-0.5 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                  Saved in {y.year}
                </div>
              </div>
              <div>
                <div className="tabular text-xl font-semibold" style={{ color: 'var(--text-primary)' }}>
                  {inr(y.totalCommitted)}
                </div>
                <div className="mt-0.5 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                  Spent in {y.year}
                </div>
              </div>
              <div>
                <div className="tabular text-xl font-semibold" style={{ color: 'var(--accent)' }}>
                  {inrSigned(money.yearOnYear?.lifetimeSavings ?? 0)}
                </div>
                <div className="mt-0.5 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
                  All time · {money.monthsTracked} mo
                </div>
              </div>
            </div>
          </button>
        )
      })()}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FeatureCard
          title="Bullish Stocks"
          description="Ranks the Nifty 500 out of 100 on trend, relative strength, momentum, volume, structure, pattern, breakout quality and risk/reward."
          state={bullishState}
          accent="var(--status-good)"
          detail={bullish.scanning ? (bullish.progress ?? 'Ranking') : undefined}
          metrics={bullish.data ? [
            { label: 'Bullish', value: bullish.data.bullishStockCount, color: 'var(--accent)' },
            { label: 'A+', value: bullish.data.aPlusCount, color: 'var(--status-good)' },
            { label: 'Buy now', value: bullish.data.buyNowCount, color: 'var(--status-good)' },
          ] : null}
          onOpen={() => onOpen('bullish')}
        />

        <FeatureCard
          title="Index 500 Analysis"
          description="Ranks every sector by six-month performance, then finds which fallen stocks inside them are showing a confirmed reversal or breakout pattern."
          state={index500.scanning ? 'running' : index500.data ? 'ready' : 'idle'}
          accent="var(--cat-nifty50)"
          detail={index500.scanning ? (index500.progress ?? 'Analysing') : undefined}
          metrics={index500.data ? [
            { label: 'Decliners', value: index500.data.declinerCount, color: 'var(--status-critical)' },
            { label: 'Patterns', value: index500.data.patternCount, color: 'var(--accent)' },
            { label: 'Reversals', value: index500.data.reversalCount, color: 'var(--status-good)' },
          ] : null}
          onOpen={() => onOpen('index500')}
        />

        <FeatureCard
          title="Reversal Watch"
          description="Scans the Nifty 500 for candlestick-confirmed reversal setups in beaten-down names — a bearish 12-month context, a reversal pattern, and a confirmed close above it."
          state={reversalState}
          accent="var(--status-warning)"
          detail={reversal.scanning ? (reversal.progress ?? 'Scanning') : undefined}
          metrics={reversal.data ? [
            { label: 'Confirmed signals', value: reversals, color: reversals > 0 ? 'var(--status-good)' : undefined },
          ] : null}
          onOpen={() => onOpen('reversal')}
        />

        <FeatureCard
          title="My Watchlist"
          description="Symbols you follow, scored live by the bullish engine on every load — including names outside the index."
          state={watchlistCount > 0 ? 'ready' : 'idle'}
          accent="var(--cat-custom)"
          detail={watchlistCount > 0 ? `${watchlistCount} followed` : 'Empty'}
          metrics={watchlistCount > 0 ? [{ label: 'Followed', value: watchlistCount }] : null}
          onOpen={() => onOpen('lookup')}
        />

        <FeatureCard
          title="Expenses"
          description="Salary, loan EMIs and fixed costs tracked month by month, with a forecast of the cash each EMI hands back when it ends."
          state={money && money.monthsTracked > 0 ? 'ready' : 'idle'}
          accent="var(--cat-next50)"
          detail={money && money.monthsTracked > 0 ? `${money.monthsTracked} month${money.monthsTracked === 1 ? '' : 's'}` : 'Nothing tracked'}
          metrics={money && money.thisYear ? [
            { label: `Saved ${money.thisYear.year}`, value: inrSigned(money.thisYear.totalSavings),
              color: money.thisYear.totalSavings < 0 ? 'var(--status-critical)' : 'var(--status-good)' },
          ] : null}
          onOpen={() => onOpen('expenses')}
        />

        <FeatureCard
          title="Trade Journal"
          description="Your delivery and swing trade log, an auto-calculated performance dashboard, and a 1:2 risk/reward calculator."
          state="ready"
          accent="var(--cat-next50)"
          detail="Always available"
          metrics={null}
          onOpen={() => onOpen('journal')}
        />
      </div>
    </>
  )
}

const INDEX500_STATUS_META = {
  'STRONG REVERSAL': { color: 'var(--status-good)', label: 'Strong reversal' },
  'REVERSAL WATCH': { color: 'var(--status-good)', label: 'Reversal watch' },
  'BREAKOUT CONFIRMED': { color: 'var(--accent)', label: 'Breakout confirmed' },
  'BREAKOUT CANDIDATE': { color: 'var(--accent)', label: 'Breakout candidate' },
  RECOVERY: { color: 'var(--status-warning)', label: 'Recovery' },
  'WAIT FOR CONFIRMATION': { color: 'var(--status-warning)', label: 'Wait for confirmation' },
  WEAK: { color: 'var(--text-muted)', label: 'Weak' },
  AVOID: { color: 'var(--status-critical)', label: 'Avoid' },
  DATA_UNAVAILABLE: { color: 'var(--text-muted)', label: 'No data' },
}

/** The sort keys the ranking service understands, with the wording this tab uses for them. */
const INDEX500_SORTS = [
  ['score', 'Opportunity score'],
  ['decline', 'Biggest 6M decline'],
  ['recovery', 'Strongest recovery'],
  ['pattern', 'Pattern quality'],
  ['return1d', "Today's move"],
  ['return1m', '1M return'],
  ['return3m', '3M return'],
  ['return1y', '1Y return'],
  ['rsi', 'RSI'],
  ['adx', 'ADX'],
  ['volume', 'Volume ratio'],
  ['fromHigh', 'Furthest below 52W high'],
]

const INDEX500_SCORE_LABELS = {
  decline: 'Decline depth',
  pattern: 'Reversal pattern',
  structure: 'Price structure',
  volume: 'Volume',
  rsi: 'RSI',
  adx: 'Trend strength',
  ema: 'EMA structure',
  relativeStrength: 'Relative strength',
}

/** Header label and alignment, in order. The single definition of the table's shape. */
const INDEX500_COLUMNS = [
  ['#', 'left'], ['Stock', 'left'], ['Sector', 'left'], ['Price', 'right'],
  ['1D', 'right'], ['1M', 'right'], ['3M', 'right'], ['6M', 'right'], ['1Y', 'right'],
  ['6M rank', 'right'], ['Pattern', 'left'], ['RSI', 'right'], ['ADX', 'right'],
  ['Vol', 'right'], ['EMA', 'left'], ['Score', 'left'], ['Status', 'left'],
]

const INDEX500_EMPTY_FILTERS = {
  sector: 'ALL', pattern: 'ALL', minDrop: '', minRsi: '', maxRsi: '', minVolumeRatio: '', sortBy: 'score',
}

/**
 * A percentage that reads green above zero and red below it, and an em dash when it is absent.
 *
 * <p>Coerces before testing, because Jackson serialises a Double.NaN as the JSON *string* "NaN" —
 * which is truthy and slips straight past `Number.isNaN`. A stock younger than a year, or a sector
 * with nothing analysed in it, arrives that way, and without this it renders as "+NaN%".
 */
function pctCell(v, digits = 1) {
  const n = Number(v)
  if (v == null || !Number.isFinite(n)) return { text: '—', color: 'var(--text-muted)' }
  return {
    text: `${n >= 0 ? '+' : ''}${n.toFixed(digits)}%`,
    color: n >= 0 ? 'var(--status-good)' : 'var(--status-critical)',
  }
}

/** The finite value, or null — the same "NaN"-as-a-string guard as {@link pctCell}. */
function finite(v) {
  const n = Number(v)
  return v == null || !Number.isFinite(n) ? null : n
}

function Index500StatusBadge({ status }) {
  const meta = INDEX500_STATUS_META[status] ?? { color: 'var(--text-muted)', label: status }
  return (
    <span className="inline-flex items-start gap-1.5 text-xs font-semibold leading-tight" style={{ color: meta.color }}>
      <span className="mt-1 shrink-0"><Dot color={meta.color} size={7} /></span>
      {meta.label}
    </span>
  )
}

/**
 * Sector-level performance — the "where to look" step that comes before picking a stock.
 *
 * <p>Weakest six months first, because that is the order this feature is built around: the sectors
 * at the top of this list are the ones whose decliners are worth examining. Clicking one filters
 * the table below to it, so the heatmap is the sector filter rather than a chart beside it.
 */
function SectorHeatmap({ sectors, selected, onSelect }) {
  const [showAll, setShowAll] = useState(false)
  if (!sectors || sectors.length === 0) return null
  const peak = Math.max(...sectors.map((s) => Math.abs(finite(s.avgReturn6mPct) ?? 0)), 1)
  // The chosen sector is always in the list, even when it is not one of the worst eight —
  // otherwise selecting it from the dropdown leaves the heatmap showing no selection at all.
  const top = sectors.slice(0, 8)
  const shown = showAll || top.some((s) => s.sector === selected)
    ? (showAll ? sectors : top)
    : [...top, ...sectors.filter((s) => s.sector === selected)]
  return (
    <div className="mb-5 overflow-hidden rounded-xl border" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b px-4 py-3" style={{ borderColor: 'var(--gridline)' }}>
        <div>
          <span className="text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>Sector performance</span>
          <p className="mt-0.5 text-xs" style={{ color: 'var(--text-muted)' }}>
            Average six-month return, weakest first — click a sector to filter the table to it
          </p>
        </div>
        {selected !== 'ALL' && (
          <button onClick={() => onSelect('ALL')} className="text-xs font-semibold" style={{ color: 'var(--accent)' }}>
            Clear sector
          </button>
        )}
      </div>
      <div>
        {shown.map((s) => {
          const avg = finite(s.avgReturn6mPct)
          const negative = avg != null && avg < 0
          const width = avg == null ? 0 : Math.max(2, (Math.abs(avg) / peak) * 100)
          const active = selected === s.sector
          return (
            <button
              key={s.sector}
              onClick={() => onSelect(active ? 'ALL' : s.sector)}
              className="flex w-full items-center gap-3 px-4 py-2 text-left transition-colors"
              style={{ background: active ? 'var(--accent-wash)' : 'transparent', borderTop: '1px solid var(--gridline)' }}
              onMouseEnter={(e) => { if (!active) e.currentTarget.style.background = 'var(--page-plane)' }}
              onMouseLeave={(e) => { if (!active) e.currentTarget.style.background = 'transparent' }}
            >
              <span className="w-40 shrink-0 truncate text-xs font-medium"
                style={{ color: active ? 'var(--accent)' : 'var(--text-primary)' }}>
                {s.sector}
              </span>
              <span className="tabular w-16 shrink-0 text-right text-xs font-semibold"
                style={{ color: avg == null ? 'var(--text-muted)' : negative ? 'var(--status-critical)' : 'var(--status-good)' }}>
                {pctCell(s.avgReturn6mPct).text}
              </span>
              <span className="h-2 flex-1 overflow-hidden rounded-full" style={{ background: 'var(--gridline)' }}>
                <span className="block h-full rounded-full"
                  style={{ width: `${width}%`, background: negative ? 'var(--status-critical)' : 'var(--status-good)' }} />
              </span>
              <span className="hidden w-28 shrink-0 text-right text-[11px] sm:block" style={{ color: 'var(--text-muted)' }}>
                {avg == null ? `${s.stocks} without data` : `${s.declining} down · ${s.advancing} up`}
              </span>
              <span className="tabular hidden w-16 shrink-0 text-right text-[11px] md:block" style={{ color: 'var(--text-muted)' }}>
                {s.patternCount} pattern{s.patternCount === 1 ? '' : 's'}
              </span>
            </button>
          )
        })}
      </div>
      {sectors.length > 8 && (
        <button onClick={() => setShowAll(!showAll)}
          className="w-full px-4 py-2 text-xs font-semibold"
          style={{ borderTop: '1px solid var(--gridline)', color: 'var(--accent)' }}>
          {showAll ? 'Show fewer' : `Show all ${sectors.length} sectors`}
        </button>
      )}
    </div>
  )
}

/** The expanded row: why this stock scored what it did, and every pattern found on its chart. */
function Index500Detail({ row }) {
  const c = row.scoreBreakdown?.components ?? {}
  return (
    <div className="border-t px-4 py-4" style={{ borderColor: 'var(--gridline)', background: 'var(--page-plane)' }}>
      <p className="mb-4 max-w-4xl text-sm leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
        {row.statusReason}
      </p>
      <div className="grid gap-4 md:grid-cols-3">
        <div className="rounded-xl border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <h4 className="mb-3 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Opportunity score — {num(row.score, 1)} / {row.scoreBreakdown?.maxScore ?? 100}
          </h4>
          <div className="flex flex-col gap-2.5">
            {Object.entries(INDEX500_SCORE_LABELS).map(([key, label]) => (
              c[key] ? <ScoreBar key={key} label={label} points={c[key].points} maxPoints={c[key].maxPoints} /> : null
            ))}
          </div>
        </div>

        <div className="rounded-xl border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <h4 className="mb-2 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Performance and levels
          </h4>
          <DetailRow label="Today" value={pctCell(row.return1dPct).text} color={pctCell(row.return1dPct).color} />
          <DetailRow label="1M" value={pctCell(row.return1mPct).text} color={pctCell(row.return1mPct).color} />
          <DetailRow label="3M" value={pctCell(row.return3mPct).text} color={pctCell(row.return3mPct).color} />
          <DetailRow label="6M" value={pctCell(row.return6mPct).text} color={pctCell(row.return6mPct).color} />
          <DetailRow label="1Y" value={pctCell(row.return1yPct).text} color={pctCell(row.return1yPct).color} />
          <DetailRow label="Below 52W high" value={pctCell(row.fromHigh52wPct).text} color="var(--status-critical)" />
          <DetailRow label="Above 52W low" value={pctCell(row.fromLow52wPct).text} color="var(--status-good)" />
          <DetailRow label="EMA 20 / 50 / 200"
            value={`${num(row.ema20, 0)} / ${num(row.ema50, 0)} / ${num(row.ema200, 0)}`} />
          {row.support != null && <DetailRow label="Support" value={fmtPrice(row.support)} />}
          {row.resistance != null && <DetailRow label="Resistance" value={fmtPrice(row.resistance)} />}
          <DetailRow label="Volume vs 20D avg" value={`${num(row.volumeRatio, 2)}×`} />
        </div>

        <div className="rounded-xl border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
          <h4 className="mb-2 text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>
            Patterns detected ({row.patternCount ?? 0})
          </h4>
          {(row.patterns ?? []).length === 0 ? (
            <p className="text-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>
              None of the detectors passed its structural tests on this chart. No pattern has been
              forced onto it to fill the column.
            </p>
          ) : (
            <div className="flex flex-col gap-3">
              {(row.patterns ?? []).map((p) => (
                <div key={p.patternType}>
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{p.patternName}</span>
                    <span className="tabular shrink-0 text-[11px]" style={{ color: 'var(--text-muted)' }}>
                      {num(p.confidence, 1)}/10 · {p.confirmation}
                    </span>
                  </div>
                  <p className="mt-0.5 text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>
                    {p.explanation}
                  </p>
                  {p.riskReward != null && (
                    <p className="tabular mt-0.5 text-[11px]" style={{ color: 'var(--text-muted)' }}>
                      breakout {fmtPrice(p.breakoutLevel)} · stop {fmtPrice(p.stopLoss)} · target {fmtPrice(p.target)} · R:R {num(p.riskReward, 1)}:1
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
      <p className="mt-4 text-xs leading-relaxed" style={{ color: 'var(--text-muted)' }}>
        A large decline is a reason to look, not a reason to buy. These are the measurements behind
        the status — not a prediction that any of them will produce a return.
      </p>
    </div>
  )
}

/**
 * Index 500 sector-wise analysis.
 *
 * <p>The tab is built around one workflow, in order: choose a sector, rank what has fallen inside
 * it over six months, then narrow to the names showing a technically confirmed reversal or
 * breakout pattern.
 *
 * <p>Changing a filter re-queries {@code /api/index500/analysis} rather than filtering in the
 * browser. That endpoint runs over the scan already held in server memory and never re-fetches a
 * bar, so the round trip costs nothing — and it keeps one definition of every threshold, which a
 * second client-side implementation of the same predicates would quietly let drift.
 */
function Index500View({ summary, scanning, progress, scanError, loadError, onScan, queued }) {
  const [filters, setFilters] = useState(INDEX500_EMPTY_FILTERS)
  const [result, setResult] = useState(null)
  const [queryError, setQueryError] = useState(null)
  const [querying, setQuerying] = useState(false)
  const [sectorSummary, setSectorSummary] = useState(null)
  const [patterns, setPatterns] = useState({})
  const [expanded, setExpanded] = useState(null)

  const hasRun = !!summary
  // Every derived query keys off this, so a re-analysis refreshes the table and the heatmap
  // without the tab needing to know a scan happened.
  const generatedAt = summary?.generatedAt ?? null

  useEffect(() => {
    fetch('/api/index500/patterns')
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => setPatterns(d?.patterns ?? {}))
      .catch(() => {})
  }, [])

  useEffect(() => {
    if (!hasRun) return undefined
    let cancelled = false
    fetch('/api/index500/sector-summary')
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => { if (!cancelled) setSectorSummary(d?.sectors ?? null) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [hasRun, generatedAt])

  // Debounced, so typing "15" into a threshold is one query rather than two.
  useEffect(() => {
    if (!hasRun) return undefined
    let cancelled = false
    const timer = setTimeout(() => {
      const q = new URLSearchParams()
      if (filters.sector !== 'ALL') q.set('sector', filters.sector)
      if (filters.pattern !== 'ALL') q.set('pattern', filters.pattern)
      if (filters.minDrop !== '') q.set('minDrop', filters.minDrop)
      if (filters.minRsi !== '') q.set('minRsi', filters.minRsi)
      if (filters.maxRsi !== '') q.set('maxRsi', filters.maxRsi)
      if (filters.minVolumeRatio !== '') q.set('minVolumeRatio', filters.minVolumeRatio)
      q.set('sortBy', filters.sortBy)
      setQuerying(true)
      fetch(`/api/index500/analysis?${q}`)
        .then(async (r) => {
          const body = await r.json().catch(() => ({}))
          if (!r.ok) throw new Error(body.error ?? `HTTP ${r.status}`)
          return body
        })
        .then((d) => { if (!cancelled) { setResult(d); setQueryError(null) } })
        .catch((e) => { if (!cancelled) setQueryError(e.message) })
        .finally(() => { if (!cancelled) setQuerying(false) })
    }, 200)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [hasRun, generatedAt, filters])

  const set = (patch) => setFilters({ ...filters, ...patch })
  const rows = result?.stocks ?? []

  if (!hasRun) {
    return (
      <ScanPending
        scanning={scanning}
        progress={progress}
        error={scanError ?? loadError}
        onScan={onScan}
        what="Index 500 Analysis"
        queued={queued}
        queuedBehind="another scan"
        description="Ranks every sector by six-month performance, then finds which fallen stocks inside them are showing a confirmed reversal or breakout pattern."
      />
    )
  }

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>
          {result ? `${result.matched} of ${result.universeSize} stocks match` : 'Loading…'}
          {result?.unavailableCount > 0 ? ` · ${result.unavailableCount} without usable data` : ''}
          {generatedAt ? ` · analysed ${new Date(generatedAt).toLocaleString()}` : ''}
        </p>
        <div className="flex flex-col items-end gap-1">
          <button onClick={onScan} disabled={scanning}
            className="flex items-center gap-2 rounded-lg border px-3.5 py-1.5 text-sm font-semibold transition-opacity hover:opacity-80 disabled:opacity-60"
            style={{ borderColor: 'var(--btn-scan-border)', background: 'var(--btn-scan-bg)', color: 'var(--text-primary)' }}>
            <span className={scanning ? 'inline-block animate-spin' : 'inline-block'}>&#8635;</span>
            {scanning ? 'Analysing…' : 'Re-analyse'}
          </button>
          {scanning && progress && <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>{progress}</span>}
          {scanError && <span className="max-w-xs text-right text-xs" style={{ color: 'var(--status-serious)' }}>{scanError}</span>}
        </div>
      </div>

      <SectorHeatmap sectors={sectorSummary} selected={filters.sector} onSelect={(s) => set({ sector: s })} />

      {result && (
        <div className="mb-5 flex flex-wrap gap-3">
          <StatTile label="Matching" value={result.matched} />
          <StatTile label="Down over 6M" value={result.declinerCount} color="var(--status-critical)" />
          <StatTile label="Showing a pattern" value={result.patternCount} color="var(--accent)" />
          <StatTile label="Reversals" value={result.reversalCount} color="var(--status-good)" />
          <StatTile label="Breakouts" value={result.breakoutCount} color="var(--status-good)" />
        </div>
      )}

      <div className="mb-4 flex flex-wrap items-end gap-x-4 gap-y-3 rounded-xl border p-3"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-1)' }}>
        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Sector</span>
          <select value={filters.sector} onChange={(e) => set({ sector: e.target.value })}
            className="max-w-[13rem] rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle}>
            <option value="ALL">All sectors</option>
            {(sectorSummary ?? []).map((s) => (
              <option key={s.sector} value={s.sector}>{s.sector} ({s.stocks})</option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Pattern</span>
          <select value={filters.pattern} onChange={(e) => set({ pattern: e.target.value })}
            className="max-w-[13rem] rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle}>
            <option value="ALL">All patterns</option>
            {Object.entries(patterns).map(([type, label]) => (
              <option key={type} value={type}>{label}</option>
            ))}
          </select>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Min drop %</span>
          <input type="number" value={filters.minDrop} onChange={(e) => set({ minDrop: e.target.value })}
            placeholder="any" className="tabular w-20 rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle} />
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>RSI range</span>
          <span className="flex items-center gap-1">
            <input type="number" value={filters.minRsi} onChange={(e) => set({ minRsi: e.target.value })}
              placeholder="min" className="tabular w-16 rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle} />
            <span className="text-xs" style={{ color: 'var(--text-muted)' }}>–</span>
            <input type="number" value={filters.maxRsi} onChange={(e) => set({ maxRsi: e.target.value })}
              placeholder="max" className="tabular w-16 rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle} />
          </span>
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Min volume ×</span>
          <input type="number" step="0.1" value={filters.minVolumeRatio}
            onChange={(e) => set({ minVolumeRatio: e.target.value })}
            placeholder="any" className="tabular w-20 rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle} />
        </label>

        <label className="flex flex-col gap-1">
          <span className="text-[10px] font-medium uppercase tracking-wide" style={{ color: 'var(--text-muted)' }}>Rank by</span>
          <select value={filters.sortBy} onChange={(e) => set({ sortBy: e.target.value })}
            className="rounded-lg border px-2 py-1.5 text-xs outline-none" style={inputStyle}>
            {INDEX500_SORTS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
          </select>
        </label>

        <button onClick={() => setFilters(INDEX500_EMPTY_FILTERS)}
          className="ml-auto rounded-lg border px-3 py-1.5 text-xs font-semibold"
          style={{ borderColor: 'var(--border)', color: 'var(--text-secondary)', background: 'transparent' }}>
          Reset filters
        </button>
      </div>

      {queryError && <p className="mb-3 text-sm" style={{ color: 'var(--status-serious)' }}>{queryError}</p>}

      <div className="overflow-hidden rounded-xl border"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-1)', opacity: querying ? 0.6 : 1 }}>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[66rem] text-sm">
            <thead>
              <tr style={{ borderBottom: '1px solid var(--gridline)' }}>
                {INDEX500_COLUMNS.map(([h, align]) => (
                  <th key={h}
                    className={`px-2 py-3 text-xs font-semibold uppercase tracking-wide text-${align}`}
                    style={{ color: 'var(--text-muted)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const d1 = pctCell(row.return1dPct)
                const m1 = pctCell(row.return1mPct)
                const m3 = pctCell(row.return3mPct)
                const m6 = pctCell(row.return6mPct)
                const y1 = pctCell(row.return1yPct)
                return (
                  <Fragment key={row.symbol}>
                    <tr onClick={() => setExpanded(expanded === row.symbol ? null : row.symbol)}
                      className="cursor-pointer transition-colors"
                      style={{ borderTop: '1px solid var(--gridline)' }}
                      onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--page-plane)')}
                      onMouseLeave={(e) => (e.currentTarget.style.background = 'transparent')}>
                      <td className="tabular px-2 py-2.5 text-xs" style={{ color: 'var(--text-muted)' }}>{row.rank}</td>
                      {/* The widest discretionary column, so it absorbs the space the extra return
                          column needs. The symbol below identifies the row when the name truncates,
                          and the title attribute gives the full name on hover. */}
                      <td className="px-2 py-2.5" title={row.companyName}>
                        <div className="max-w-[6.5rem] truncate font-medium" style={{ color: 'var(--text-primary)' }}>
                          {row.companyName}
                        </div>
                        <div className="text-[10px]" style={{ color: 'var(--text-muted)' }}>{row.symbol}</div>
                      </td>
                      <td className="max-w-[5.5rem] truncate px-2 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                        {row.sector}
                      </td>
                      <td className="tabular px-2 py-2.5 text-right" style={{ color: 'var(--text-primary)' }}>
                        {row.analysed ? fmtPrice(row.price) : '—'}
                      </td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: d1.color }}>{d1.text}</td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: m1.color }}>{m1.text}</td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: m3.color }}>{m3.text}</td>
                      <td className="tabular px-2 py-2.5 text-right text-xs font-semibold" style={{ color: m6.color }}>{m6.text}</td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: y1.color }}>{y1.text}</td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: 'var(--text-muted)' }}>
                        {row.declineRank ?? '—'}
                      </td>
                      <td className="max-w-[6.5rem] px-2 py-2.5 text-xs" style={{ color: 'var(--text-secondary)' }}>
                        <span className="block truncate">{row.patternName ?? '—'}</span>
                        {row.patternCount > 1 && (
                          <span className="text-[10px]" style={{ color: 'var(--text-muted)' }}>
                            +{row.patternCount - 1} more
                          </span>
                        )}
                      </td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                        {row.analysed ? num(row.rsi, 0) : '—'}
                      </td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                        {row.analysed ? num(row.adx, 0) : '—'}
                      </td>
                      <td className="tabular px-2 py-2.5 text-right text-xs" style={{ color: 'var(--text-secondary)' }}>
                        {row.analysed ? `${num(row.volumeRatio, 1)}×` : '—'}
                      </td>
                      <td className="px-2 py-2.5 text-[11px] leading-tight" style={{ color: 'var(--text-muted)' }}>{row.emaStatus ?? '—'}</td>
                      <td className="px-2 py-2.5">
                        {row.analysed && (
                          <span className="tabular rounded px-1.5 py-0.5 text-xs font-semibold"
                            style={{ background: 'var(--accent-wash)', color: 'var(--accent)' }}>
                            {num(row.score, 0)}
                          </span>
                        )}
                      </td>
                      <td className="px-2 py-2.5"><Index500StatusBadge status={row.status} /></td>
                    </tr>
                    {expanded === row.symbol && row.analysed && (
                      <tr><td colSpan={INDEX500_COLUMNS.length} className="p-0"><Index500Detail row={row} /></td></tr>
                    )}
                  </Fragment>
                )
              })}
              {rows.length === 0 && !querying && (
                <tr>
                  <td colSpan={INDEX500_COLUMNS.length} className="px-4 py-10 text-center text-sm" style={{ color: 'var(--text-muted)' }}>
                    Nothing matches these filters. Try a smaller minimum drop, a wider RSI range, or
                    clearing the pattern.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
      {result && result.returned < result.matched && (
        <p className="mt-3 text-xs" style={{ color: 'var(--text-muted)' }}>
          Showing the top {result.returned} of {result.matched} matches.
        </p>
      )}
    </>
  )
}

const VIEW_TITLES = {
  dashboard: 'Dashboard',
  bullish: 'Bullish Stocks',
  index500: 'Index 500 Analysis',
  reversal: 'Reversal Watch',
  lookup: 'My Watchlist',
  journal: 'Trade Journal',
  expenses: 'Expenses',
}

const VIEW_SUBTITLES = {
  dashboard: 'Market regime, and where each scanner stands. Opening a tab starts its scan.',
  reversal: 'Candlestick-confirmed reversal setups in beaten-down Nifty 500 names.',
  lookup: 'Symbols you follow, scored by the same 100-point bullish assessment as the ranked table.',
  index500: 'Sector by sector, then stock by stock: which sectors have fallen hardest over six months, and which names inside them are showing a confirmed reversal or breakout.',
  bullish: 'Nifty 500 ranked on trend, relative strength, momentum, volume, price structure, pattern quality, breakout quality and risk/reward.',
  journal: 'Your delivery/swing trade log, auto-calculated performance dashboard, and 1:2 R:R calculator.',
  expenses: 'Salary, EMIs and fixed costs month by month — and what that adds up to over a year.',
}

export default function App() {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [view, setView] = useState('dashboard')
  const [expanded, setExpanded] = useState(null)
  const [watchlistChartRow, setWatchlistChartRow] = useState(null)

  const [customRows, setCustomRows] = useState([])
  const [customInput, setCustomInput] = useState('')
  const [customLoading, setCustomLoading] = useState(false)
  const [customError, setCustomError] = useState(null)

  const [refreshing, setRefreshing] = useState(false)
  const [refreshProgress, setRefreshProgress] = useState(null)
  const [refreshError, setRefreshError] = useState(null)
  const pollRef = useRef(null)

  // The bullish ranking lives here rather than inside its tab: the dashboard reports on it, and
  // opening the tab is what starts it, so the state has to outlive the tab being mounted.
  const [bullishData, setBullishData] = useState(null)
  const [bullishError, setBullishError] = useState(null)
  const [bullishScanning, setBullishScanning] = useState(false)
  const [bullishProgress, setBullishProgress] = useState(null)
  const [bullishScanError, setBullishScanError] = useState(null)
  const bullishPollRef = useRef(null)

  // The Index 500 analysis keeps only a summary here — one row plus the aggregate counts. The tab
  // queries the full table itself with whatever filters are set, and the dashboard needs nothing
  // more than the counts, so holding 500 rows in the shell would be carrying them for no reader.
  const [index500Summary, setIndex500Summary] = useState(null)
  const [index500Error, setIndex500Error] = useState(null)
  const [index500Scanning, setIndex500Scanning] = useState(false)
  const [index500Progress, setIndex500Progress] = useState(null)
  const [index500ScanError, setIndex500ScanError] = useState(null)
  const index500PollRef = useRef(null)

  // One auto-start per feature per session. Without this a scan that fails would be retried on
  // every re-render that lands on its tab, which is a request loop rather than a retry.
  const autoStarted = useRef({ reversal: false, bullish: false, index500: false })

  function loadResults() {
    return fetch('/api/results')
      .then((r) => {
        // 404 is the ordinary cold-start case, not a failure: the scan simply has not run.
        if (r.status === 404) throw new Error('no-results-yet')
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json()
      })
      .then((d) => {
        setData(d)
        setError(null)
      })
  }

  /** A real load failure, as opposed to "no scan has run yet". */
  const loadFailure = error && error !== 'no-results-yet' ? error : null

  useEffect(() => {
    loadResults().catch((e) => setError(e.message))
    return () => clearInterval(pollRef.current)
  }, [])

  function loadBullish() {
    return fetch('/api/bullish-stocks')
      .then((r) => {
        if (r.status === 404) throw new Error('no-ranking-yet')
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json()
      })
      .then((d) => { setBullishData(d); setBullishError(null) })
  }

  useEffect(() => {
    loadBullish().catch((e) => setBullishError(e.message))
    return () => clearInterval(bullishPollRef.current)
  }, [])

  async function runBullishScan() {
    if (bullishScanning) return
    setBullishScanError(null)
    setBullishScanning(true)
    setBullishProgress('Starting ranking…')
    try {
      const res = await fetch('/api/bullish-stocks/scan', { method: 'POST' })
      if (res.status !== 202 && res.status !== 409) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.error ?? `HTTP ${res.status}`)
      }
      bullishPollRef.current = setInterval(async () => {
        try {
          const st = await fetch('/api/bullish-stocks/status').then((r) => r.json())
          if (st.running) {
            setBullishProgress(st.progress ?? 'Ranking…')
            return
          }
          clearInterval(bullishPollRef.current)
          if (st.lastResult?.error) setBullishScanError(st.lastResult.error)
          else await loadBullish().catch((e) => setBullishError(e.message))
          setBullishScanning(false)
          setBullishProgress(null)
        } catch (e) {
          clearInterval(bullishPollRef.current)
          setBullishScanning(false)
          setBullishProgress(null)
          setBullishScanError(e.message || 'Lost connection to the API server')
        }
      }, 1500)
    } catch (e) {
      setBullishScanning(false)
      setBullishProgress(null)
      setBullishScanError(e.message || 'Could not reach the API server')
    }
  }

  /**
   * The aggregate view of the last analysis.
   *
   * <p>{@code limit=1} deliberately: this call exists to answer "has it run, and what did it find",
   * which the summary counts already carry. Pulling the whole table here would fetch 250 rows the
   * shell never renders, and the tab re-queries with its own filters anyway.
   */
  function loadIndex500() {
    return fetch('/api/index500/analysis?limit=1&includeUnavailable=true')
      .then((r) => {
        if (r.status === 404) throw new Error('no-analysis-yet')
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json()
      })
      .then((d) => { setIndex500Summary(d); setIndex500Error(null) })
  }

  useEffect(() => {
    loadIndex500().catch((e) => setIndex500Error(e.message))
    return () => clearInterval(index500PollRef.current)
  }, [])

  async function runIndex500Scan() {
    if (index500Scanning) return
    setIndex500ScanError(null)
    setIndex500Scanning(true)
    setIndex500Progress('Starting analysis…')
    try {
      const res = await fetch('/api/index500/scan', { method: 'POST' })
      if (res.status !== 202 && res.status !== 409) {
        const body = await res.json().catch(() => ({}))
        throw new Error(body.error ?? `HTTP ${res.status}`)
      }
      index500PollRef.current = setInterval(async () => {
        try {
          const st = await fetch('/api/index500/status').then((r) => r.json())
          if (st.running) {
            setIndex500Progress(st.progress ?? 'Analysing…')
            return
          }
          clearInterval(index500PollRef.current)
          if (st.lastResult?.error) setIndex500ScanError(st.lastResult.error)
          else await loadIndex500().catch((e) => setIndex500Error(e.message))
          setIndex500Scanning(false)
          setIndex500Progress(null)
        } catch (e) {
          clearInterval(index500PollRef.current)
          setIndex500Scanning(false)
          setIndex500Progress(null)
          setIndex500ScanError(e.message || 'Lost connection to the API server')
        }
      }, 1500)
    } catch (e) {
      setIndex500Scanning(false)
      setIndex500Progress(null)
      setIndex500ScanError(e.message || 'Could not reach the API server')
    }
  }

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
            loadSavedSymbols().then((saved) =>
              saved.forEach((sym) => fetchCustom(sym, { silent: true })))
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
    loadSavedSymbols().then((saved) => saved.forEach((sym) => fetchCustom(sym, { silent: true })))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function fetchCustom(rawSymbol, { silent = false } = {}) {
    const symbol = rawSymbol.trim().toUpperCase()
    if (!symbol) return
    setCustomLoading(!silent)
    setCustomError(null)
    try {
      const res = await fetch(`/api/bullish-stocks/${encodeURIComponent(symbol)}`)
      const body = await res.json()
      if (!res.ok) throw new Error(body.error ?? `HTTP ${res.status}`)
      setCustomRows((prev) => [body, ...prev.filter((r) => r.symbol !== body.symbol)])
      // The server de-duplicates, so an already-followed symbol is a harmless no-op.
      saveSymbol(body.symbol)
    } catch (e) {
      if (!silent) setCustomError(e.message || 'Could not reach the API server')
    } finally {
      setCustomLoading(false)
    }
  }

  function removeCustom(symbol) {
    setCustomRows((prev) => prev.filter((r) => r.symbol !== symbol))
    forgetSymbol(symbol)
  }

  // Opening a tab is what starts its scan. The dashboard deliberately starts nothing: it is the
  // landing view, and a page load should not kick off several minutes of fetching on its own.
  // Reversal Watch owns the scan behind /api/results, so opening it is what starts that scan.
  useEffect(() => {
    // Never auto-start one scan while the other is running. Both walk the same ~500 symbols, so
    // running them at once doubles the load on Yahoo's endpoint for no gain — whereas waiting
    // costs nothing, because the first scan fills the shared bar cache and the second then
    // completes in seconds. These flags are effect dependencies, so the queued scan starts on its
    // own the moment the running one finishes.
    const busy = refreshing || bullishScanning || index500Scanning

    if (view === 'reversal' && !data && !busy && !autoStarted.current.reversal) {
      autoStarted.current.reversal = true
      refreshAll()
    }
    if (view === 'bullish' && !bullishData && !busy && !autoStarted.current.bullish) {
      autoStarted.current.bullish = true
      runBullishScan()
    }
    if (view === 'index500' && !index500Summary && !busy && !autoStarted.current.index500) {
      autoStarted.current.index500 = true
      runIndex500Scan()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view, data, bullishData, index500Summary, refreshing, bullishScanning, index500Scanning])

  // No full-screen gate any more. Every view renders inside the same shell and handles its own
  // empty state, so the tab row, the header and the dashboard stay reachable at all times — the
  // old "No scan data yet" wall hid the entire app behind a scan that had not been asked for.

  return (
    <div className="min-h-screen" style={{ background: 'var(--page-plane)' }}>
      {/* The brand used to live only on the "no scan data" screen, which no longer exists — so it
          moves into a persistent bar rather than disappearing from the app altogether. */}
      <div className="border-b" style={{ borderColor: 'var(--gridline)', background: 'var(--surface-1)' }}>
        <div className="mx-auto flex max-w-6xl items-center gap-2.5 px-4 py-3 sm:px-6">
          <LogoBadge size={28} />
          <span className="text-sm font-semibold tracking-tight" style={{ color: 'var(--text-primary)' }}>
            Breakout Scanner
          </span>
          <span className="ml-auto text-xs" style={{ color: 'var(--text-muted)' }}>Nifty 500 · live NSE data</span>
        </div>
      </div>
      <div className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
        <header className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight" style={{ color: 'var(--text-primary)' }}>
              {VIEW_TITLES[view]}
            </h1>
            <p className="mt-1 text-sm" style={{ color: 'var(--text-secondary)' }}>
              {VIEW_SUBTITLES[view]}
            </p>
            <div className="mt-3">
              <ViewTabs view={view} setView={setView} reversalCount={data?.reversals?.length ?? 0}
                watchlistCount={customRows.length} />
            </div>
          </div>
          {/* Only Reversal Watch is fed by this scan, so only it gets the button. The Bullish tab carries
              its own Re-rank, and offering both would be two buttons that do different things.
              Conditional rendering rather than the `hidden` attribute, which Tailwind's `flex`
              utility would override. */}
          {view === 'reversal' && (
          <div className="flex flex-col items-end gap-1.5">
            <button
              onClick={refreshAll}
              disabled={refreshing}
              className="flex items-center gap-2 rounded-lg border px-3.5 py-1.5 text-sm font-semibold transition-opacity hover:opacity-80 disabled:opacity-60"
              style={{ borderColor: 'var(--btn-scan-border)', background: 'var(--btn-scan-bg)', color: 'var(--text-primary)' }}
            >
              <span className={refreshing ? 'inline-block animate-spin' : 'inline-block'}>&#8635;</span>
              {refreshing ? 'Scanning…' : 'Rescan'}
            </button>
            {refreshing && refreshProgress && (
              <span className="tabular text-xs" style={{ color: 'var(--text-muted)' }}>{refreshProgress}</span>
            )}
            {refreshError && (
              <span className="max-w-xs text-right text-xs" style={{ color: 'var(--status-serious)' }}>{refreshError}</span>
            )}
          </div>
          )}
        </header>

        {view === 'reversal' && data && (
          <div className="mb-6 flex flex-wrap gap-3">
            <StatTile label="Confirmed signals" value={data.reversals?.length ?? 0} color="var(--status-good)" />
          </div>
        )}
        {view === 'lookup' && (
          <div className="mb-6 flex flex-wrap gap-3">
            <StatTile label="Followed" value={customRows.length} />
            <StatTile label="Buy now" color="var(--status-good)"
              value={customRows.filter((r) => r.tradeStatus === 'BUY NOW').length} />
            <StatTile label="Bullish" color="var(--accent)"
              value={customRows.filter((r) => r.score >= 65).length} />
          </div>
        )}

        {view === 'lookup' && (
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

        {view === 'dashboard' && (
          <DashboardView
            reversal={{ data, scanning: refreshing, progress: refreshProgress }}
            bullish={{ data: bullishData, scanning: bullishScanning, progress: bullishProgress }}
            index500={{ data: index500Summary, scanning: index500Scanning, progress: index500Progress }}
            watchlistCount={customRows.length}
            onOpen={setView}
          />
        )}

        {view === 'index500' && (
          <Index500View
            summary={index500Summary}
            scanning={index500Scanning}
            progress={index500Progress}
            scanError={index500ScanError}
            loadError={index500Error && index500Error !== 'no-analysis-yet' ? index500Error : null}
            onScan={runIndex500Scan}
            queued={refreshing || bullishScanning}
          />
        )}

        {view === 'journal' && <TradeJournalView />}
        {view === 'expenses' && <ExpensesView />}
        {view === 'bullish' && (
          <BullishStocksView
            data={bullishData}
            scanning={bullishScanning}
            progress={bullishProgress}
            scanError={bullishScanError}
            loadError={bullishError}
            onScan={runBullishScan}
            queued={refreshing}
          />
        )}
        {view === 'reversal' && (data
          ? <ReversalTable rows={data.reversals ?? []} />
          : <ScanPending scanning={refreshing} progress={refreshProgress} error={refreshError ?? loadFailure}
              onScan={refreshAll} what="Reversal Watch" queued={bullishScanning} queuedBehind="Bullish ranking"
              description="Scans the Nifty 500 for candlestick-confirmed reversal setups in beaten-down names." />)}
        {view === 'lookup' && (
          <WatchlistTable rows={customRows} expanded={expanded} setExpanded={setExpanded}
            onRemove={removeCustom} onOpenChart={setWatchlistChartRow} />
        )}
        {watchlistChartRow && (
          <FullChartModal
            row={{
              symbol: watchlistChartRow.symbol,
              name: watchlistChartRow.name,
              values: {
                'Prev resistance': watchlistChartRow.breakout.level,
                'Breakout Confirm Level': watchlistChartRow.breakout.confirmedLevel,
              },
            }}
            onClose={() => setWatchlistChartRow(null)}
          />
        )}

      </div>
    </div>
  )
}
