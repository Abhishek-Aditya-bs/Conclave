import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getDecision, listDecisions, replayDecision } from "../lib/api";
import type { DecisionFilters } from "../lib/types";

export function useDecisions(filters: DecisionFilters) {
  return useQuery({
    queryKey: ["decisions", filters],
    queryFn: () => listDecisions(filters),
    // Inherit refetchInterval=5000 from QueryClientProvider in main.tsx.
  });
}

export function useDecision(decisionId: string | undefined) {
  return useQuery({
    queryKey: ["decision", decisionId],
    queryFn: () => getDecision(decisionId!),
    enabled: !!decisionId,
    refetchInterval: false,
    staleTime: 60_000,
  });
}

export function useReplayDecision() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (decisionId: string) => replayDecision(decisionId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["decisions"] }),
  });
}
