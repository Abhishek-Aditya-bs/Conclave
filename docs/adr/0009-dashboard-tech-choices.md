# ADR-009 — Dashboard + marketing website tech choices (M10)

* **Status.** Accepted (Session 10, 2026-05-26).
* **Module.** M10 — Audit Dashboard + Marketing website.
* **Spec hooks.** §5 "Dashboard" row, §6 M10, §8 "Website plan".

## Context

Spec §5 locks the dashboard stack: **Vite + React 19 + Tailwind + shadcn/ui
+ Framer Motion**, JetBrains Mono + Geist Sans via Fontsource, deployed to
Cloudflare Pages. Spec §6 M10 wants: incoming events stream, decisions
feed, decision-detail view with evidence + verdict + judge reasoning, and
a score histogram. The marketing website (spec §8) uses the same stack
for consistency.

The M7 audit API (ADR-006) is the contract: snake_case JSON, three
endpoints under `/api/v1/decisions`, stable error codes. No OpenAPI
generation in Spring Boot 4 yet, so the dashboard hand-writes TypeScript
types that mirror `DecisionSummary` / `DecisionDetail` /
`ContributingFactorRecord`.

## Decision

### Tailwind v4 with `@theme` directive, not v3

Tailwind v4 ships CSS-first config — `@theme { --color-bg: #09090b }` in
`src/index.css` replaces `tailwind.config.ts`. The `@tailwindcss/vite`
plugin handles compilation. No `postcss.config.js`, no
`autoprefixer` dep. Smaller surface, fewer config files to drift.

### shadcn primitives inlined, not via the `shadcn` CLI

The shadcn CLI scaffolds a `components/ui/` tree with Radix-backed
primitives. For M10 we need exactly Button, Card, and form Field —
inlining them as plain Tailwind components avoids:

- Pulling Radix's ~40KB into the bundle for components we don't use.
- The opinionated shadcn theme layer (CSS variables in `--background`
  shape), which would conflict with the spec's "zinc palette + JetBrains
  Mono for code/tabular data, Geist Sans for UI" requirement that's
  already in `index.css`.

Three files in `components/ui/`. ~80 lines of code total. The look
matches the shadcn `zinc` palette per spec §8.

### TanStack Query for data fetching, not SWR or vanilla `fetch`

The dashboard polls every 5 seconds via the `QueryClient`'s default
`refetchInterval`. TanStack Query gives us: cache invalidation on
mutation success (replay invalidates the list query), per-query
overrides (detail view doesn't poll), and `enabled: !!id` to skip
queries until the route param resolves. All of this would be a
~150-line custom hook with vanilla fetch.

### react-router v7 for two routes

`/` (list) and `/decisions/:decisionId` (detail). Single
`<BrowserRouter>` at the top. No layouts, no nested routes, no data
loaders. Lighter than wiring a router-loaders pattern for two pages.

### recharts for the histogram

Not Chart.js or D3 — recharts is the React-native option, ships TS
types, and produces SVG which respects our CSS custom-properties for
colors. The histogram is 10 buckets, client-side from the current page
of decisions. ~50 lines.

### No Framer Motion

Spec §5 lists Framer Motion but the audit UI is dense and tabular —
animations would compete with the data. Subtle hover state changes via
CSS `transition-colors` cover the polish bar without a 60KB dep. If
the marketing website grows hero animation needs later, we add it then.

### Tiny markdown subset, not `react-markdown`

The judge's verdict explanation uses `**bold**`, bullet lists, and
inline `` `code` ``. `react-markdown` is ~30KB gzipped for these three
features. We hand-roll an `inline()` helper (~60 lines in
[VerdictMarkdown.tsx](../../dashboard/src/components/VerdictMarkdown.tsx))
that handles the subset and never injects raw HTML. If verdict markup
grows (tables, links), swap in `react-markdown`.

### Vite dev proxy for `/api`

Vite proxies `/api/*` to `http://localhost:8080` in dev. The fetch
wrapper uses `import.meta.env.VITE_API_BASE_URL ?? ""` for prod
overrides (Cloudflare Pages → Worker that fronts the orchestrator).
No CORS config required server-side for the dev path.

### Marketing website: same stack, no router

`website/` is a single-page app with anchor-link navigation. Same Vite
+ React + Tailwind v4 config as the dashboard. No fetch, no
client-side routing, no data deps. Bundle: 65KB gzipped JS + 18KB
gzipped CSS — well within Cloudflare Pages' free tier.

## Consequences

- **Bundle size:** dashboard 720KB JS (211KB gzipped) — acceptable for
  an internal audit tool. Recharts is ~150KB of that; route-level code
  splitting would trim it but the dashboard is a single-page tool
  anyway.
- **Type drift risk:** TypeScript types in `dashboard/src/lib/types.ts`
  hand-mirror the M7 Java DTOs. If the orchestrator adds a field, the
  dashboard's `DecisionSummary` / `DecisionDetail` must be updated.
  Cross-referenced in the file header. springdoc-openapi 2.x doesn't
  support Spring Boot 4 yet (ADR-006); when it does, we'll codegen
  these types from the spec.
- **No SSE/WebSocket for live feed.** Polling at 5s is sufficient for
  a demo (the spec's "incoming events stream" is sub-second visible
  enough). Adding SSE would require an `EventSource` route on the
  orchestrator and a parallel subscription pattern — deferred until
  there's a measurable user need.
- **Replay banner.** When the user clicks Replay, the dashboard shows
  the fresh decision in a separate panel labelled "fresh — not
  persisted" so the audit semantics (ADR-006: replay does NOT mutate
  history) are visible in the UI itself.

## Rejected alternatives

- **Next.js.** SSR / RSC adds deploy complexity (Cloudflare Pages
  needs `@cloudflare/next-on-pages` or similar adapter). Vite SPA is
  static files; one rsync away from prod.
- **shadcn CLI for primitives.** Pulls Radix surface we don't need;
  conflicts with our pre-existing theme.
- **D3 for the histogram.** Imperative; doesn't play well with React's
  rendering model.
- **OpenAPI codegen for types.** Blocked by springdoc-openapi 2.x not
  supporting Spring Boot 4. Hand-written types are ~50 lines; codegen
  would be more setup than current ROI.
