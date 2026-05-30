import { useState, type ReactNode } from "react";

/* ─────────────────────────────────────────────────────────────────────────
   CONCLAVE — marketing site.
   Single file by design. Only React is imported; everything else is Tailwind
   theme tokens (from index.css) + hand-authored inline SVG diagrams.
   ──────────────────────────────────────────────────────────────────────── */

const REPO = "https://github.com/Abhishek-Aditya-bs/Conclave";
const LIVE = "https://conclave-website.pages.dev";
const PAPER = "/conclave.pdf";

// Diagram palette — three signal colors + verdict, reused across every SVG so
// the whole site reads as one system. (Baseliner=teal, Graph=violet, Judge=amber.)
const C = {
  teal: "#37d6c2",
  violet: "#8b7bff",
  amber: "#ffb84d",
  ink: "#e7ecf5",
  mute: "#8b97ad",
  line: "#2a3650",
  panel: "#0e1626",
};

export default function App() {
  return (
    <div className="min-h-screen bg-background text-foreground antialiased">
      <GlobalFX />
      <Nav />
      <main>
        <Hero />
        <Problem />
        <HowItWorks />
        <Baseline />
        <Architecture />
        <Domains />
        <Performance />
        <Paper />
        <Quickstart />
      </main>
      <Footer />
    </div>
  );
}

/* ───────────────────────────────── shared ──────────────────────────────── */

function GlobalFX() {
  // Keyframes for the animated flow dashes / pulses used inside the SVGs.
  return (
    <style>{`
      @keyframes cv-dash { to { stroke-dashoffset: -32; } }
      @keyframes cv-pulse { 0%,100% { opacity:.25 } 50% { opacity:.9 } }
      @keyframes cv-float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-4px) } }
      .cv-flow { stroke-dasharray: 6 10; animation: cv-dash 1.1s linear infinite; }
      .cv-flow-slow { stroke-dasharray: 4 12; animation: cv-dash 2.2s linear infinite; }
      .cv-pulse { animation: cv-pulse 2.4s ease-in-out infinite; }
      @media (prefers-reduced-motion: reduce) {
        .cv-flow,.cv-flow-slow,.cv-pulse { animation: none; }
      }
    `}</style>
  );
}

function Eyebrow({ children }: { children: ReactNode }) {
  return (
    <div className="inline-flex items-center gap-2 rounded-full border border-border bg-muted/40 px-3 py-1 text-[11px] uppercase tracking-[0.2em] text-muted-foreground">
      {children}
    </div>
  );
}

function Section({
  id,
  className = "",
  children,
}: {
  id?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section id={id} className={`mx-auto w-full max-w-6xl px-5 py-20 md:py-28 ${className}`}>
      {children}
    </section>
  );
}

function Logo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <rect width="32" height="32" rx="7" fill="#fafafa" />
      <circle cx="16" cy="16" r="9" stroke="#09090b" strokeWidth="1.5" />
      <path d="M11 13 L21 19 M21 13 L11 19" stroke="#09090b" strokeWidth="0.9" opacity="0.45" />
      <circle cx="11" cy="13" r="1.6" fill="#09090b" />
      <circle cx="21" cy="13" r="1.6" fill="#09090b" />
      <circle cx="11" cy="19" r="1.6" fill="#09090b" />
      <circle cx="21" cy="19" r="1.6" fill="#09090b" />
    </svg>
  );
}

function GitHubMark({ className = "" }: { className?: string }) {
  return (
    <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor" className={className} aria-hidden="true">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" />
    </svg>
  );
}

function Arrow() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true" className="inline-block">
      <path d="M5 12h14M13 6l6 6-6 6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/* ────────────────────────────────── nav ────────────────────────────────── */

function Nav() {
  const links = [
    ["Problem", "#problem"],
    ["How it works", "#how"],
    ["Architecture", "#architecture"],
    ["Domains", "#domains"],
    ["Paper", "#paper"],
    ["Start", "#start"],
  ];
  return (
    <header className="sticky top-0 z-50 border-b border-border bg-background/80 backdrop-blur">
      <div className="mx-auto flex h-14 w-full max-w-6xl items-center justify-between px-5">
        <a href="#top" className="flex items-center gap-2.5">
          <Logo size={26} />
          <span className="text-sm font-semibold tracking-[0.18em]">CONCLAVE</span>
        </a>
        <nav className="hidden items-center gap-7 md:flex">
          {links.map(([label, href]) => (
            <a key={href} href={href} className="text-[13px] text-muted-foreground transition-colors hover:text-foreground">
              {label}
            </a>
          ))}
        </nav>
        <div className="flex items-center gap-2">
          <a
            href={REPO}
            className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-[13px] text-muted-foreground transition-colors hover:text-foreground"
          >
            <GitHubMark /> <span className="hidden sm:inline">GitHub</span>
          </a>
          <a
            href="#start"
            className="inline-flex items-center gap-1.5 rounded-lg bg-foreground px-3.5 py-1.5 text-[13px] font-medium text-background transition-opacity hover:opacity-90"
          >
            Run the demo
          </a>
        </div>
      </div>
    </header>
  );
}

/* ────────────────────────────────── hero ───────────────────────────────── */

