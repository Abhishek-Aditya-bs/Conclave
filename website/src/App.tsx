import {
  ArrowRight,
  ArrowUpRight,
  Boxes,
  Cpu,
  Github,
  Layers,
  Network,
  ShieldCheck,
  Sparkles,
  Workflow,
} from "lucide-react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

const REPO = "https://github.com/anthropics/conclave";

export default function App() {
  return (
    <div className="relative min-h-full bg-background text-foreground">
      <Nav />
      <Hero />
      <Stats />
      <Architecture />
      <Configs />
      <Benchmarks />
      <Quickstart />
      <Footer />
    </div>
  );
}

/* ────────────────────────────────────────────────────────────── chrome */

function Nav() {
  return (
    <nav className="sticky top-0 z-40 border-b border-border/60 bg-background/70 backdrop-blur-xl">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-3.5">
        <a href="/" className="flex items-center gap-2 text-sm">
          <Logo />
          <span className="font-medium tracking-tight">CONCLAVE</span>
          <Badge variant="outline" className="ml-1 text-[10px] uppercase">
            v0.1
          </Badge>
        </a>
        <div className="hidden items-center gap-1 md:flex">
          <NavLink href="#architecture">Architecture</NavLink>
          <NavLink href="#configs">Configs</NavLink>
          <NavLink href="#benchmarks">Benchmarks</NavLink>
          <NavLink href="#quickstart">Quickstart</NavLink>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" asChild>
            <a href={REPO} target="_blank" rel="noopener">
              <Github />
              GitHub
            </a>
          </Button>
          <Button size="sm" asChild>
            <a href="#quickstart">
              Quickstart
              <ArrowRight />
            </a>
          </Button>
        </div>
      </div>
    </nav>
  );
}

function NavLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <a
      href={href}
      className="rounded-md px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
    >
      {children}
    </a>
  );
}

function Logo() {
  return (
    <svg width="20" height="20" viewBox="0 0 32 32" fill="none" aria-hidden>
      <rect width="32" height="32" rx="6" fill="currentColor" className="text-foreground" />
      <circle cx="16" cy="16" r="9" stroke="oklch(0 0 0)" strokeWidth="1.5" />
      <circle cx="11" cy="13" r="1.5" fill="oklch(0 0 0)" />
      <circle cx="21" cy="13" r="1.5" fill="oklch(0 0 0)" />
      <circle cx="11" cy="19" r="1.5" fill="oklch(0 0 0)" />
      <circle cx="21" cy="19" r="1.5" fill="oklch(0 0 0)" />
    </svg>
  );
}

/* ────────────────────────────────────────────────────────────── hero */

function Hero() {
  return (
    <section className="relative overflow-hidden border-b border-border/60">
      <div aria-hidden className="bg-grid bg-grid-fade absolute inset-0" />
      <div aria-hidden className="glow absolute inset-x-0 top-0 h-[600px]" />

      <div className="relative mx-auto grid max-w-6xl gap-12 px-6 py-24 md:grid-cols-[1.1fr_0.9fr] md:py-32">
        <div className="flex flex-col justify-center">
          <Badge
            variant="outline"
            className="mb-6 w-fit gap-1.5 border-foreground/15 px-2 py-1 text-[10px] uppercase tracking-widest text-muted-foreground"
          >
            <Sparkles className="size-3" />
            multi-agent · real-time · explainable
          </Badge>
          <h1 className="font-heading text-balance text-4xl leading-[1.05] tracking-tight md:text-6xl">
            Four agents deliberate
            <br />
            <span className="text-muted-foreground">on every event.</span>
          </h1>
          <p
            data-body="prose"
            className="mt-6 max-w-xl text-base leading-relaxed text-muted-foreground md:text-lg"
          >
            A feature extractor, a behavioural baseliner, a graph reasoner, and a
            judge — coordinated via LangGraph — read each enriched event and
            return a calibrated risk score plus a verdict you can hand to a
            reviewer. One architecture; two reference configurations
            (fraud&nbsp;+&nbsp;security) running off the same image set.
          </p>
          <div className="mt-8 flex flex-wrap items-center gap-3">
            <Button size="lg" asChild>
              <a href="#quickstart">
                Run the demo
                <ArrowRight />
              </a>
            </Button>
            <Button size="lg" variant="outline" asChild>
              <a href={REPO} target="_blank" rel="noopener">
                <Github />
                View source
                <ArrowUpRight />
              </a>
            </Button>
          </div>
          <p className="mt-6 text-xs text-muted-foreground">
            <span className="text-foreground">Java 25</span> · Spring Boot 4 ·
            Kafka Streams · pgvector · Neo4j · LangGraph ·{" "}
            <span className="text-foreground">Claude Haiku 4.5</span>
          </p>
        </div>

        <Terminal />
      </div>
    </section>
  );
}

