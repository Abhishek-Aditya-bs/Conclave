import { Activity, ArrowRight, Github, Layers, ShieldCheck } from "lucide-react";

export default function App() {
  return (
    <div className="min-h-full bg-[color:var(--color-bg)]">
      <Nav />
      <Hero />
      <Problem />
      <HowItWorks />
      <Configs />
      <Benchmarks />
      <Quickstart />
      <Footer />
    </div>
  );
}

function Nav() {
  return (
    <nav className="sticky top-0 z-10 border-b border-[color:var(--color-border)] bg-[color:var(--color-bg)]/80 backdrop-blur">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
        <a href="/" className="flex items-center gap-2 text-sm font-semibold">
          <Activity className="h-4 w-4" />
          CONCLAVE
        </a>
        <div className="flex items-center gap-5 text-xs text-[color:var(--color-fg-muted)]">
          <a href="#problem" className="hover:text-[color:var(--color-fg)]">Problem</a>
          <a href="#how" className="hover:text-[color:var(--color-fg)]">How it works</a>
          <a href="#quickstart" className="hover:text-[color:var(--color-fg)]">Quickstart</a>
          <a
            href="https://github.com/anthropics/conclave"
            target="_blank"
            rel="noopener"
            className="inline-flex items-center gap-1 hover:text-[color:var(--color-fg)]"
          >
            <Github className="h-3 w-3" />
            github
          </a>
        </div>
      </div>
    </nav>
  );
}

function Hero() {
  return (
    <section className="mx-auto max-w-6xl px-6 py-24 md:py-32">
      <p className="mb-4 font-mono text-xs uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
        Multi-agent · Real-time · Explainable
      </p>
      <h1 className="text-4xl font-semibold tracking-tight md:text-6xl">
        Multi-agent risk detection.
        <br />
        <span className="text-[color:var(--color-fg-muted)]">
          One architecture, multiple domains.
        </span>
      </h1>
      <p className="mt-6 max-w-2xl text-base text-[color:var(--color-fg-muted)] md:text-lg">
        Four specialized agents deliberate over every event and emit a calibrated risk
        score with a natural-language verdict — fraud-grade for payments, SOC-grade for
        security, one architecture.
      </p>
      <div className="mt-10 flex flex-wrap items-center gap-3">
        <a
          href="#quickstart"
          className="inline-flex items-center gap-2 rounded-md bg-[color:var(--color-fg)] px-4 py-2 text-sm font-medium text-[color:var(--color-bg)] hover:bg-[color:var(--color-fg-muted)]"
        >
          Quickstart
          <ArrowRight className="h-3 w-3" />
        </a>
        <a
          href="https://github.com/anthropics/conclave"
          target="_blank"
          rel="noopener"
          className="inline-flex items-center gap-2 rounded-md border border-[color:var(--color-border)] px-4 py-2 text-sm text-[color:var(--color-fg-muted)] hover:text-[color:var(--color-fg)]"
        >
          <Github className="h-3 w-3" /> View source
        </a>
      </div>
    </section>
  );
}

function Problem() {
  return (
    <Section id="problem" eyebrow="The problem" title="Per-domain pipelines repeat the same plumbing.">
      <div className="grid grid-cols-1 gap-8 md:grid-cols-2">
        <Card>
          <h3 className="text-base font-semibold">Tabular models are accurate but opaque.</h3>
          <p className="mt-2 text-sm text-[color:var(--color-fg-muted)]">
            Gradient-boosted trees give you a score and a feature-importance vector —
            not a verdict you can hand to a reviewer or to an audit log.
          </p>
        </Card>
        <Card>
          <h3 className="text-base font-semibold">Every domain reimplements the streaming layer.</h3>
          <p className="mt-2 text-sm text-[color:var(--color-fg-muted)]">
            Payment fraud, account abuse, and SOC anomaly detection all use the same
            Kafka + features + decision shape — but ship as three teams' worth of code.
          </p>
        </Card>
      </div>
    </Section>
  );
}

