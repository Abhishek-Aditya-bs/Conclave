import type {
  ApiError,
  DecisionDetail,
  DecisionFilters,
  DecisionPage,
} from "./types";

// In dev, Vite proxies /api → http://localhost:8080. In prod (Cloudflare
// Pages deploy), set VITE_API_BASE_URL to the orchestrator's hostname.
const BASE = import.meta.env.VITE_API_BASE_URL ?? "";

const HEADERS = { "content-type": "application/json" };

class ApiException extends Error {
  constructor(public readonly status: number, public readonly body: ApiError) {
    super(body.message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init);
  if (!res.ok) {
    let body: ApiError;
    try {
      body = (await res.json()) as ApiError;
    } catch {
      body = { code: "internal", message: `HTTP ${res.status}` };
    }
    throw new ApiException(res.status, body);
  }
  // 204 has no body
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export function listDecisions(filters: DecisionFilters): Promise<DecisionPage> {
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(filters)) {
    if (v === undefined || v === null || v === "") continue;
    params.set(k, String(v));
  }
  const qs = params.toString();
  return request<DecisionPage>(`/api/v1/decisions${qs ? `?${qs}` : ""}`);
}

export function getDecision(decisionId: string): Promise<DecisionDetail> {
  return request<DecisionDetail>(`/api/v1/decisions/${decisionId}`);
}

export function replayDecision(decisionId: string): Promise<DecisionDetail> {
  return request<DecisionDetail>(`/api/v1/decisions/${decisionId}/replay`, {
    method: "POST",
    headers: HEADERS,
  });
}

export { ApiException };