function Terminal() {
  return (
    <Card className="self-center bg-card/60 ring-foreground/10 backdrop-blur supports-[backdrop-filter]:bg-card/40">
      <CardHeader className="flex flex-row items-center justify-between border-b border-border/60 pb-3">
        <div className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-full bg-foreground/15" />
          <span className="size-2.5 rounded-full bg-foreground/15" />
          <span className="size-2.5 rounded-full bg-foreground/15" />
        </div>
        <span className="text-[10px] uppercase tracking-widest text-muted-foreground">
          ~/conclave
        </span>
      </CardHeader>
      <CardContent className="px-4 pb-4 font-mono text-[12.5px] leading-relaxed">
        <Line prompt prefix="$">make demo-fraud</Line>
        <Line muted>↳ mvn -DskipTests package … ✓</Line>
        <Line muted>↳ docker compose up -d … ✓ kafka schema-registry postgres neo4j</Line>
        <Line muted>↳ agents · orchestrator · baseline · graph started</Line>
        <Line muted>↳ generators: 200 clean · 2 card-testing rings · 1 ato · 1 bust-out</Line>
        <div className="my-2 h-px bg-border/60" />
        <Line prompt prefix="$">curl /api/v1/decisions?domain=fraud&min_score=0.66</Line>
        <Line className="text-foreground">
          {`{ "decision_id": "0193…", "verdict_label": `}
          <span className="rounded bg-destructive/10 px-1 py-0.5 text-destructive">
            "BLOCK"
          </span>
          {`,`}
        </Line>
        <Line className="text-foreground">
          {`  "score": `}
          <span className="text-foreground">0.842</span>
          {`, "latency_ms": 587, "judge_provider": "anthropic" }`}
        </Line>
        <Line muted className="mt-1">
          <span className="inline-block size-1.5 animate-pulse rounded-full bg-foreground/70" />
          <span className="ml-2">streaming…</span>
        </Line>
      </CardContent>
    </Card>
  );
}