function HowItWorks() {
  return (
    <Section
      id="how"
      eyebrow="How it works"
      title="Four agents deliberate over every event."
    >
      <ol className="grid grid-cols-1 gap-4 md:grid-cols-4">
        {[
          {
            n: 1,
            t: "Feature Extractor",
            d: "Summarises the enriched event into structured evidence for the deliberation.",
          },
          {
            n: 2,
            t: "Behavioral Baseliner",
            d: "pgvector embedding of the entity's 90-day history. Diff vs current event → drift score.",
          },
          {
            n: 3,
            t: "Graph Reasoner",
            d: "Neo4j templates: card-testing rings, lateral movement, privileged access — depth-bounded.",
          },
          {
            n: 4,
            t: "Judge",
            d: "Claude Haiku 4.5 (or Ollama). Reads the evidence package, emits score + verdict + factors.",
          },
        ].map((s) => (
          <li
            key={s.n}
            className="rounded-lg border border-[color:var(--color-border)] bg-[color:var(--color-bg-soft)] p-5"
          >
            <div className="mb-3 inline-flex h-7 w-7 items-center justify-center rounded-full bg-[color:var(--color-bg)] font-mono text-xs">
              {s.n}
            </div>
            <h3 className="text-sm font-semibold">{s.t}</h3>
            <p className="mt-2 text-xs text-[color:var(--color-fg-muted)]">{s.d}</p>
          </li>
        ))}
      </ol>
      <p className="mt-6 text-xs text-[color:var(--color-fg-subtle)]">
        Baseliner ∥ Graph Reasoner run in parallel; Judge consumes both. End-to-end p99
        target: 600ms.
      </p>
    </Section>
  );
}

function Configs() {
  return (
    <Section
      id="configs"
      eyebrow="Two reference configs"
      title="Same architecture. Two domains. Switch by env var."
    >
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        <ConfigCard
          icon={<Layers className="h-4 w-4" />}
          title="Payment fraud"
          subtitle="Stripe-Radar style"
          bullets={[
            "PaymentEvent: card-not-present transactions",
            "Cardholder · device · IP graph",
            "Detects card-testing rings, ATO, bust-out fraud",
          ]}
          command="make demo-fraud"
        />
        <ConfigCard
          icon={<ShieldCheck className="h-4 w-4" />}
          title="Security / SOC"
          subtitle="Lateral-movement style"
          bullets={[
            "AuthEvent: logins · API key use · privileged access",
            "Identity · host · resource graph",
            "Detects lateral movement, exfiltration, ATO",
          ]}
          command="make demo-security"
        />
      </div>
    </Section>
  );
}

function ConfigCard({
  icon,
  title,
  subtitle,
  bullets,
  command,
}: {
  icon: React.ReactNode;
  title: string;
  subtitle: string;
  bullets: string[];
  command: string;
}) {
  return (
    <div className="rounded-lg border border-[color:var(--color-border)] bg-[color:var(--color-bg-soft)] p-6">
      <div className="mb-1 flex items-center gap-2 text-[color:var(--color-fg-muted)]">
        {icon}
        <span className="font-mono text-[10px] uppercase tracking-wider">{subtitle}</span>
      </div>
      <h3 className="text-lg font-semibold">{title}</h3>
      <ul className="mt-4 space-y-2 text-sm text-[color:var(--color-fg-muted)]">
        {bullets.map((b) => (
          <li key={b} className="flex items-start gap-2">
            <span className="mt-1.5 inline-block h-1 w-1 rounded-full bg-[color:var(--color-fg-subtle)]" />
            {b}
          </li>
        ))}
      </ul>
      <pre className="mt-5 rounded border border-[color:var(--color-border)] bg-[color:var(--color-bg)] px-3 py-2 font-mono text-xs text-[color:var(--color-fg)]">
        $ {command}
      </pre>
    </div>
  );
}

