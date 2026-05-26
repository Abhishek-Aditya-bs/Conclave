import type { ChangeEvent } from "react";
import { Label, Select, TextInput } from "./ui/Field";
import { Button } from "./ui/Button";
import type { DecisionFilters } from "../lib/types";
import { X } from "lucide-react";

interface Props {
  value: DecisionFilters;
  onChange: (next: DecisionFilters) => void;
}

const update = <K extends keyof DecisionFilters>(
  value: DecisionFilters,
  onChange: (next: DecisionFilters) => void,
  key: K
) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
  const raw = e.target.value;
  const next = { ...value };
  if (raw === "" || raw === undefined) {
    delete next[key];
  } else if (key === "min_score" || key === "max_score") {
    next[key] = Number(raw) as DecisionFilters[K];
  } else {
    next[key] = raw as DecisionFilters[K];
  }
  // Reset pagination when a real filter changes.
  if (key !== "limit" && key !== "offset") {
    next.offset = 0;
  }
  onChange(next);
};

export function FilterBar({ value, onChange }: Props) {
  const hasFilters = Object.keys(value).some(
    (k) => k !== "limit" && k !== "offset" && value[k as keyof DecisionFilters] !== undefined
  );

  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-6">
      <div className="col-span-1">
        <Label>Domain</Label>
        <Select value={value.domain ?? ""} onChange={update(value, onChange, "domain")}>
          <option value="">All</option>
          <option value="fraud">fraud</option>
          <option value="security">security</option>
        </Select>
      </div>
      <div>
        <Label>Verdict</Label>
        <Select
          value={value.verdict_label ?? ""}
          onChange={update(value, onChange, "verdict_label")}
        >
          <option value="">All</option>
          <option value="ALLOW">ALLOW</option>
          <option value="REVIEW">REVIEW</option>
          <option value="BLOCK">BLOCK</option>
        </Select>
      </div>
      <div>
        <Label>Min score</Label>
        <TextInput
          type="number"
          step="0.01"
          min="0"
          max="1"
          placeholder="0.00"
          value={value.min_score ?? ""}
          onChange={update(value, onChange, "min_score")}
        />
      </div>
      <div>
        <Label>Max score</Label>
        <TextInput
          type="number"
          step="0.01"
          min="0"
          max="1"
          placeholder="1.00"
          value={value.max_score ?? ""}
          onChange={update(value, onChange, "max_score")}
        />
      </div>
      <div>
        <Label>Entity</Label>
        <TextInput
          placeholder="ch_… or user_…"
          value={value.baseline_entity_id ?? ""}
          onChange={update(value, onChange, "baseline_entity_id")}
        />
      </div>
      <div className="flex items-end">
        {hasFilters ? (
          <Button
            size="sm"
            variant="ghost"
            onClick={() => onChange({ limit: value.limit, offset: 0 })}
          >
            <X className="h-3 w-3" />
            Clear
          </Button>
        ) : null}
      </div>
    </div>
  );
}