function Line({
  children,
  prompt,
  prefix,
  muted,
  className,
}: {
  children: React.ReactNode;
  prompt?: boolean;
  prefix?: string;
  muted?: boolean;
  className?: string;
}) {
  return (
    <div
      className={`flex gap-2 ${muted ? "text-muted-foreground" : ""} ${className ?? ""}`}
    >
      {prompt ? (
        <span className="select-none text-muted-foreground">{prefix ?? "$"}</span>
      ) : null}
      <span className="min-w-0 break-all">{children}</span>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────── stats */

function Stats() {
  const items: { label: string; value: string; sub: string }[] = [
    { label: "M3 baseline · p99", value: "0.74 ms", sub: "27× under budget" },
    { label: "M4 graph · p99", value: "6 ms", sub: "8× under budget; 100K edges" },
    { label: "M5 judge · target", value: "<600 ms", sub: "Haiku 4.5 · tool-use output" },
    { label: "Java + Python tests", value: "318/318", sub: "across 6 modules, gated 80% line" },
  ];
  return (
    <section className="border-b border-border/60 bg-background">
      <div className="mx-auto grid max-w-6xl grid-cols-2 divide-x divide-border/60 md:grid-cols-4">
        {items.map((it) => (
          <div key={it.label} className="px-6 py-8">
            <p className="text-[10px] uppercase tracking-widest text-muted-foreground">
              {it.label}
            </p>
            <p className="mt-2 text-2xl tabular-nums tracking-tight text-foreground md:text-3xl">
              {it.value}
            </p>
            <p className="mt-1 text-xs text-muted-foreground">{it.sub}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

/* ────────────────────────────────────────────────────────────── architecture */

function Architecture() {
  const agents = [
    {
      icon: Workflow,
      tag: "01 · stream",
      title: "Feature extractor",
      body: "Kafka Streams topology per domain. Stateful counters, BIN risk, graph entity IDs. Emits to events.{domain}.enriched.",
    },
    {
      icon: Cpu,
      tag: "02 · parallel",
      title: "Behavioural baseliner",
      body: "pgvector + langchain4j MiniLM in-JVM. EMA-rolled per-entity embedding. p99 lookup 0.74 ms.",
    },
    {
      icon: Network,
      tag: "02 · parallel",
      title: "Graph reasoner",
      body: "Neo4j with fixed Cypher templates — depth-bounded, latency-bounded. Card-testing rings, lateral movement, privileged access.",
    },
    {
      icon: ShieldCheck,
      tag: "03 · verdict",
      title: "Deliberating judge",
      body: "Claude Haiku 4.5 via Anthropic SDK (tool-use structured output) or Ollama for self-hosters. Returns score, label, factors, NL verdict.",
    },
  ];

  return (
    <section id="architecture" className="border-b border-border/60">
      <div className="mx-auto max-w-6xl px-6 py-24">
        <SectionEyebrow tag="Architecture" />
        <SectionTitle>
          The deliberation is the contribution.
          <br />
          <span className="text-muted-foreground">Not the LLM.</span>
        </SectionTitle>
        <p
          data-body="prose"
          className="mt-6 max-w-2xl text-muted-foreground"
        >
          A single LLM call gives you a post-hoc rationale. CONCLAVE structures
          the reasoning as a graph of specialist agents — each with a tight tool
          budget and a measurable latency contract — so the verdict is auditable
          end-to-end, not just labelled.
        </p>

        <div className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-2">
          {agents.map((a) => (
            <Card key={a.title} className="group/agent relative overflow-hidden">
              <CardHeader>
                <Badge
                  variant="outline"
                  className="w-fit text-[10px] uppercase tracking-widest"
                >
                  {a.tag}
                </Badge>
                <CardTitle className="mt-3 flex items-center gap-2 text-base">
                  <a.icon className="size-4 text-muted-foreground" />
                  {a.title}
                </CardTitle>
                <CardDescription
                  data-body="prose"
                  className="mt-2 text-sm leading-relaxed"
                >
                  {a.body}
                </CardDescription>
              </CardHeader>
            </Card>
          ))}
        </div>

        <p className="mt-6 text-xs text-muted-foreground">
          Baseliner and Graph reasoner run on the same LangGraph super-step;
          the judge consumes both. End-to-end target:{" "}
          <span className="text-foreground">p99 &lt; 750&nbsp;ms</span>.
        </p>
      </div>
    </section>
  );
}

/* ────────────────────────────────────────────────────────────── configs */

function Configs() {
  return (
    <section id="configs" className="border-b border-border/60">
      <div className="mx-auto max-w-6xl px-6 py-24">
        <SectionEyebrow tag="Two reference configurations" />
        <SectionTitle>
          One architecture.
          <br />
          <span className="text-muted-foreground">Switched by env var.</span>
        </SectionTitle>
        <p data-body="prose" className="mt-6 max-w-2xl text-muted-foreground">
          Domain-specific code lives only in the feature extractor and the graph
          schema. Everything else — orchestrator, baseliner, judge, audit
          API — is identical across both. The whole point of the project.
        </p>

        <div className="mt-10">
          <Tabs defaultValue="fraud" className="gap-6">
            <TabsList className="self-start">
              <TabsTrigger value="fraud">
                <Layers />
                Payment fraud
              </TabsTrigger>
              <TabsTrigger value="security">
                <Boxes />
                Security / SOC
              </TabsTrigger>
            </TabsList>

            <TabsContent value="fraud">
              <ConfigDetail
                title="Payment fraud"
                tag="Stripe-Radar style"
                bullets={[
                  "PaymentEvent — card-not-present transactions",
                  "Cardholder · device · IP graph (Neo4j)",
                  "Detects card-testing rings, ATO, bust-out fraud",
                ]}
                cmd="make demo-fraud"
                topic="events.fraud.enriched"
              />
            </TabsContent>

            <TabsContent value="security">
              <ConfigDetail
                title="Security / SOC"
                tag="Lateral-movement style"
                bullets={[
                  "AuthEvent — logins · API key use · privileged access",
                  "Identity · host · resource graph (Neo4j)",
                  "Detects lateral movement, exfiltration, ATO",
                ]}
                cmd="make demo-security"
                topic="events.security.enriched"
              />
            </TabsContent>
          </Tabs>
        </div>
      </div>
    </section>
  );
}

function ConfigDetail({
  title,
  tag,
  bullets,
  cmd,
  topic,
}: {
  title: string;
  tag: string;
  bullets: string[];
  cmd: string;
  topic: string;
}) {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-[1fr_0.9fr]">
      <Card>
        <CardHeader>
          <Badge variant="outline" className="w-fit text-[10px] uppercase tracking-widest">
            {tag}
          </Badge>
          <CardTitle className="mt-3 text-xl">{title}</CardTitle>
        </CardHeader>
        <CardContent>
          <ul className="space-y-2 text-sm text-muted-foreground">
            {bullets.map((b) => (
              <li key={b} className="flex items-start gap-2">
                <span className="mt-2 size-1 rounded-full bg-foreground/40" />
                <span data-body="prose">{b}</span>
              </li>
            ))}
          </ul>
        </CardContent>
      </Card>
      <Card className="bg-card/60">
        <CardHeader className="border-b border-border/60 pb-3">
          <CardTitle className="text-[11px] uppercase tracking-widest text-muted-foreground">
            ~/conclave
          </CardTitle>
        </CardHeader>
        <CardContent className="font-mono text-[12.5px] leading-relaxed">
          <Line prompt prefix="$">{cmd}</Line>
          <Line muted>↳ stack up. fires a starter event burst.</Line>
          <div className="my-2 h-px bg-border/60" />
          <Line prompt prefix="$">kafka-console-consumer --topic {topic}</Line>
          <Line muted>... enriched events streaming →</Line>
        </CardContent>
      </Card>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────── benchmarks */

function Benchmarks() {
  const rows: {
    component: string;
    budget: string;
    p99: string;
    headroom: string;
    note: string;
    pending?: boolean;
  }[] = [
    {
      component: "M3 baseline lookup",
      budget: "20 ms",
      p99: "0.74 ms",
      headroom: "27×",
      note: "Apple M3 · 10K baselines · 1K random reads",
    },
    {
      component: "M4 graph query",
      budget: "50 ms",
      p99: "6 ms",
      headroom: "8×",
      note: "Apple M3 · 100K-edge synthetic graph",
    },
    {
      component: "M5 deliberation (Haiku 4.5)",
      budget: "600 ms",
      p99: "—",
      headroom: "—",
      note: "Pending eval pass (post-M10)",
      pending: true,
    },
    {
      component: "M6 end-to-end",
      budget: "750 ms",
      p99: "—",
      headroom: "—",
      note: "Pending eval pass",
      pending: true,
    },
  ];

  return (
    <section id="benchmarks" className="border-b border-border/60 bg-background">
      <div className="mx-auto max-w-6xl px-6 py-24">
        <SectionEyebrow tag="Benchmarks" />
        <SectionTitle>
          Latency budgets met.
          <br />
          <span className="text-muted-foreground">No fanfare needed.</span>
        </SectionTitle>
        <p data-body="prose" className="mt-6 max-w-2xl text-muted-foreground">
          Measured from the Testcontainers integration tests in CI. Numbers below
          ship from the actual test suite; M5/M6 totals land with the post-M10
          benchmark pipeline. No marketing-grade synthetic charts.
        </p>

        <div className="mt-10 overflow-hidden rounded-xl border border-border bg-card/40">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Component</TableHead>
                <TableHead className="text-right">Budget</TableHead>
                <TableHead className="text-right">Measured (p99)</TableHead>
                <TableHead className="text-right">Headroom</TableHead>
                <TableHead className="hidden md:table-cell">Notes</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.component}>
                  <TableCell className="font-medium">{r.component}</TableCell>
                  <TableCell className="text-right tabular-nums text-muted-foreground">
                    {r.budget}
                  </TableCell>
                  <TableCell
                    className={`text-right tabular-nums ${
                      r.pending ? "text-muted-foreground" : "text-foreground"
                    }`}
                  >
                    {r.p99}
                  </TableCell>
                  <TableCell className="text-right tabular-nums text-muted-foreground">
                    {r.headroom}
                  </TableCell>
                  <TableCell className="hidden text-xs text-muted-foreground md:table-cell">
                    {r.note}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </div>
    </section>
  );
}

/* ────────────────────────────────────────────────────────────── quickstart */

function Quickstart() {
  return (
    <section id="quickstart" className="border-b border-border/60">
      <div className="mx-auto max-w-6xl px-6 py-24">
        <SectionEyebrow tag="Quickstart" />
        <SectionTitle>
          <span className="text-muted-foreground">git clone, then</span>
          <br />
          make demo-fraud.
        </SectionTitle>

        <div className="mt-10 grid grid-cols-1 gap-4 md:grid-cols-[1fr_1fr]">
          <Card>
            <CardHeader className="border-b border-border/60 pb-3">
              <CardTitle className="text-[11px] uppercase tracking-widest text-muted-foreground">
                01 · prerequisites
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="space-y-2 text-sm text-muted-foreground">
                {[
                  "JDK 25 (Spring Boot 4 baseline)",
                  "Maven 3.9+",
                  "Docker Desktop",
                  "uv (Python toolchain — for the M5 agent)",
                  "Node 20+ (for the dashboard + this site)",
                ].map((p) => (
                  <li key={p} className="flex items-start gap-2">
                    <span className="mt-2 size-1 rounded-full bg-foreground/40" />
                    <span data-body="prose">{p}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>

          <Card className="bg-card/60">
            <CardHeader className="border-b border-border/60 pb-3">
              <CardTitle className="text-[11px] uppercase tracking-widest text-muted-foreground">
                02 · commands
              </CardTitle>
            </CardHeader>
            <CardContent className="font-mono text-[12.5px] leading-relaxed">
              <Line muted># clone and configure</Line>
              <Line prompt prefix="$">git clone {REPO} && cd conclave</Line>
              <Line prompt prefix="$">cp .env.example .env</Line>
              <Line muted># fill ANTHROPIC_API_KEY for the default judge</Line>
              <div className="my-2 h-px bg-border/60" />
              <Line muted># boot the full stack + emit a starter burst</Line>
              <Line prompt prefix="$">make demo-fraud</Line>
              <Line prompt prefix="$">open http://localhost:8080/api/v1/decisions</Line>
              <div className="my-2 h-px bg-border/60" />
              <Line muted># same images, security domain</Line>
              <Line prompt prefix="$">make demo-stop && make demo-security</Line>
            </CardContent>
          </Card>
        </div>

        <Separator className="my-10" />

        <div className="grid grid-cols-1 gap-6 md:grid-cols-[1fr_auto]">
          <p data-body="prose" className="text-sm text-muted-foreground">
            <span className="text-foreground">No Anthropic key?</span>{" "}
            <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground">
              make demo-fraud-local
            </span>{" "}
            boots an Ollama sidecar with{" "}
            <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground">
              qwen3:8b
            </span>{" "}
            (~6&nbsp;GB cached on first run). The same compose stack, the same
            decisions API — only the judge backend swaps.
          </p>
          <Button size="lg" variant="outline" asChild>
            <a href={REPO} target="_blank" rel="noopener">
              <Github />
              Browse the source
              <ArrowUpRight />
            </a>
          </Button>
        </div>
      </div>
    </section>
  );
}

/* ────────────────────────────────────────────────────────────── footer */

function Footer() {
  return (
    <footer className="bg-background">
      <div className="mx-auto flex max-w-6xl flex-col gap-4 px-6 py-10 md:flex-row md:items-center md:justify-between">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Logo />
          <span>CONCLAVE · MIT license</span>
        </div>
        <div className="flex items-center gap-4 text-xs text-muted-foreground">
          <a href="#architecture" className="hover:text-foreground">
            Architecture
          </a>
          <a href="#benchmarks" className="hover:text-foreground">
            Benchmarks
          </a>
          <a href={REPO} target="_blank" rel="noopener" className="hover:text-foreground">
            GitHub ↗
          </a>
        </div>
      </div>
    </footer>
  );
}

/* ────────────────────────────────────────────────────────────── section helpers */

function SectionEyebrow({ tag }: { tag: string }) {
  return (
    <Badge
      variant="outline"
      className="border-foreground/15 text-[10px] uppercase tracking-widest text-muted-foreground"
    >
      {tag}
    </Badge>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="mt-4 max-w-3xl font-heading text-balance text-3xl leading-[1.1] tracking-tight md:text-5xl">
      {children}
    </h2>
  );
}
