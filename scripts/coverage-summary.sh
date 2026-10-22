#!/usr/bin/env bash
# Prints line and branch coverage per module and in total from the JaCoCo CSV reports written by
#   mvn -B clean verify
# (target/site/jacoco/jacoco.csv in each module). It measures code exercised by the automated tests only.
set -euo pipefail
cd "$(dirname "$0")/.."
REPORTS=$(ls services/*/target/site/jacoco/jacoco.csv shared/*/target/site/jacoco/jacoco.csv 2>/dev/null || true)
[[ -z "$REPORTS" ]] && { echo "No JaCoCo reports found: run 'mvn -B clean verify' first." >&2; exit 1; }
printf '%-24s %10s %10s\n' "module" "lines" "branches"
# CSV columns: 6 BRANCH_MISSED, 7 BRANCH_COVERED, 8 LINE_MISSED, 9 LINE_COVERED
awk -F, 'FNR > 1 {
    m = FILENAME; sub(/\/target\/site\/jacoco\/jacoco.csv$/, "", m); sub(/^.*\//, "", m)
    lm[m] += $8; lc[m] += $9; bm[m] += $6; bc[m] += $7
  }
  END { for (m in lm) printf "%-24s %9.1f%% %9.1f%%\n", m, 100 * lc[m] / (lc[m] + lm[m]), (bc[m] + bm[m]) ? 100 * bc[m] / (bc[m] + bm[m]) : 0 }' $REPORTS | sort
# Total from the aggregate report (counts platform-commons code exercised by the services' tests too).
AGG=coverage/target/site/jacoco-aggregate/jacoco.csv
[[ -f "$AGG" ]] && REPORTS="$AGG" && echo "(per-module lines above count only each module's own tests; TOTAL is the aggregate)"
awk -F, 'FNR > 1 { lm += $8; lc += $9; bm += $6; bc += $7 }
  END { printf "%-24s %9.1f%% %9.1f%%\n(%d of %d lines, %d of %d branches covered)\n", "TOTAL", 100 * lc / (lc + lm), 100 * bc / (bc + bm), lc, lc + lm, bc, bc + bm }' $REPORTS