function Hero() {
  return (
    <div id="top" className="relative overflow-hidden border-b border-border">
      <div className="bg-grid bg-grid-fade pointer-events-none absolute inset-0" />
      <div className="glow pointer-events-none absolute inset-x-0 top-0 h-[420px]" />
      <Section className="relative !py-24 md:!py-28">
        <div className="flex flex-col items-start gap-6">
          <Eyebrow>multi-agent · real-time · explainable</Eyebrow>
          <h1 className="max-w-3xl text-4xl font-semibold leading-[1.05] tracking-tight md:text-6xl">
            Four agents deliberate
            <br />
            on <span className="text-muted-foreground">every event.</span>
          </h1>
          <p className="max-w-2xl text-base leading-relaxed text-muted-foreground md:text-lg">
            CONCLAVE reads each event, weighs <span className="text-foreground">behavioral</span>,{" "}
            <span className="text-foreground">relational</span>, and <span className="text-foreground">contextual</span>{" "}
            signals through a team of specialist agents, and returns a calibrated risk verdict —{" "}
            <span className="text-foreground">with the reasoning attached.</span>
          </p>
          <div className="flex flex-wrap items-center gap-3 pt-1">
            <a
              href="#start"
              className="inline-flex items-center gap-2 rounded-lg bg-foreground px-4 py-2.5 text-sm font-medium text-background transition-opacity hover:opacity-90"
            >
              Run the demo <Arrow />
            </a>
            <a
              href={REPO}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              <GitHubMark /> View source
            </a>
          </div>
          <p className="pt-2 font-mono text-xs text-muted-foreground/80">
            Java 25 · Spring Boot · Kafka Streams · pgvector · Neo4j · LangGraph · Claude / OpenAI / Ollama
          </p>
        </div>

        <div className="mt-14">
          <FlowDiagram />
        </div>
      </Section>
    </div>
  );
}

/* The signature hero diagram: event → feature → (baseliner ∥ graph) → judge → verdict. */
function FlowDiagram() {
  return (
    <div className="rounded-2xl border border-border bg-card/40 p-3 sm:p-6">
      <svg viewBox="0 0 1000 300" className="w-full" role="img" aria-label="Event flows through four agents to a verdict">
        <defs>
          <filter id="soft" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="6" result="b" />
            <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
          </filter>
          <marker id="ah" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
            <path d="M0 0 L8 4 L0 8 z" fill={C.mute} />
          </marker>
        </defs>

        {/* edges */}
        <g fill="none" strokeWidth="2">
          <path d="M150 150 H214" stroke={C.mute} markerEnd="url(#ah)" />
          <path d="M150 150 H214" stroke={C.teal} className="cv-flow" opacity="0.8" />
          {/* feature -> baseliner / graph */}
          <path d="M360 150 C 400 150, 410 86, 452 86" stroke={C.teal} className="cv-flow" />
          <path d="M360 150 C 400 150, 410 214, 452 214" stroke={C.violet} className="cv-flow" />
          {/* baseliner / graph -> judge */}
          <path d="M648 86 C 690 86, 700 150, 740 150" stroke={C.teal} className="cv-flow" />
          <path d="M648 214 C 690 214, 700 150, 740 150" stroke={C.violet} className="cv-flow" />
          {/* judge -> verdict */}
          <path d="M858 150 H922" stroke={C.amber} className="cv-flow" />
          <path d="M858 150 H922" stroke={C.mute} markerEnd="url(#ah)" opacity="0.5" />
        </g>

        {/* event */}
        <Node x={26} y={120} w={124} h={60} title="event" sub="on the stream" color={C.mute} />
        {/* feature */}
        <Node x={216} y={120} w={144} h={60} title="feature" sub="distill" color={C.ink} />
        {/* baseliner */}
        <Node x={452} y={56} w={196} h={60} title="behavioral baseliner" sub="cosine vs profile" color={C.teal} />
        {/* graph */}
        <Node x={452} y={184} w={196} h={60} title="graph reasoner" sub="rings · lateral moves" color={C.violet} />
        {/* judge */}
        <Node x={740} y={120} w={118} h={60} title="judge" sub="LLM deliberates" color={C.amber} glow />
        {/* verdict */}
        <g>
          <rect x={922} y={112} width={64} height={76} rx="12" fill={C.panel} stroke="#3a2d6b" />
          <text x={954} y={138} textAnchor="middle" fontSize="11" fontWeight="700" fill="#7CFFB2">ALLOW</text>
          <text x={954} y={154} textAnchor="middle" fontSize="11" fontWeight="700" fill={C.amber}>REVIEW</text>
          <text x={954} y={170} textAnchor="middle" fontSize="11" fontWeight="700" fill="#ff7a90">BLOCK</text>
        </g>
      </svg>
      <p className="px-2 pb-1 text-center text-xs text-muted-foreground">
        The baseliner and graph reasoner run in parallel; the judge consumes both and returns a score, a label, and a plain-English “why.”
      </p>
    </div>
  );
}

function Node({
  x, y, w, h, title, sub, color, glow = false,
}: {
  x: number; y: number; w: number; h: number; title: string; sub: string; color: string; glow?: boolean;
}) {
  return (
    <g filter={glow ? "url(#soft)" : undefined}>
      <rect x={x} y={y} width={w} height={h} rx="12" fill={C.panel} stroke={color} strokeOpacity="0.55" />
      <rect x={x} y={y} width={3.5} height={h} rx="2" fill={color} />
      <text x={x + w / 2} y={y + h / 2 - 3} textAnchor="middle" fontSize="14" fontWeight="600" fill={C.ink}>
        {title}
      </text>
      <text x={x + w / 2} y={y + h / 2 + 15} textAnchor="middle" fontSize="11" fill={C.mute}>
        {sub}
      </text>
    </g>
  );
}

// A floating label with a dark "halo" behind it so text never sits on top of a
// line/arrow. JetBrains Mono is monospace, so the box width is predictable.
function Chip({
  x, y, text, color = C.ink, size = 11, anchor = "start", weight = "400",
}: {
  x: number; y: number; text: string; color?: string; size?: number;
  anchor?: "start" | "middle" | "end"; weight?: string;
}) {
  const w = text.length * size * 0.62 + 12;
  const rx = anchor === "middle" ? x - w / 2 : anchor === "end" ? x - w + 6 : x - 6;
  return (
    <g>
      <rect x={rx} y={y - size + 2} width={w} height={size + 7} rx="5" fill={C.panel} stroke={C.line} strokeOpacity="0.6" />
      <text x={x} y={y} textAnchor={anchor} fontSize={size} fontWeight={weight} fill={color}>
        {text}
      </text>
    </g>
  );
}

