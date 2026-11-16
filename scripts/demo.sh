#!/usr/bin/env bash
# POSTs the SAME external_id twice and shows that only one row exists.
# Usage: start the app (mvn spring-boot:run), then: bash scripts/demo.sh
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
EXT="demo-duplicate-$(date +%s)"
BODY="{\"type\":\"navan_fee\",\"external_id\":\"$EXT\",\"currency\":\"EUR\",\"occurred_at\":\"2026-03-11T06:40:00Z\",\"total\":25.00,\"tax_lines\":[],\"fee_lines\":[],\"metadata\":{\"fee_kind\":\"trip_fee\"}}"

echo "== POST #1 (expect 201)"
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d "$BODY"

echo
echo "== POST #2, same external_id (expect 409 + existing_id)"
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/transactions" -H 'Content-Type: application/json' -d "$BODY"

echo
echo "== rows stored with external_id=$EXT (expect 1)"
curl -s "$BASE/transactions?limit=100" | grep -o "\"external_id\":\"$EXT\"" | wc -l
