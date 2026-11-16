#!/usr/bin/env bash
# Builds and tests a jakarta copy of BlackBox against Joget 9.x wflow-core, to catch 9.x API changes early.
# The shipped jar stays the 8.2 (javax) build: Joget 9 converts it to jakarta when it installs it.
# Usage: ./check-joget9.sh [wflow-core version ...]   (default: 9.0-SNAPSHOT 9.1-SNAPSHOT; needs them in ~/.m2)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
versions=("$@")
[ ${#versions[@]} -eq 0 ] && versions=(9.0-SNAPSHOT 9.1-SNAPSHOT)
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
status=0
for v in "${versions[@]}"; do
    d="$work/$v"
    mkdir -p "$d/chrome-extension"
    cp -r "$here/pom.xml" "$here/src" "$d/"
    cp "$here/chrome-extension/viewer.js" "$here/chrome-extension/viewer.css" "$here/chrome-extension/display.js" "$d/chrome-extension/"
    python3 - "$d/pom.xml" "$v" <<'EOF'
import re, sys
path, version = sys.argv[1], sys.argv[2]
p = open(path).read()
p = re.sub(r'(<artifactId>wflow-core</artifactId>\s*<version>)[^<]+', r'\g<1>' + version, p)
p = re.sub(r'<groupId>javax.servlet</groupId>(\s*)<artifactId>javax.servlet-api</artifactId>(\s*)<version>[^<]+</version>',
           r'<groupId>jakarta.servlet</groupId>\1<artifactId>jakarta.servlet-api</artifactId>\2<version>6.0.0</version>', p)
open(path, 'w').write(p)
EOF
    grep -rl 'javax\.servlet' "$d/src" | xargs -r sed -i 's/javax\.servlet/jakarta.servlet/g'
    echo "== wflow-core $v"
    if (cd "$d" && mvn -o -q test > build.log 2>&1); then
        echo "PASS"
    else
        grep -E 'ERROR|Tests run:' "$d/build.log" | head -30
        echo "FAIL"
        status=1
    fi
done
exit $status
