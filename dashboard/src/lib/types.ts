// Wire types matching the M7 audit API (snake_case via Jackson SNAKE_CASE).
// Source of truth: orchestrator/src/main/java/io/conclave/audit/*.java
// + ADR-006 (docs/adr/0006-audit-api-surface.md).

export type Domain = "fraud" | "security";

export type VerdictLabel = "ALLOW" | "REVIEW" | "BLOCK";

export interface ContributingFactor {
  name: string;
  /** Signed weight in [-1, 1]. Positive nudges BLOCK, negative ALLOW. */
  weight: number;
  evidence: string;
}

export interface DecisionSummary {
  decision_id: string;
  event_id: string;
  domain: Domain;
  baseline_entity_id: string;
  score: number;
  verdict_label: VerdictLabel;
  latency_ms: number;
  judge_provider: string;
  judge_model: string;
  created_at: string;
}

export interface DecisionDetail extends DecisionSummary {
  verdict_explanation_md: string;
  contributing_factors: ContributingFactor[];
  enriched_event_json: string;
}

export interface DecisionPage {
  items: DecisionSummary[];
  total: number;
  limit: number;
  offset: number;
}

export interface ApiError {
  code: "decision_not_found" | "invalid_argument" | "internal";
  message: string;
}

export interface DecisionFilters {
  domain?: Domain;
  verdict_label?: VerdictLabel;
  baseline_entity_id?: string;
  min_score?: number;
  max_score?: number;
  since?: string;
  until?: string;
  judge_provider?: string;
  limit?: number;
  offset?: number;
}
