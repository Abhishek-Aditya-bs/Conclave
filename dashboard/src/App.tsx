import { Link, Route, Routes } from "react-router-dom";
import { Github } from "lucide-react";
import { DecisionsPage } from "./pages/DecisionsPage";
import { DecisionDetailPage } from "./pages/DecisionDetailPage";
import { API_BASE } from "./lib/api";

const REPO = "https://github.com/anthropics/conclave";

export default function App() {
  return (
    <div className="relative min-h-full bg-background text-foreground">
      {/* Subtle grid + glow at the top, mirroring the marketing-site hero. */}
      <div aria-hidden className="pointer-events-none absolute inset-x-0 top-0 h-[420px] overflow-hidden">
        <div className="bg-grid bg-grid-fade absolute inset-0" />
        <div className="glow absolute inset-x-0 top-0 h-[320px]" />
      </div>

      <div className="relative">
        <Header />
        <main className="mx-auto max-w-7xl px-4 py-6 md:px-8">
          <Routes>
            <Route path="/" element={<DecisionsPage />} />
            <Route path="/decisions/:decisionId" element={<DecisionDetailPage />} />
          </Routes>
        </main>
        <Footer />
      </div>
    </div>
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

function Header() {
  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-background/70 backdrop-blur-xl">
      <div className="mx-auto flex max-w-7xl items-center justify-between gap-3 px-4 py-3 md:px-8">
        <Link to="/" className="flex min-w-0 items-center gap-2 text-sm">
          <Logo />
          <span className="font-medium tracking-tight">CONCLAVE</span>
          <span className="ml-1 rounded-md border border-border px-1.5 py-0.5 text-[10px] uppercase tracking-wider text-muted-foreground">
            audit
          </span>
        </Link>
        <a
          href={REPO}
          target="_blank"
          rel="noopener"
          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <Github className="h-3.5 w-3.5" />
          <span className="hidden sm:inline">GitHub</span>
        </a>
      </div>
    </header>
  );
}

function Footer() {
  return (
    <footer className="mt-12 border-t border-border/60 py-5 text-center text-[10px] text-muted-foreground">
      <p>
        CONCLAVE — multi-agent real-time risk detection. API contract:{" "}
        <code className="font-mono text-foreground/80">{`${API_BASE}/api/v1/decisions`}</code>
      </p>
    </footer>
  );
}
