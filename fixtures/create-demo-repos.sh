#!/usr/bin/env sh
# Turns each fixture directory into a standalone Git repository (branch "main") that StackTrial can clone.
# Usage: fixtures/create-demo-repos.sh [target-dir]     (default: /tmp/stacktrial-fixture-repos)
# Then create a trial with repoUrl file:///tmp/stacktrial-fixture-repos/<fixture>.
# To demo against GitHub instead, push these repositories and use their https:// URLs.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
target=${1:-/tmp/stacktrial-fixture-repos}
mkdir -p "$target"
for fixture in passing-upgrade breaking-upgrade; do
  rm -rf "$target/$fixture"
  mkdir -p "$target/$fixture"
  (cd "$here/$fixture" && tar --exclude=target -cf - .) | (cd "$target/$fixture" && tar --no-same-owner -xf -)
  (
    cd "$target/$fixture"
    git init -q -b main
    git add -A
    git -c user.name="StackTrial Fixtures" -c user.email="fixtures@stacktrial.local" commit -q -m "Baseline: $fixture"
  )
  echo "file://$target/$fixture"
done