/* ──────────────────────────────── problem ──────────────────────────────── */

function Problem() {
  const lenses = [
    { c: C.teal, t: "Behavioral", d: "Is this normal for THIS entity — or has its pattern suddenly broken?" },
    { c: C.violet, t: "Relational", d: "Is it part of a bad shape — a card-testing ring, lateral movement?" },
    { c: C.amber, t: "Explainable", d: "Can you justify the call to an analyst, right now, with evidence?" },
  ];
  return (
    <Section id="problem">
      <Eyebrow>The problem</Eyebrow>
      <h2 className="mt-5 max-w-2xl text-3xl font-semibold tracking-tight md:text-4xl">
        A single event is only suspicious <span className="text-muted-foreground">in context.</span>
      </h2>
      <p className="mt-4 max-w-2xl text-muted-foreground">
        Fraud and intrusions hide between the lines. Answering “is this safe to allow?” takes three different
        lenses at once — and a score nobody can explain is a score nobody can act on.
      </p>

      <div className="mt-10">
        <ProblemDiagram />
      </div>

      <div className="mt-8 grid gap-4 md:grid-cols-3">
        {lenses.map((l) => (
          <div key={l.t} className="rounded-xl border border-border bg-card/40 p-5">
            <div className="flex items-center gap-2.5">
              <span className="h-2.5 w-2.5 rounded-full" style={{ background: l.c }} />
              <h3 className="text-sm font-semibold">{l.t}</h3>
            </div>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{l.d}</p>
          </div>
        ))}
      </div>
      <p className="mt-6 text-sm text-muted-foreground">
        Most systems nail one lens and bolt on the rest. <span className="text-foreground">CONCLAVE answers all three, per event.</span>
      </p>
    </Section>
  );
}

function ProblemDiagram() {
  return (
    <div className="rounded-2xl border border-border bg-card/40 p-3 sm:p-6">
      <svg viewBox="0 0 1000 230" className="w-full" role="img" aria-label="Three lenses converge on one event">
        <defs>
          <radialGradient id="evg" cx="50%" cy="50%" r="50%">
            <stop offset="0" stopColor="#ffffff" stopOpacity="0.18" />
            <stop offset="1" stopColor="#ffffff" stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* center event */}
        <circle cx="500" cy="115" r="78" fill="url(#evg)" />
        <circle cx="500" cy="115" r="40" fill={C.panel} stroke={C.line} />
        <text x="500" y="111" textAnchor="middle" fontSize="13" fontWeight="700" fill={C.ink}>event</text>
        <text x="500" y="128" textAnchor="middle" fontSize="10" fill={C.mute}>on the wire</text>

        {/* three lenses */}
        {[
          { x: 120, y: 60, c: C.teal, t: "is it NORMAL", t2: "for this entity?", sx: 310, sy: 84, ex: 457, ey: 108 },
          { x: 120, y: 170, c: C.violet, t: "is it part of", t2: "a bad PATTERN?", sx: 310, sy: 194, ex: 459, ey: 132 },
          { x: 760, y: 115, c: C.amber, t: "can you EXPLAIN", t2: "the call — now?", sx: 760, sy: 139, ex: 543, ey: 119 },
        ].map((l, i) => (
          <g key={i}>
            <line x1={l.sx} y1={l.sy} x2={l.ex} y2={l.ey} stroke={l.c} strokeOpacity="0.5" strokeWidth="2" className="cv-flow-slow" />
            <rect x={l.x} y={l.y} width="190" height="48" rx="10" fill={C.panel} stroke={l.c} strokeOpacity="0.5" />
            <rect x={l.x} y={l.y} width="3.5" height="48" rx="2" fill={l.c} />
            <text x={l.x + 14} y={l.y + 21} fontSize="12.5" fontWeight="600" fill={C.ink}>{l.t}</text>
            <text x={l.x + 14} y={l.y + 37} fontSize="11" fill={C.mute}>{l.t2}</text>
          </g>
        ))}
      </svg>
    </div>
  );
}

/* ─────────────────────────────── how it works ──────────────────────────── */

function HowItWorks() {
  const agents = [
    { n: "01", c: C.mute, t: "Feature extractor", d: "A streaming topology distills each raw event into a compact, structured digest — velocities, risk hints, the entity IDs the rest of the team needs." },
    { n: "02", c: C.teal, t: "Behavioral baseliner", d: "Holds a rolling embedding ‘fingerprint’ per entity in pgvector and scores the new event by cosine similarity to it. Out of character → high anomaly." },
    { n: "02", c: C.violet, t: "Graph reasoner", d: "Runs depth-bounded templates over the entity graph to surface structure a single event can’t show: testing rings, lateral movement, sensitive access." },
    { n: "03", c: C.amber, t: "Deliberating judge", d: "An LLM weighs every signal and emits a calibrated score, a verdict, ranked contributing factors, and a short rationale an analyst can act on." },
  ];
  return (
    <Section id="how" className="border-t border-border">
      <Eyebrow>How it works</Eyebrow>
      <h2 className="mt-5 max-w-2xl text-3xl font-semibold tracking-tight md:text-4xl">
        The deliberation is the product — <span className="text-muted-foreground">not the model.</span>
      </h2>
      <p className="mt-4 max-w-2xl text-muted-foreground">
        A single LLM call gives you a guess with a rationale stapled on. CONCLAVE structures the reasoning as a
        graph of specialists, each with one job, so the verdict is auditable end to end.
      </p>

      <div className="mt-10">
        <DeliberationDiagram />
      </div>

      <div className="mt-8 grid gap-4 sm:grid-cols-2">
        {agents.map((a, i) => (
          <div key={i} className="rounded-xl border border-border bg-card/40 p-5">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold" style={{ color: a.c === C.mute ? undefined : a.c }}>{a.t}</h3>
              <span className="font-mono text-[11px] text-muted-foreground">{a.n}</span>
            </div>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{a.d}</p>
          </div>
        ))}
      </div>
      <p className="mt-6 text-sm text-muted-foreground">
        If a model is unavailable, the judge falls back to a deterministic verdict from the same signals —{" "}
        <span className="text-foreground">the pipeline never returns “no decision.”</span>
      </p>
    </Section>
  );
}

