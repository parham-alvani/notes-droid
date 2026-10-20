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
unzip -oq "$apk" 'META-INF/services/*' -d "$work" 2>/dev/null || true

# Descriptors, not package prefixes: R8 renaming a class is the failure being
# looked for, and a renamed class leaves the prefix behind in other strings.
required=(
    "Lnet/i2p/crypto/eddsa/EdDSASecurityProvider;"
    "Lnet/i2p/crypto/eddsa/EdDSAPrivateKey;"
    "Lorg/apache/sshd/client/ClientBuilder;"
    "Lorg/eclipse/jgit/api/Git;"
    # Glance starts it by name on a tap on the quote widget.
    "Lme/parham1995/notes/widget/NextQuote;"
)

# Every implementation JGit or sshd registers through ServiceLoader is named in
# a text file R8 does not rewrite, so the list is read out of the APK rather
# than kept here: a dependency bump that adds a service is checked without
# anybody remembering to add it.
for services in "$work"/META-INF/services/org.eclipse.jgit.* "$work"/META-INF/services/org.apache.sshd.*; do
    [ -f "$services" ] || continue
    while read -r impl; do
        impl="${impl%%#*}"
        [ -n "$impl" ] && required+=("L${impl//./\/};")
    done <"$services"
done

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

# 16 KB pages. From Android 15 a device may use 16 KB memory pages, and a
# native library that is not aligned to them does not load: the bundled
# SQLite is one, and without it there is no database. Two things have to
# hold, and a dependency bump can break either without a build error -- the
# library's offset inside the zip, and the alignment of its own segments.
zipalign=$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/*/zipalign | sort -V | tail -1)
if ! "$zipalign" -c -P 16 4 "$apk"; then
    echo "Native libraries are not 16 KB aligned inside $(basename "$apk")." >&2
    "$zipalign" -c -P 16 -v 4 "$apk" | grep -F '.so' | grep -v '(OK' >&2 || true
    exit 1
fi

unzip -oq "$apk" 'lib/arm64-v8a/*' 'lib/x86_64/*' -d "$work" 2>/dev/null || true
python3 - "$work"/lib/*/*.so <<'PY'
import struct, sys

PAGE = 16 * 1024
bad = []
libs = [p for p in sys.argv[1:] if not p.endswith("*.so")]
for path in libs:
    data = open(path, "rb").read()
    phoff, = struct.unpack_from("<Q", data, 0x20)
    size, count = struct.unpack_from("<HH", data, 0x36)
    for i in range(count):
        entry = phoff + i * size
        kind, = struct.unpack_from("<I", data, entry)
        align, = struct.unpack_from("<Q", data, entry + 48)
        if kind == 1 and align < PAGE:  # PT_LOAD
            bad.append(f"{path.split('/lib/', 1)[1]}: segments aligned to {align}")
            break
if bad:
    print("64-bit native libraries built for 4 KB pages:", file=sys.stderr)
    print("\n".join("    " + b for b in bad), file=sys.stderr)
    sys.exit(1)
print(f"{len(libs)} 64-bit native libraries are 16 KB aligned")
PY
