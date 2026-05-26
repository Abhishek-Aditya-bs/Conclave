import { Link, Route, Routes } from "react-router-dom";
import { Activity, GitBranch } from "lucide-react";
import { DecisionsPage } from "./pages/DecisionsPage";
import { DecisionDetailPage } from "./pages/DecisionDetailPage";

export default function App() {
  return (
    <div className="min-h-full">
      <Header />
      <main className="mx-auto max-w-7xl px-4 py-6 md:px-8">
        <Routes>
          <Route path="/" element={<DecisionsPage />} />
          <Route path="/decisions/:decisionId" element={<DecisionDetailPage />} />
        </Routes>
      </main>
      <Footer />
    </div>
  );
}

function Header() {
  return (
    <header className="sticky top-0 z-10 border-b border-[color:var(--color-border)] bg-[color:var(--color-bg)]/80 backdrop-blur">
      <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3 md:px-8">
        <Link to="/" className="flex items-center gap-2">
          <Activity className="h-4 w-4 text-[color:var(--color-fg)]" />
          <span className="text-sm font-semibold tracking-tight">CONCLAVE</span>
          <span className="ml-1 rounded bg-[color:var(--color-bg-soft)] px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
            audit
          </span>
        </Link>
        <div className="flex items-center gap-3 text-xs text-[color:var(--color-fg-muted)]">
          <a
            href="https://github.com/anthropics/conclave"
            target="_blank"
            rel="noopener"
            className="inline-flex items-center gap-1 hover:text-[color:var(--color-fg)]"
          >
            <GitBranch className="h-3 w-3" />
            repo
          </a>
        </div>
      </div>
    </header>
  );
}

function Footer() {
  return (
    <footer className="mt-12 border-t border-[color:var(--color-border)] py-4 text-center text-[10px] text-[color:var(--color-fg-subtle)]">
      <p>
        CONCLAVE — multi-agent real-time risk detection. M7 API contract:{" "}
        <code className="font-mono">localhost:8080/api/v1/decisions</code>
      </p>
    </footer>
  );
}