function DeliberationDiagram() {
  return (
    <div className="rounded-2xl border border-border bg-card/40 p-3 sm:p-6">
      <svg viewBox="0 0 1000 360" className="w-full" role="img" aria-label="The deliberation graph">
        <defs>
          <marker id="ah2" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
            <path d="M0 0 L8 4 L0 8 z" fill={C.mute} />
          </marker>
        </defs>

        {/* START */}
        <text x="40" y="186" fontSize="11" fill={C.mute} fontWeight="600">START</text>
        <path d="M86 182 H146" stroke={C.mute} strokeWidth="2" markerEnd="url(#ah2)" />

        {/* feature */}
        <Node x={148} y={152} w={150} h={60} title="feature" sub="event → digest" color={C.ink} />

        {/* split */}
        <g fill="none" strokeWidth="2.5">
          <path d="M298 182 C 350 182, 350 92, 408 92" stroke={C.teal} className="cv-flow" />
          <path d="M298 182 C 350 182, 350 272, 408 272" stroke={C.violet} className="cv-flow" />
        </g>

        {/* baseliner card with mini cosine */}
        <g>
          <rect x={408} y={56} width={244} height={76} rx="12" fill={C.panel} stroke={C.teal} strokeOpacity="0.5" />
          <rect x={408} y={56} width={3.5} height={76} rx="2" fill={C.teal} />
          <text x={424} y={84} fontSize="13.5" fontWeight="600" fill={C.ink}>behavioral baseliner</text>
          <text x={424} y={104} fontSize="11" fill={C.mute}>cosine( event , rolling profile )</text>
          <text x={424} y={120} fontSize="11" fill={C.teal}>anomaly ∈ [0,1] · cosine ∈ [-1,1]</text>
        </g>

        {/* graph card */}
        <g>
          <rect x={408} y={236} width={244} height={76} rx="12" fill={C.panel} stroke={C.violet} strokeOpacity="0.5" />
          <rect x={408} y={236} width={3.5} height={76} rx="2" fill={C.violet} />
          <text x={424} y={264} fontSize="13.5" fontWeight="600" fill={C.ink}>graph reasoner</text>
          <text x={424} y={284} fontSize="11" fill={C.mute}>Neo4j pattern templates</text>
          <text x={424} y={300} fontSize="11" fill={C.violet}>risk_signal ∈ [0,1]</text>
        </g>

        {/* join into judge */}
        <g fill="none" strokeWidth="2.5">
          <path d="M652 94 C 706 94, 706 182, 760 182" stroke={C.teal} className="cv-flow" />
          <path d="M652 274 C 706 274, 706 182, 760 182" stroke={C.violet} className="cv-flow" />
        </g>

        {/* judge */}
        <g filter="url(#soft)">
          <rect x={760} y={146} width={150} height={72} rx="12" fill={C.panel} stroke={C.amber} strokeOpacity="0.65" />
          <rect x={760} y={146} width={3.5} height={72} rx="2" fill={C.amber} />
          <text x={835} y={178} textAnchor="middle" fontSize="14" fontWeight="700" fill={C.ink}>judge</text>
          <text x={835} y={196} textAnchor="middle" fontSize="11" fill={C.mute}>weighs all evidence</text>
        </g>

        <path d="M910 182 H958" stroke={C.amber} strokeWidth="2.5" className="cv-flow" />
        <text x="956" y="170" textAnchor="end" fontSize="11" fill={C.mute}>END</text>
        <text x="835" y="246" textAnchor="middle" fontSize="11" fill={C.mute}>score · verdict · factors · rationale</text>

        <text x="530" y="36" textAnchor="middle" fontSize="11" fill={C.mute} className="cv-pulse">— parallel super-step —</text>
      </svg>
    </div>
  );
}

/* ─────────────────────────────── baseline ──────────────────────────────── */

function Baseline() {
  return (
    <Section id="baseline" className="border-t border-border">
      <div className="grid items-center gap-10 md:grid-cols-2">
        <div>
          <Eyebrow>Behavioral baselines, as vectors</Eyebrow>
          <h2 className="mt-5 text-3xl font-semibold tracking-tight md:text-4xl">
            Anomalous means it <span className="text-muted-foreground">stops looking like the customer.</span>
          </h2>
          <p className="mt-4 text-muted-foreground">
            Every entity carries a rolling <span className="text-foreground">behavioral fingerprint</span> — an
            exponential moving average of its past event embeddings, kept in Postgres + pgvector. A new event is
            embedded and compared by <span className="text-foreground">cosine similarity</span> to that fingerprint.
          </p>
          <ul className="mt-5 space-y-2.5 text-sm text-muted-foreground">
            {[
              "Not a threshold on amount — a deviation from learned behavior.",
              "A count-aware warmup keeps brand-new entities from being judged on a single first event.",
              "Scoring is read-only: a suspicious event never poisons the profile it’s measured against.",
            ].map((t) => (
              <li key={t} className="flex gap-2.5">
                <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: C.teal }} />
                <span>{t}</span>
              </li>
            ))}
          </ul>
        </div>
        <CosineDiagram />
      </div>
    </Section>
  );
}

