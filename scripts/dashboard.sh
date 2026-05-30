#!/usr/bin/env bash
# Run the audit dashboard dev server (Vite) on http://localhost:5173.
# It proxies /api → the orchestrator on :8080, so bring the stack up first.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

cd dashboard
[ -d node_modules ] || { info "Installing dashboard deps…"; npm install --no-audit --no-fund; }
info "Dashboard dev server → http://localhost:5173  (Ctrl-C to stop)"
exec npm run dev
