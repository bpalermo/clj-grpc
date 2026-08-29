#!/usr/bin/env bash
# Workspace status for stamped image tags (//bazel/images). Wired in via
# .bazelrc. Bazel runs this on every build, so it must be fast and must never
# fail — everything degrades to a placeholder instead.
#
# STABLE_ keys are part of the action cache key, so a new commit re-stamps the
# tag rather than silently reusing a previous one.
set -uo pipefail

commit=$(git rev-parse --short=12 HEAD 2>/dev/null)
echo "STABLE_GIT_COMMIT ${commit:-unknown}"

# Marks images built from a non-pristine tree, so an accidental push is
# identifiable. --porcelain respects .gitignore, so bazel-* symlinks and
# target/ do not count.
if [ -z "$(git status --porcelain 2>/dev/null)" ]; then
  echo "STABLE_GIT_DIRTY clean"
else
  echo "STABLE_GIT_DIRTY dirty"
fi
