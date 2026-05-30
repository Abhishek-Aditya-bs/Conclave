import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ArrowLeft, Loader2, RotateCw, TriangleAlert } from "lucide-react";
import { format, parseISO } from "date-fns";
import { useDecision, useReplayDecision } from "../hooks/useDecisions";
import { Card, CardBody, CardHeader, CardTitle } from "../components/ui/Card";
import { Button } from "../components/ui/Button";
import { ScoreBadge } from "../components/ScoreBadge";
import { VerdictPill } from "../components/VerdictPill";
import { ContributingFactors } from "../components/ContributingFactors";
import { JsonBlock } from "../components/JsonBlock";
import { VerdictMarkdown } from "../components/VerdictMarkdown";
import type { DecisionDetail } from "../lib/types";

export function DecisionDetailPage() {
  const { decisionId } = useParams<{ decisionId: string }>();
  const { data, isLoading, error } = useDecision(decisionId);
  const replay = useReplayDecision();
  const [replayed, setReplayed] = useState<DecisionDetail | null>(null);

  const handleReplay = () => {
    if (!decisionId) return;
    replay.mutate(decisionId, { onSuccess: (fresh) => setReplayed(fresh) });
  };

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-[color:var(--color-fg-subtle)]">
        <Loader2 className="h-4 w-4 animate-spin" /> loading decision…
      </div>
    );
  }

  if (error) {
    return (
      <Card>
        <CardBody>
          <div className="flex items-start gap-2 text-sm text-red-400">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <p className="font-medium">decision not found</p>
              <p className="mt-1 text-xs text-[color:var(--color-fg-muted)]">
                {(error as Error).message}
              </p>
            </div>
          </div>
        </CardBody>
      </Card>
    );
  }

  if (!data) return null;

  const factors = data.contributing_factors ?? [];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <Link
          to="/"
          className="inline-flex items-center gap-1 text-xs text-[color:var(--color-fg-muted)] hover:text-[color:var(--color-fg)]"
        >
          <ArrowLeft className="h-3 w-3" /> back to list
        </Link>
        <Button onClick={handleReplay} disabled={replay.isPending} size="sm">
          <RotateCw className={`h-3 w-3 ${replay.isPending ? "animate-spin" : ""}`} />
          {replay.isPending ? "replaying…" : "Replay deliberation"}
        </Button>
      </div>

      <DecisionView decision={data} title="Persisted decision" />

      {replayed ? (
        <Card>
          <CardHeader>
            <CardTitle>
              Replay result{" "}
              <span className="ml-2 font-mono text-[10px] font-normal uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
                fresh — not persisted
              </span>
            </CardTitle>
          </CardHeader>
          <CardBody>
            <DecisionView decision={replayed} embedded title={null} />
          </CardBody>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Contributing factors ({factors.length})</CardTitle>
        </CardHeader>
        <CardBody>
          <ContributingFactors factors={factors} />
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Enriched event (consumed by the judge)</CardTitle>
        </CardHeader>
        <CardBody>
          <JsonBlock payload={data.enriched_event_json} />
        </CardBody>
      </Card>
    </div>
  );
}

interface ViewProps {
  decision: DecisionDetail;
  title?: string | null;
  embedded?: boolean;
}

function DecisionView({ decision, title = "Persisted decision", embedded }: ViewProps) {
  const created = parseISO(decision.created_at);
  const body = (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-x-6 gap-y-3 md:grid-cols-4">
        <Meta label="Verdict">
          <div className="flex items-center gap-2">
            <ScoreBadge score={decision.score} />
            <VerdictPill label={decision.verdict_label} />
          </div>
        </Meta>
        <Meta label="Domain">
          <span className="font-mono text-sm">{decision.domain}</span>
        </Meta>
        <Meta label="Entity">
          <span className="font-mono text-xs">{decision.baseline_entity_id ?? "—"}</span>
        </Meta>
        <Meta label="Latency">
          <span className="font-mono text-sm">{decision.latency_ms}ms</span>
        </Meta>
        <Meta label="Judge">
          <span className="font-mono text-xs">
            {decision.judge_provider} · {decision.judge_model}
          </span>
        </Meta>
        <Meta label="Decision id">
          <span className="font-mono text-xs text-[color:var(--color-fg-subtle)]">
            {decision.decision_id}
          </span>
        </Meta>
        <Meta label="Event id">
          <span className="font-mono text-xs text-[color:var(--color-fg-subtle)]">
            {decision.event_id}
          </span>
        </Meta>
        <Meta label="When">
          <span className="font-mono text-xs">
            {format(created, "yyyy-MM-dd HH:mm:ss")}
          </span>
        </Meta>
      </div>
      <div>
        <h3 className="mb-2 text-[10px] font-medium uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
          Judge verdict
        </h3>
        <VerdictMarkdown text={decision.verdict_explanation_md ?? ""} />
      </div>
    </div>
  );

  if (embedded) return body;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardBody>{body}</CardBody>
    </Card>
  );
}

function Meta({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="mb-1 text-[10px] font-medium uppercase tracking-wider text-[color:var(--color-fg-subtle)]">
        {label}
      </div>
      <div>{children}</div>
    </div>
  );
}
