#!/usr/bin/env bash
# Stamp the talos-main overlay with the current :latest digest of each soak
# image, straight from the registry — the same digests build.yaml printed when
# it pushed. Run before a soak; commit the result with the soak's report so
# the run is exactly reproducible later.
#
#   ./soak/pin-digests.sh [commit-ref-for-the-comment]
set -euo pipefail
cd "$(dirname "$0")/.."

ref="${1:-$(git rev-parse --short=12 HEAD)}"
overlay="soak/k8s/overlays/talos-main/kustomization.yaml"

for repo in soak-grpc-native soak-grpc-jvm soak-rest; do
  image="ghcr.io/bpalermo/clj-grpc/${repo}"
  digest=$(docker manifest inspect --verbose "${image}:latest" \
             | jq -r 'if type == "array" then .[0].Descriptor.digest else .Descriptor.digest end')
  [ -n "${digest}" ] && [ "${digest}" != "null" ] || {
    echo "could not resolve ${image}:latest (is the package public?)" >&2
    exit 1
  }
  # Replace the digest line following this image's name entry.
  python3 - "$overlay" "$image" "$digest" "$ref" <<'EOF'
import re, sys
path, image, digest, ref = sys.argv[1:5]
s = open(path).read()
pattern = rf"(- name: {re.escape(image)}\n    )(digest: \S+ # \S+|newTag: latest # pin: digest sha256:\.\.\. from build\.yaml)"
s2 = re.sub(pattern, rf"\g<1>digest: {digest} # {ref}", s)
if s == s2 and digest not in s:
    sys.exit(f"no pin entry matched for {image} in {path}")
open(path, "w").write(s2)
EOF
  echo "${repo}: ${digest}"
done
