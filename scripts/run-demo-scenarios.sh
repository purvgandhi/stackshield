#!/usr/bin/env sh
# Runs the four demo scenarios against a running StackTrial (real git, real Docker, real mvn verify)
# and saves every report, build log and dependency tree under docs/scenario-results/.
#
# Prerequisites: the app is running on http://localhost:8080, `sh fixtures/create-demo-repos.sh` was run,
# and curl + jq are installed.
# Usage: sh scripts/run-demo-scenarios.sh [base-url]
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
base=${1:-http://localhost:8080}
repos=/tmp/stacktrial-fixture-repos
out="$here/docs/scenario-results"
mkdir -p "$out"

diff_json=$(jq -Rs . < "$here/fixtures/diffs/compile-break.diff")

run() {
  name=$1 body=$2
  echo "== $name: creating trial"
  id=$(curl -sf -X POST "$base/api/trials" -H 'Content-Type: application/json' -d "$body" | jq -r .trialId)
  echo "   trial $id"
  while [ "$(curl -sf "$base/api/trials/$id" | jq -r .finished)" != "true" ]; do sleep 5; done

  dir="$out/$name"
  rm -rf "$dir" && mkdir -p "$dir"
  printf "%s" "$body" | jq . > "$dir/request.json"
  curl -sf "$base/api/trials/$id" | jq . > "$dir/report.json"
  for side in BASELINE TRIAL; do
    curl -sf "$base/api/trials/$id/runs/$side/log" > "$dir/$(echo $side | tr A-Z a-z)-build.log" || true
    curl -sf "$base/api/trials/$id/runs/$side/dependency-tree" > "$dir/$(echo $side | tr A-Z a-z)-dependency-tree.txt" || true
  done
  jq -r '"   status=\(.status) verdict=\(.verdict) | \(.verdictReason // .failureReason)"' "$dir/report.json"
  jq -r '.comparison[] | "     \(.label): \(.baseline) -> \(.trial)"' "$dir/report.json"
}

run 1-spring-boot-2.7-to-3.0 \
  "{\"repoUrl\":\"file://$repos/breaking-upgrade\",\"baselineRef\":\"main\",\"changeType\":\"SPRING_BOOT_VERSION\",\"targetVersion\":\"3.0.13\"}"
run 2-safe-dependency-upgrade \
  "{\"repoUrl\":\"file://$repos/passing-upgrade\",\"baselineRef\":\"main\",\"changeType\":\"DEPENDENCY_VERSION\",\"groupId\":\"org.apache.commons\",\"artifactId\":\"commons-lang3\",\"targetVersion\":\"3.17.0\"}"
run 3-java-17-to-21 \
  "{\"repoUrl\":\"file://$repos/passing-upgrade\",\"baselineRef\":\"main\",\"changeType\":\"JAVA_VERSION\",\"targetVersion\":\"21\"}"
run 4-broken-code-change \
  "{\"repoUrl\":\"file://$repos/passing-upgrade\",\"baselineRef\":\"main\",\"changeType\":\"GIT_DIFF\",\"diff\":$diff_json}"

echo "Saved to $out"