function Benchmarks() {
  return (
    <Section
      id="benchmarks"
      eyebrow="Benchmarks"
      title="Latency budgets met across the pipeline."
    >
      <div className="overflow-hidden rounded-lg border border-[color:var(--color-border)]">
        <table className="w-full text-sm">
          <thead className="bg-[color:var(--color-bg-soft)] text-left text-[10px] uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
            <tr>
              <th className="px-4 py-2.5 font-medium">Component</th>
              <th className="px-4 py-2.5 font-medium">Budget</th>
              <th className="px-4 py-2.5 font-medium">Measured (p99)</th>
              <th className="px-4 py-2.5 font-medium">Headroom</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[color:var(--color-border)] font-mono text-xs">
            <Row name="M3 baseline lookup" budget="20 ms" measured="0.74 ms" head="27×" />
            <Row name="M4 graph query (100K-edge)" budget="50 ms" measured="6 ms" head="8×" />
            <Row name="M5 deliberation (Haiku)" budget="600 ms" measured="—" head="—" muted />
            <Row name="M6 end-to-end orchestration" budget="750 ms" measured="—" head="—" muted />
          </tbody>
        </table>
      </div>
      <p className="mt-4 text-xs text-[color:var(--color-fg-subtle)]">
        AUC, precision@FPR=1%, and human-eval verdict ratings land with the benchmark
        pipeline (post-M10). Numbers shown are from Apple M3 hardware under the included
        Testcontainers integration tests.
      </p>
    </Section>
  );
}

function Row({
  name,
  budget,
  measured,
  head,
  muted,
}: {
  name: string;
  budget: string;
  measured: string;
  head: string;
  muted?: boolean;
}) {
  return (
    <tr className={muted ? "text-[color:var(--color-fg-subtle)]" : "text-[color:var(--color-fg-muted)]"}>
      <td className="px-4 py-2.5">{name}</td>
      <td className="px-4 py-2.5">{budget}</td>
      <td className="px-4 py-2.5">{measured}</td>
      <td className="px-4 py-2.5">{head}</td>
    </tr>
  );
}

function Quickstart() {
  return (
    <Section id="quickstart" eyebrow="Quickstart" title="git clone, make demo-fraud.">
      <pre className="overflow-x-auto rounded-lg border border-[color:var(--color-border)] bg-[color:var(--color-bg-soft)] p-5 font-mono text-xs text-[color:var(--color-fg-muted)]">
{`# prerequisites: JDK 25, Maven 3.9+, Docker, uv (for the Python agent)
git clone https://github.com/anthropics/conclave
cd conclave

cp .env.example .env             # fill in ANTHROPIC_API_KEY for the default judge

make demo-fraud                  # boots the full stack + emits a starter event burst
open http://localhost:8080/api/v1/decisions

make demo-stop
make demo-security               # same images, different SPRING_PROFILES_ACTIVE`}
      </pre>
      <p className="mt-4 text-xs text-[color:var(--color-fg-subtle)]">
        Want to run without an Anthropic key? <code className="font-mono">make demo-fraud-local</code>{" "}
        boots an Ollama sidecar with qwen3:8b.
      </p>
    </Section>
  );
}

function Footer() {
  return (
    <footer className="mt-20 border-t border-[color:var(--color-border)]">
      <div className="mx-auto max-w-6xl px-6 py-8 text-center text-xs text-[color:var(--color-fg-subtle)]">
        <p>
          Built by Abhishek Aditya · MIT license · See spec.md for the full architecture
          contract.
        </p>
      </div>
    </footer>
  );
}

function Section({
  id,
  eyebrow,
  title,
  children,
}: {
  id?: string;
  eyebrow: string;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} className="border-t border-[color:var(--color-border)]">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <p className="font-mono text-[10px] uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
          {eyebrow}
        </p>
        <h2 className="mt-2 text-2xl font-semibold tracking-tight md:text-3xl">{title}</h2>
        <div className="mt-8">{children}</div>
      </div>
    </section>
  );
}

function Card({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-[color:var(--color-border)] bg-[color:var(--color-bg-soft)] p-6">
      {children}
    </div>
  );
}
