#!/bin/bash

# TODO: This is flaky as it relies on coarse directory-level diffs.

BASE="$(git merge-base main HEAD)"

# shellcheck disable=SC2046 # We actually want splitting in jq command output
git diff --quiet HEAD "$BASE" -- $(jq --raw-output ".workspaces[]" package.json)
check_ts=$?
git diff --quiet HEAD "$BASE" -- skiplang/prelude/ :^skiplang/prelude/ts
prelude=$?
git diff --quiet HEAD "$BASE" -- skiplang/compiler/
skc=$?
git diff --quiet HEAD "$BASE" -- skipruntime-ts/
skipruntime=$?
git diff --quiet HEAD "$BASE" -- skiplang/prelude/ts/
ts_prelude=$?
git diff --quiet HEAD "$BASE" -- examples/
examples=$?

shopt -s globstar
declare -A SK_CHANGED
for skargo_toml in **/Skargo.toml; do
  dir=$(dirname "$skargo_toml")
  git diff --quiet HEAD "$BASE" -- "$dir" \
    && SK_CHANGED["$dir"]=false || SK_CHANGED["$dir"]=true
done

if (( prelude != 0 )); then
  skc=1
  SK_CHANGED[sql]=true
  skdb_wasm=true
  skipruntime=1
fi
if ${SK_CHANGED[skiplang/sqlparser]}; then
  SK_CHANGED[sql]=true
fi
if ${SK_CHANGED[skiplang/skjson]}; then
  skdb_wasm=true
  skipruntime=1
fi
if (( ts_prelude != 0 )); then
  skdb_wasm=true
  skipruntime=1
fi

cat .circleci/base.yml

echo "workflows:"

    cat <<EOF
  fast-checks:
    jobs:
      - fast-checks
EOF

if (( check_ts != 0 ))
then
    cat <<EOF
  check-ts:
    jobs:
      - check-ts
EOF
fi

if (( skc != 0 ))
then
   cat <<EOF
  compiler:
    jobs:
      - compiler
EOF
fi

if (( prelude != 0 ))
then
    cat <<EOF
  skstore:
    jobs:
      - skstore
EOF
fi

for dir in "${!SK_CHANGED[@]}"; do
  if ${SK_CHANGED["$dir"]}; then
    case "$dir" in
      skiplang/compiler | skiplang/prelude | skipruntime-ts/* ) ;;
      sql)
        cat <<EOF
  skdb:
    jobs:
      - skdb
EOF
        ;;
      *)
        if [ -d "$dir/tests" ]; then
          name=$(basename "$dir")
          echo "  $name-tests:"
          echo "    jobs:"
          echo "      - skip-package-tests:"
          echo "          dir: $dir"
          echo "          name: $name"
        fi
    esac
  fi
done

if ${skdb_wasm:-false}
then
    cat <<EOF
  skdb-wasm:
    jobs:
      - skdb-wasm
EOF
fi

if (( skipruntime != 0 ))
then
    cat <<EOF
  skipruntime:
    jobs:
      - skipruntime
EOF
fi

if (( examples != 0 ))
then
    cat <<EOF
  examples:
    jobs:
      - check-examples
EOF
fi
