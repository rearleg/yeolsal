#!/usr/bin/env bash
# verify-v11.sh — Story 1.4 post-deploy sanity check for the V11
# migration. Five invariants must hold; exits 0 on full pass, 1 on
# any failure. Safe to run multiple times.
#
# Connection modes (priority order):
#   1. PGURL=postgresql://user:pass@host:port/db  — direct psql.
#   2. Default: docker compose exec -T postgres psql -U yeosal yeosal.
#
# Run from anywhere; the script does not depend on its own CWD.
#
# See infra/RUNBOOK-V11.md for context.

set -euo pipefail

VERBOSE=0
SHOW_HELP=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        -v|--verbose) VERBOSE=1; shift ;;
        -h|--help)    SHOW_HELP=1; shift ;;
        --)           shift; break ;;
        *)
            printf '[verify-v11] unknown argument: %s\n' "$1" >&2
            SHOW_HELP=1
            shift
            ;;
    esac
done

if [[ "$SHOW_HELP" -eq 1 ]]; then
    cat <<'EOF'
Usage: verify-v11.sh [--verbose] [--help]

Runs 5 sanity checks against the yeolsal Postgres after the V11
migration deploy. Prints PASS/FAIL per check and exits with code 0 if
all pass, 1 otherwise.

Connection modes:
  - PGURL=postgresql://user:pass@host:port/db ./verify-v11.sh
  - ./verify-v11.sh   # defaults to `docker compose exec -T postgres
                      #              psql -U yeosal yeosal`

Flags:
  -v, --verbose   Also print the actual counts (not just PASS/FAIL).
  -h, --help      Show this help and exit.

Checks:
  1. flyway_schema_history has a successful row for V11.
  2. survival_state row count == room_members row count.
  3. room_rule_versions row count == rooms row count.
  4. room_point_pool row count == rooms row count.
  5. users.free_revival_ticket_used is non-null for every user.

See infra/RUNBOOK-V11.md for context and rollback guidance.
EOF
    exit 0
fi

# ----- psql wrapper -----

run_sql() {
    # $1: SQL. Returns trimmed stdout from psql (-A -t = unaligned + tuples-only).
    local sql=$1
    local out
    if [[ -n "${PGURL:-}" ]]; then
        out=$(psql "$PGURL" -A -t -c "$sql")
    else
        out=$(docker compose exec -T postgres \
                  psql -U yeosal yeosal -A -t -c "$sql")
    fi
    # Strip surrounding whitespace / newlines.
    printf '%s' "${out//$'\n'/ }" | awk '{$1=$1; print}'
}

PASS_COUNT=0
FAIL_COUNT=0

report() {
    # $1: PASS|FAIL, $2: label, $3: optional verbose detail.
    local status=$1 label=$2 detail=${3:-}
    if [[ "$status" == "PASS" ]]; then
        PASS_COUNT=$((PASS_COUNT + 1))
        printf '[PASS] %s' "$label"
    else
        FAIL_COUNT=$((FAIL_COUNT + 1))
        printf '[FAIL] %s' "$label"
    fi
    if [[ "$VERBOSE" -eq 1 && -n "$detail" ]]; then
        printf ' — %s' "$detail"
    fi
    printf '\n'
}

# ----- 1. flyway_schema_history success row for V11 -----

FLYWAY_OK=$(run_sql "SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success = true")
if [[ "$FLYWAY_OK" == "1" ]]; then
    report PASS "flyway_schema_history V11 success row" "count=$FLYWAY_OK"
else
    report FAIL "flyway_schema_history V11 success row" "expected 1, got '$FLYWAY_OK'"
fi

# ----- 2. survival_state count == room_members count -----

SS_COUNT=$(run_sql "SELECT count(*) FROM survival_state")
RM_COUNT=$(run_sql "SELECT count(*) FROM room_members")
if [[ "$SS_COUNT" == "$RM_COUNT" ]]; then
    report PASS "survival_state count == room_members count" \
           "survival_state=$SS_COUNT, room_members=$RM_COUNT"
else
    report FAIL "survival_state count == room_members count" \
           "survival_state=$SS_COUNT, room_members=$RM_COUNT"
fi

# ----- 3. room_rule_versions count == rooms count -----

RRV_COUNT=$(run_sql "SELECT count(*) FROM room_rule_versions")
ROOMS_COUNT=$(run_sql "SELECT count(*) FROM rooms")
if [[ "$RRV_COUNT" == "$ROOMS_COUNT" ]]; then
    report PASS "room_rule_versions count == rooms count" \
           "room_rule_versions=$RRV_COUNT, rooms=$ROOMS_COUNT"
else
    report FAIL "room_rule_versions count == rooms count" \
           "room_rule_versions=$RRV_COUNT, rooms=$ROOMS_COUNT"
fi

# ----- 4. room_point_pool count == rooms count -----

RPP_COUNT=$(run_sql "SELECT count(*) FROM room_point_pool")
if [[ "$RPP_COUNT" == "$ROOMS_COUNT" ]]; then
    report PASS "room_point_pool count == rooms count" \
           "room_point_pool=$RPP_COUNT, rooms=$ROOMS_COUNT"
else
    report FAIL "room_point_pool count == rooms count" \
           "room_point_pool=$RPP_COUNT, rooms=$ROOMS_COUNT"
fi

# ----- 5. users.free_revival_ticket_used backfilled (no NULLs) -----

NULL_TICKETS=$(run_sql "SELECT count(*) FROM users WHERE free_revival_ticket_used IS NULL")
if [[ "$NULL_TICKETS" == "0" ]]; then
    report PASS "users.free_revival_ticket_used non-null" "null_count=$NULL_TICKETS"
else
    report FAIL "users.free_revival_ticket_used non-null" "null_count=$NULL_TICKETS (expected 0)"
fi

# ----- summary -----

TOTAL=$((PASS_COUNT + FAIL_COUNT))
if [[ "$FAIL_COUNT" -eq 0 ]]; then
    printf 'verify-v11: %d/%d checks passed\n' "$PASS_COUNT" "$TOTAL"
    exit 0
else
    printf 'verify-v11: %d/%d checks passed (%d failed)\n' \
           "$PASS_COUNT" "$TOTAL" "$FAIL_COUNT"
    exit 1
fi
