import { useState } from "react";
import { ChevronLeft, ChevronRight, RefreshCw } from "lucide-react";
import { useDecisions } from "../hooks/useDecisions";
import type { DecisionFilters } from "../lib/types";
import { Card, CardBody, CardHeader, CardTitle } from "../components/ui/Card";
import { Button } from "../components/ui/Button";
import { FilterBar } from "../components/FilterBar";
import { DecisionTable } from "../components/DecisionTable";
import { ScoreHistogram } from "../components/ScoreHistogram";

export function DecisionsPage() {
  const [filters, setFilters] = useState<DecisionFilters>({ limit: 25, offset: 0 });
  const { data, isLoading, isFetching, refetch } = useDecisions(filters);

  const limit = filters.limit ?? 25;
  const offset = filters.offset ?? 0;
  const total = data?.total ?? 0;
  const showing = data?.items.length ?? 0;
  const start = total === 0 ? 0 : offset + 1;
  const end = offset + showing;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        <Card className="md:col-span-2">
          <CardHeader>
            <CardTitle>Filters</CardTitle>
          </CardHeader>
          <CardBody>
            <FilterBar value={filters} onChange={setFilters} />
          </CardBody>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Score distribution (this page)</CardTitle>
          </CardHeader>
          <CardBody>
            <ScoreHistogram decisions={data?.items ?? []} />
          </CardBody>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex items-center justify-between">
          <CardTitle>
            Decisions{" "}
            <span className="ml-2 font-mono text-xs font-normal text-[color:var(--color-fg-subtle)]">
              {start}–{end} of {total}
            </span>
          </CardTitle>
          <div className="flex items-center gap-1">
            <Button size="sm" variant="ghost" onClick={() => refetch()} aria-label="Refresh">
              <RefreshCw className={`h-3 w-3 ${isFetching ? "animate-spin" : ""}`} />
              <span className="sr-only">Refresh</span>
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={offset === 0}
              onClick={() =>
                setFilters({ ...filters, offset: Math.max(0, offset - limit) })
              }
              aria-label="Previous page"
            >
              <ChevronLeft className="h-3 w-3" />
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={end >= total}
              onClick={() => setFilters({ ...filters, offset: offset + limit })}
              aria-label="Next page"
            >
              <ChevronRight className="h-3 w-3" />
            </Button>
          </div>
        </CardHeader>
        <DecisionTable items={data?.items ?? []} isLoading={isLoading} />
      </Card>
    </div>
  );
}