function CosineDiagram() {
  return (
    <div className="rounded-2xl border border-border bg-card/40 p-3 sm:p-6">
      <svg viewBox="0 0 540 300" className="w-full" role="img" aria-label="Cosine similarity to the behavioral profile">
        <defs>
          <marker id="ahv" markerWidth="9" markerHeight="9" refX="7" refY="4.5" orient="auto">
            <path d="M0 0 L9 4.5 L0 9 z" fill={C.ink} />
          </marker>
          <marker id="aht" markerWidth="9" markerHeight="9" refX="7" refY="4.5" orient="auto">
            <path d="M0 0 L9 4.5 L0 9 z" fill={C.teal} />
          </marker>
          <marker id="ahr" markerWidth="9" markerHeight="9" refX="7" refY="4.5" orient="auto">
            <path d="M0 0 L9 4.5 L0 9 z" fill="#ff7a90" />
          </marker>
        </defs>

        {/* origin */}
        <circle cx="70" cy="248" r="3.5" fill={C.mute} />

        {/* vectors fan from the origin; every label lives to the RIGHT of the
            arrow tips, in clear space, with a halo — so nothing sits on a line. */}
        <line x1="70" y1="248" x2="250" y2="64" stroke="#ff7a90" strokeWidth="2.5" markerEnd="url(#ahr)" />
        <line x1="70" y1="248" x2="322" y2="138" stroke={C.ink} strokeWidth="2.5" markerEnd="url(#ahv)" />
        <line x1="70" y1="248" x2="322" y2="196" stroke={C.teal} strokeWidth="2.5" markerEnd="url(#aht)" />

        {/* angle arc + θ between the profile and the out-of-character vector.
            Both endpoints sit at radius 50 from the origin (70,248), so it reads
            as a true angle wedge at the vertex instead of a stray stroke. */}
        <path d="M115.8 228 A 50 50 0 0 0 105 212.3" fill="none" stroke={C.mute} strokeWidth="1.3" />
        <Chip x={140} y={199} text="θ" color={C.mute} size={12} anchor="middle" />

        <Chip x={262} y={62} text="out of character" color="#ff7a90" size={12} weight="600" />
        <Chip x={262} y={82} text="cos θ low → high anomaly" color={C.mute} size={10.5} />

        <Chip x={334} y={136} text="profile" color={C.ink} size={12.5} weight="700" />
        <Chip x={334} y={156} text="EMA of past behavior" color={C.mute} size={10.5} />

        <Chip x={334} y={196} text="looks normal" color={C.teal} size={12} weight="600" />
        <Chip x={334} y={216} text="cos θ high → low anomaly" color={C.mute} size={10.5} />
      </svg>
    </div>
  );
}

/* ───────────────────────────── architecture ────────────────────────────── */

