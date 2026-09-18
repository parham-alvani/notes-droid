#!/usr/bin/env bash
#
# Checks that R8 left the classes nothing references by symbol.
#
# The SSH transport is held together by name lookups: sshd asks for its
# Ed25519 provider as the string "net.i2p.crypto.eddsa.EdDSASecurityProvider",
# and JGit finds its transports through ServiceLoader. R8 cannot see either, so
# a missing keep rule does not fail the build -- it ships an APK that installs,
# launches, and cannot authenticate, reporting it as a key the server refused.
#
# Only release builds run R8, so only release builds can be wrong this way,
# which is exactly why nothing caught it for a whole day of debug builds.
#
# Usage: tools/verify-apk.sh <apk>
set -euo pipefail

apk="${1:?usage: tools/verify-apk.sh <apk>}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

unzip -oq "$apk" 'classes*.dex' -d "$work"

# Descriptors, not package prefixes: R8 renaming a class is the failure being
# looked for, and a renamed class leaves the prefix behind in other strings.
required=(
    "Lnet/i2p/crypto/eddsa/EdDSASecurityProvider;"
    "Lnet/i2p/crypto/eddsa/EdDSAPrivateKey;"
    "Lorg/apache/sshd/client/ClientBuilder;"
    "Lorg/eclipse/jgit/api/Git;"
)

symbols=$(cat "$work"/classes*.dex | strings)
missing=()
for class in "${required[@]}"; do
    grep -qF -- "$class" <<<"$symbols" || missing+=("$class")
done

if [ ${#missing[@]} -ne 0 ]; then
    echo "R8 removed or renamed classes the SSH transport looks up by name:" >&2
    printf '    %s\n' "${missing[@]}" >&2
    echo >&2
    echo "Add a keep rule to app/proguard-rules.pro. Shipping this APK gives you" >&2
    echo "'publickey: no keys to try' on a device whose key is perfectly good." >&2
    exit 1
fi

echo "$(basename "$apk"): all ${#required[@]} reflectively-loaded classes survived R8"
