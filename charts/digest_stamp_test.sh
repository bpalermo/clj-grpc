#!/usr/bin/env bash
# The rules_helm <-> rules_img integration is an untyped-JSON contract
# (rules_helm reads image_push's deploy metadata and hashes the manifest).
# If a rules_img upgrade ever breaks it, packaging silently keeps the raw
# placeholder tokens — this test makes that a CI failure instead of a
# deploy-time surprise.
set -euo pipefail
tgz="$1"
values=$(tar xzf "$tgz" -O clj-grpc-soak/values.yaml)
for repo in soak-grpc-native soak-grpc-jvm soak-rest; do
  echo "$values" | grep -Eq "ghcr\.io/bpalermo/clj-grpc/${repo}@sha256:[a-f0-9]{64}" \
    || { echo "FAIL: no stamped digest for ${repo}"; echo "$values" | grep image:; exit 1; }
done
if echo "$values" | grep -q '{@'; then
  echo "FAIL: unsubstituted image token remains"; exit 1
fi
echo "all three images digest-stamped"