function Architecture() {
  const rows = [
    ["orchestrator", "ingest · enrichment · feature extraction · audit API", "Java · Kafka Streams"],
    ["baseline", "rolling behavioral embeddings + cosine scoring", "Java · Postgres + pgvector"],
    ["graph", "relational pattern templates", "Java · Neo4j"],
    ["agents", "the deliberation graph + LLM judge", "Python · LangGraph · gRPC"],
    ["dashboard", "live decision explorer", "React · Vite"],
  ];
  return (
    <Section id="architecture" className="border-t border-border">
      <Eyebrow>Architecture</Eyebrow>
      <h2 className="mt-5 max-w-2xl text-3xl font-semibold tracking-tight md:text-4xl">
        A streaming pipeline, <span className="text-muted-foreground">audited end to end.</span>
      </h2>

      <div className="mt-10">
        <PipelineDiagram />
      </div>

      <div className="mt-8 overflow-hidden rounded-xl border border-border">
        <table className="w-full text-left text-sm">
          <thead className="bg-muted/40 text-xs uppercase tracking-wider text-muted-foreground">
            <tr>
              <th className="px-4 py-3 font-medium">Service</th>
              <th className="px-4 py-3 font-medium">Role</th>
              <th className="hidden px-4 py-3 font-medium sm:table-cell">Stack</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r[0]} className="border-t border-border">
                <td className="px-4 py-3 font-mono text-foreground">{r[0]}</td>
                <td className="px-4 py-3 text-muted-foreground">{r[1]}</td>
                <td className="hidden px-4 py-3 font-mono text-xs text-muted-foreground sm:table-cell">{r[2]}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

function PipelineDiagram() {
  const lane = (x: number, label: string) => (
    <text x={x} y={28} fontSize="10.5" fill={C.mute} fontWeight="600" textAnchor="middle">{label}</text>
  );
  return (
    <div className="rounded-2xl border border-border bg-card/40 p-3 sm:p-6">
      <svg viewBox="0 0 1000 270" className="w-full" role="img" aria-label="End-to-end streaming pipeline">
        <defs>
          <marker id="ahp" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
            <path d="M0 0 L8 4 L0 8 z" fill={C.mute} />
          </marker>
        </defs>

        {lane(110, "PRODUCERS")}
        {lane(390, "ORCHESTRATOR · Kafka Streams")}
        {lane(680, "AGENTS · LangGraph")}
        {lane(910, "AUDIT")}

        {/* dividers */}
        <g stroke={C.line} strokeDasharray="3 6">
          <line x1="220" y1="44" x2="220" y2="250" />
          <line x1="560" y1="44" x2="560" y2="250" />
          <line x1="800" y1="44" x2="800" y2="250" />
        </g>

        {/* producers */}
        <PBox x={40} y={120} w={150} h={52} t="events.raw" s="generators / apps" c={C.mute} />

        {/* orchestrator */}
        <PBox x={250} y={80} w={280} h={52} t="enrich + feature-extract" s="" c={C.ink} />
        <PBox x={250} y={158} w={280} h={44} t="events.enriched" s="" c={C.mute} small />

        {/* agents trio */}
        <PBox x={596} y={70} w={176} h={40} t="behavioral baseliner" s="" c={C.teal} small />
        <PBox x={596} y={118} w={176} h={40} t="graph reasoner" s="" c={C.violet} small />
        <PBox x={596} y={166} w={176} h={40} t="deliberating judge" s="" c={C.amber} small />

        {/* audit */}
        <PBox x={826} y={80} w={150} h={52} t="Decision" s="→ Postgres" c={C.ink} />
        <PBox x={826} y={158} w={150} h={48} t="/api/v1/decisions" s="+ dashboard" c={C.mute} small />

        {/* arrows */}
        <g fill="none" strokeWidth="2">
          {/* producers → enrich */}
          <path d="M190 146 C 220 146, 222 106, 250 106" stroke={C.mute} markerEnd="url(#ahp)" />
          {/* enrich → events.enriched (the topic the agents consume) */}
          <path d="M390 132 V158" stroke={C.mute} markerEnd="url(#ahp)" />
          {/* events.enriched → agents: the agents consume the enriched stream */}
          <path d="M530 180 C 566 180, 570 90, 596 90" stroke={C.teal} className="cv-flow" />
          <path d="M530 180 C 566 180, 570 138, 596 138" stroke={C.violet} className="cv-flow" />
          <path d="M530 180 C 566 180, 570 186, 596 186" stroke={C.amber} className="cv-flow" />
          {/* judge → Decision → audit API */}
          <path d="M772 186 C 800 186, 800 106, 826 106" stroke={C.amber} className="cv-flow" />
          <path d="M901 132 V158" stroke={C.mute} markerEnd="url(#ahp)" />
        </g>

        <text x="500" y="246" textAnchor="middle" fontSize="10.5" fill={C.mute}>
          infra: Kafka · Schema Registry · Postgres (pgvector) · Neo4j     ·     domain = fraud | security
        </text>
      </svg>
    </div>
  );
}

function PBox({
  x, y, w, h, t, s, c, small = false,
}: {
  x: number; y: number; w: number; h: number; t: string; s: string; c: string; small?: boolean;
}) {
  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx="9" fill={C.panel} stroke={c} strokeOpacity="0.45" />
      <rect x={x} y={y} width={3} height={h} rx="2" fill={c} />
      <text x={x + w / 2} y={y + (s ? h / 2 - 2 : h / 2 + 4)} textAnchor="middle" fontSize={small ? "11.5" : "13"} fontWeight="600" fill={C.ink}>{t}</text>
      {s ? <text x={x + w / 2} y={y + h / 2 + 14} textAnchor="middle" fontSize="10.5" fill={C.mute}>{s}</text> : null}
    </g>
  );
}

/* ───────────────────────────────── domains ─────────────────────────────── */

function Domains() {
  const [tab, setTab] = useState<"fraud" | "security">("fraud");
  const data = {
    fraud: {
      title: "Payment fraud",
      tag: "card-not-present",
      entity: "cardholder / card",
      graph: ["cardholder", "device", "IP", "merchant"],
      patterns: ["card-testing ring", "bust-out", "account takeover"],
      cmd: "./scripts/up.sh local fraud",
      color: C.teal,
    },
    security: {
      title: "Security / SOC",
      tag: "identity & access",
      entity: "principal / identity",
      graph: ["principal", "host", "resource"],
      patterns: ["lateral movement", "exfiltration", "account takeover"],
      cmd: "./scripts/up.sh local security",
      color: C.violet,
    },
  } as const;
  const d = data[tab];
  return (
    <Section id="domains" className="border-t border-border">
      <Eyebrow>One architecture, two domains</Eyebrow>
      <h2 className="mt-5 max-w-2xl text-3xl font-semibold tracking-tight md:text-4xl">
        Same agents. <span className="text-muted-foreground">Swap the config.</span>
      </h2>
      <p className="mt-4 max-w-2xl text-muted-foreground">
        Domain-specific logic lives only in the feature extractor and the graph schema. The orchestrator,
        baseliner, judge, and audit trail are identical across both. That portability is the point.
      </p>

      <div className="mt-8 inline-flex rounded-lg border border-border bg-muted/30 p-1">
        {(["fraud", "security"] as const).map((k) => (
          <button
            key={k}
            onClick={() => setTab(k)}
            className={`rounded-md px-4 py-1.5 text-sm transition-colors ${
              tab === k ? "bg-foreground text-background" : "text-muted-foreground hover:text-foreground"
            }`}
          >
            {data[k].title}
          </button>
        ))}
      </div>

      <div className="mt-6 grid gap-5 md:grid-cols-2">
        <div className="rounded-xl border border-border bg-card/40 p-6">
          <div className="flex items-center gap-2.5">
            <span className="h-2.5 w-2.5 rounded-full" style={{ background: d.color }} />
            <h3 className="text-lg font-semibold">{d.title}</h3>
            <span className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">{d.tag}</span>
          </div>
          <dl className="mt-5 space-y-3 text-sm">
            <Row k="Entity" v={d.entity} />
            <Row k="Graph" v={d.graph.join(" · ")} />
            <Row k="Patterns" v={d.patterns.join(" · ")} />
          </dl>
          <div className="mt-5 rounded-lg border border-border bg-background p-3 font-mono text-xs">
            <span className="text-muted-foreground">$ </span>
            <span className="text-foreground">{d.cmd}</span>
          </div>
        </div>
        <div className="rounded-xl border border-border bg-card/40 p-6">
          <GraphShape nodes={d.graph} color={d.color} />
        </div>
      </div>
    </Section>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex gap-4">
      <dt className="w-20 shrink-0 text-muted-foreground">{k}</dt>
      <dd className="text-foreground">{v}</dd>
    </div>
  );
}

function GraphShape({ nodes, color }: { nodes: readonly string[]; color: string }) {
  // Render the entity graph as a small star: first node center, rest around it.
  const cx = 230, cy = 130, r = 92;
  const outer = nodes.slice(1);
  const pts = outer.map((_, i) => {
    const a = (-90 + (360 / outer.length) * i) * (Math.PI / 180);
    return { x: cx + r * Math.cos(a), y: cy + r * Math.sin(a) };
  });
  return (
    <svg viewBox="0 0 460 260" className="w-full" role="img" aria-label="Entity graph shape">
      <g stroke={color} strokeOpacity="0.5" strokeWidth="2" fill="none">
        {pts.map((p, i) => (
          <line key={i} x1={cx} y1={cy} x2={p.x} y2={p.y} className="cv-flow-slow" />
        ))}
      </g>
      {pts.map((p, i) => (
        <g key={i}>
          <circle cx={p.x} cy={p.y} r="26" fill={C.panel} stroke={color} strokeOpacity="0.55" />
          <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize="11" fill={C.ink}>{outer[i]}</text>
        </g>
      ))}
      <circle cx={cx} cy={cy} r="32" fill={C.panel} stroke={color} />
      <text x={cx} y={cy + 4} textAnchor="middle" fontSize="11.5" fontWeight="700" fill={C.ink}>{nodes[0]}</text>
    </svg>
  );
}

/* ─────────────────────────────── performance ───────────────────────────── */

function Performance() {
  const stats = [
    { v: "0.74 ms", l: "behavioral lookup", s: "p99 · pgvector" },
    { v: "6 ms", l: "graph query", s: "p99 · 100K-edge graph" },
    { v: "< 600 ms", l: "judge verdict", s: "target, cloud backend" },
    { v: "2", l: "domains, one codebase", s: "fraud + security" },
  ];
  return (
    <Section className="border-t border-border">
      <div className="grid gap-px overflow-hidden rounded-2xl border border-border bg-border sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <div key={s.l} className="bg-card/60 p-6">
            <div className="text-3xl font-semibold tracking-tight">{s.v}</div>
            <div className="mt-1 text-sm text-foreground">{s.l}</div>
            <div className="mt-0.5 font-mono text-[11px] text-muted-foreground">{s.s}</div>
          </div>
        ))}
      </div>
      <p className="mt-4 text-center text-xs text-muted-foreground">
        Measured from the integration test suite. Every decision is persisted with its score, factors, and rationale for audit.
      </p>
    </Section>
  );
}

/* ────────────────────────────────── paper ──────────────────────────────── */

function Paper() {
  const rows = [
    { d: "Payment fraud", n: "332", auc: "0.999", block: "100% · 0 FP", flagged: "100% of attacks" },
    { d: "Security / SOC", n: "254", auc: "0.819", block: "100% · 0 FP", flagged: "lateral movement" },
  ];
  return (
    <Section id="paper" className="border-t border-border">
      <div className="grid gap-10 md:grid-cols-[1fr_1.05fr] md:items-start">
        <div>
          <Eyebrow>Paper</Eyebrow>
          <h2 className="mt-5 max-w-xl text-3xl font-semibold tracking-tight md:text-4xl">
            One architecture, measured <span className="text-muted-foreground">end-to-end.</span>
          </h2>
          <p className="mt-4 text-muted-foreground">
            An honest engineering paper on multi-agent deliberation as a first-class risk-decision
            architecture. Every number is captured by running the live stack and joining persisted
            decisions against leakage-proof ground-truth labels — no offline re-scoring. The judge
            for these runs is <span className="text-foreground">gemini-3.1-flash-lite</span> via OpenRouter.
          </p>
          <p className="mt-4 text-muted-foreground">
            The central finding is a deliberate negative result: detection tracks{" "}
            <span className="text-foreground">evidence coverage</span>. Where an agent surfaces the
            discriminative signal (fraud rings, lateral movement), detection is near-perfect; where
            none does (security exfiltration / account-takeover), the judge has nothing to act on — a
            judge weighs presented evidence, it is not an oracle.
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <a
              href={PAPER}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-2 rounded-lg bg-foreground px-4 py-2.5 text-sm font-medium text-background transition-opacity hover:opacity-90"
            >
              Read the PDF <Arrow />
            </a>
            <a
              href={REPO}
              className="inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
            >
              <GitHubMark /> Source on GitHub
            </a>
          </div>
        </div>

        <div className="overflow-hidden rounded-2xl border border-border bg-card/60">
          <div className="border-b border-border px-5 py-3 text-xs font-medium uppercase tracking-wider text-muted-foreground">
            Detection, end-to-end
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-[12px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-5 py-2.5 font-medium">Domain</th>
                  <th className="px-3 py-2.5 font-medium">Decisions</th>
                  <th className="px-3 py-2.5 font-medium">ROC-AUC</th>
                  <th className="px-3 py-2.5 font-medium">Block prec.</th>
                  <th className="px-5 py-2.5 font-medium">Flagged</th>
                </tr>
              </thead>
              <tbody className="font-mono">
                {rows.map((r) => (
                  <tr key={r.d} className="border-b border-border/60 last:border-0">
                    <td className="px-5 py-3 font-sans text-foreground">{r.d}</td>
                    <td className="px-3 py-3 text-muted-foreground">{r.n}</td>
                    <td className="px-3 py-3 text-foreground">{r.auc}</td>
                    <td className="px-3 py-3 text-foreground">{r.block}</td>
                    <td className="px-5 py-3 text-muted-foreground">{r.flagged}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="space-y-1.5 px-5 py-4 text-xs text-muted-foreground">
            <p>
              <span className="text-foreground">Fraud:</span> zero false-positive blocks; every injected
              attack flagged at review-or-higher; 98.9% precision at FPR&nbsp;≤&nbsp;1%.
            </p>
            <p>
              <span className="text-foreground">Latency:</span> behavioral lookup p99 0.74&nbsp;ms · graph
              query p99 6&nbsp;ms · end-to-end dominated by the LLM judge (p50 ≈ 1.8&nbsp;s).
            </p>
          </div>
        </div>
      </div>
    </Section>
  );
}

/* ──────────────────────────────── quickstart ───────────────────────────── */

function Quickstart() {
  const cmds = [
    ["git clone https://github.com/Abhishek-Aditya-bs/Conclave && cd Conclave", "comment"],
    ["cp .env.example .env", "# add your key (OpenRouter is cheapest)"],
    ["./scripts/up.sh", "# pick judge model + domain, then builds + boots"],
    ["./scripts/seed.sh", "# fire a small labeled burst (prompts the domain)"],
    ["open http://localhost:8080/api/v1/decisions", ""],
    ["./scripts/dashboard.sh", "# live explorer → :5173"],
    ["./scripts/down.sh", "# tear it all down"],
  ];
  return (
    <Section id="start" className="border-t border-border">
      <div className="grid gap-10 md:grid-cols-[0.9fr_1.1fr] md:items-center">
        <div>
          <Eyebrow>Quickstart</Eyebrow>
          <h2 className="mt-5 text-3xl font-semibold tracking-tight md:text-4xl">
            Clone, then <span className="text-muted-foreground">one command.</span>
          </h2>
          <p className="mt-4 text-muted-foreground">
            Run <span className="font-mono text-foreground">up.sh</span> with no arguments and it walks you through
            the judge model and domain, validates your setup, then builds and boots the full stack. The default
            judge is <span className="text-foreground">gemini-3.1-flash-lite</span> via OpenRouter — fast and cheap.
            Both <span className="text-foreground">fraud</span> and{" "}
            <span className="text-foreground">security</span> run on the same images — try one, then the other.
          </p>
          <ul className="mt-5 space-y-2 text-sm text-muted-foreground">
            <li>Prereqs: Docker Desktop · JDK 25 · Maven · uv · Node 20+ · optional gum for the picker</li>
            <li>
              <span className="text-foreground">serving</span> uses OpenRouter, OpenAI, or Claude — any OpenAI-compatible endpoint. Keys live in a git-ignored <span className="font-mono">.env</span>.
            </li>
            <li>
              <span className="text-foreground">local</span> runs the judge on your own Ollama — no API key, auto-started for you (slower per call).
            </li>
          </ul>
          <a
            href={REPO}
            className="mt-6 inline-flex items-center gap-2 rounded-lg border border-border px-4 py-2.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            <GitHubMark /> Read the docs <Arrow />
          </a>
        </div>

        <div className="overflow-hidden rounded-xl border border-border bg-card/60">
          <div className="flex items-center gap-2 border-b border-border bg-muted/40 px-4 py-2.5">
            <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" />
            <span className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" />
            <span className="h-2.5 w-2.5 rounded-full bg-[#28c840]" />
            <span className="ml-2 font-mono text-xs text-muted-foreground">conclave — zsh</span>
          </div>
          <pre className="overflow-x-auto p-4 font-mono text-[12.5px] leading-relaxed">
            {cmds.map(([cmd, note], i) => (
              <div key={i} className="whitespace-pre">
                <span className="text-muted-foreground">$ </span>
                <span className="text-foreground">{cmd}</span>
                {note && note !== "comment" ? <span className="text-muted-foreground">  {note}</span> : null}
              </div>
            ))}
            <div className="mt-3 whitespace-pre text-muted-foreground">{`{ "verdict_label": "BLOCK", "score": 0.91,`}</div>
            <div className="whitespace-pre text-muted-foreground">{`  "contributing_factors": ["graph_ring_detected", "behavioral_anomaly"],`}</div>
            <div className="whitespace-pre text-muted-foreground">{`  "verdict_explanation_md": "Device fans out across 7 cards…" }`}</div>
          </pre>
        </div>
      </div>
    </Section>
  );
}

/* ──────────────────────────────── footer ───────────────────────────────── */

function Footer() {
  return (
    <footer className="border-t border-border">
      <div className="mx-auto flex w-full max-w-6xl flex-col items-start justify-between gap-6 px-5 py-10 sm:flex-row sm:items-center">
        <div className="flex items-center gap-2.5">
          <Logo size={24} />
          <span className="text-sm text-muted-foreground">
            <span className="font-semibold tracking-[0.18em] text-foreground">CONCLAVE</span> · MIT license
          </span>
        </div>
        <div className="flex items-center gap-6 text-sm text-muted-foreground">
          <a href="#how" className="transition-colors hover:text-foreground">How it works</a>
          <a href="#architecture" className="transition-colors hover:text-foreground">Architecture</a>
          <a href={PAPER} target="_blank" rel="noreferrer" className="transition-colors hover:text-foreground">Paper</a>
          <a href={LIVE} className="transition-colors hover:text-foreground">Live</a>
          <a href={REPO} className="inline-flex items-center gap-1.5 transition-colors hover:text-foreground">
            <GitHubMark /> GitHub
          </a>
        </div>
      </div>
      <div className="mx-auto w-full max-w-6xl px-5 pb-10">
        <p className="text-xs text-muted-foreground/70">
          Risk detection that is accurate, relational, and explainable — at once.
        </p>
      </div>
    </footer>
  );
}
