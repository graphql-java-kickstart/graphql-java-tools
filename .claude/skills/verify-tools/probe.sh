#!/usr/bin/env bash
# Drives graphql-java-tools the way a consuming app does: installs the current directory's checkout into a private local
# Maven repository, compiles the probe project in probe/ against it, builds a scenario's schema and runs its operations.
# See SKILL.md for the commands. Evidence goes to $TMPDIR/verify-tools/evidence/ and survives `clean`.
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git rev-parse --show-toplevel 2>/dev/null)" || REPO="$(git -C "$SKILL_DIR" rev-parse --show-toplevel)"
ROOT="${TMPDIR:-/tmp}"
ROOT="${ROOT%/}/verify-tools"
KEY="$(basename "$REPO")-$(printf '%s' "$REPO" | sha256sum | cut -c1-8)"
WORK="$ROOT/work/$KEY"
M2="$WORK/m2"
TAIL_M2="$HOME/.m2/repository"
EVIDENCE_ROOT="$ROOT/evidence"
STAMP="$WORK/installed.stamp"

die() { echo "probe: $*" >&2; exit 1; }

pom_value() { sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$REPO/pom.xml" | head -1; }
TOOLS_VERSION="$(pom_value version)"
KOTLIN_VERSION="$(pom_value kotlin.version)"

# Identifies the library sources: HEAD plus any uncommitted change to pom.xml or src/, tracked or not
fingerprint() {
  {
    git -C "$REPO" rev-parse HEAD
    git -C "$REPO" diff HEAD -- pom.xml src
    git -C "$REPO" ls-files --others --exclude-standard -- src | while IFS= read -r file; do
      echo "$file"
      cat "$REPO/$file"
    done
  } | sha256sum | cut -c1-16
}

installed_jar() { echo "$M2/io/github/graphql-java-kickstart/graphql-java-tools/$TOOLS_VERSION/graphql-java-tools-$TOOLS_VERSION.jar"; }

install_state() {
  if [[ ! -f "$STAMP" || ! -f "$(installed_jar)" ]]; then echo missing
  elif [[ "$(cat "$STAMP")" == "$(fingerprint)" ]]; then echo fresh
  else echo stale
  fi
}

lock() {
  mkdir -p "$WORK"
  if ! mkdir "$WORK/lock" 2>/dev/null; then
    local pid
    pid="$(cat "$WORK/lock/pid" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      die "another probe run (pid $pid) is using $WORK; wait for it instead of sharing the work dir"
    fi
    rm -rf "$WORK/lock"
    mkdir "$WORK/lock"
  fi
  echo $$ > "$WORK/lock/pid"
  trap 'rm -rf "$WORK/lock"' EXIT
}

mvn_isolated() {
  mvn -B "-Dmaven.repo.local=$M2" "-Dmaven.repo.local.tail=$TAIL_M2" "$@"
}

cmd_install() {
  local fp log
  fp="$(fingerprint)"
  log="$WORK/install.log"
  echo "probe: installing $TOOLS_VERSION from $REPO into $M2 (log: $log)"
  if ! (cd "$REPO" && mvn_isolated install -DskipTests > "$log" 2>&1); then
    grep -E '^\[ERROR\]' "$log" | head -30 >&2 || true
    die "install failed, see $log"
  fi
  echo "$fp" > "$STAMP"
  echo "probe: installed $(installed_jar)"
}

cmd_doctor() {
  local state lock_state="free"
  state="$(install_state)"
  if [[ -f "$WORK/lock/pid" ]] && kill -0 "$(cat "$WORK/lock/pid")" 2>/dev/null; then
    lock_state="held by pid $(cat "$WORK/lock/pid")"
  fi
  echo "checkout:      $REPO"
  echo "branch:        $(git -C "$REPO" rev-parse --abbrev-ref HEAD) @ $(git -C "$REPO" rev-parse --short HEAD)"
  echo "tools version: $TOOLS_VERSION (kotlin $KOTLIN_VERSION)"
  echo "work dir:      $WORK"
  echo "install:       $state ($(installed_jar))"
  echo "lock:          $lock_state"
  echo "evidence:      $EVIDENCE_ROOT"
  echo "java:          $(java -version 2>&1 | head -1)"
  echo "maven:         $(mvn -v 2>/dev/null | head -1)"
  [[ -d "$TAIL_M2" ]] || echo "WARNING: $TAIL_M2 does not exist, every dependency will be downloaded into the work dir"
  [[ "$state" == "fresh" ]] || echo "note: run installs the checkout first because the install is $state"
}

cmd_list() {
  grep -h 'override val name = ' "$SKILL_DIR"/probe/src/main/kotlin/scenarios/*.kt | sed 's/.*= "\(.*\)"/\1/'
}

cmd_run() {
  local scenario="" gj="" skip_install=false
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --graphql-java) gj="$2"; shift 2 ;;
      --skip-install) skip_install=true; shift ;;
      -*) die "unknown option $1" ;;
      *) [[ -z "$scenario" ]] || die "one scenario per run"; scenario="$1"; shift ;;
    esac
  done
  [[ -n "$scenario" ]] || die "usage: probe.sh run <scenario> [--graphql-java VERSION] [--skip-install]"
  cmd_list | grep -qx "$scenario" || die "unknown scenario '$scenario', known: $(cmd_list | tr '\n' ' ')"

  lock
  local state
  state="$(install_state)"
  if [[ "$state" != "fresh" ]]; then
    if [[ "$skip_install" == true && "$state" == "stale" ]]; then
      echo "probe: WARNING install is stale, running against the previously installed jar"
    else
      cmd_install
    fi
  fi

  local evidence base n=1 build mvn_args=() exit_code
  base="$EVIDENCE_ROOT/$(date +%Y%m%d-%H%M%S)-$scenario${gj:+-gj$gj}"
  evidence=$base
  build="$WORK/probe-target${gj:+-gj$gj}"
  mkdir -p "$EVIDENCE_ROOT"
  # Another checkout can start the same scenario in the same second
  until mkdir "$evidence" 2>/dev/null; do
    n=$((n + 1))
    evidence="$base-$n"
  done
  mvn_args=(-f "$SKILL_DIR/probe/pom.xml" "-Dtools.version=$TOOLS_VERSION" "-Dkotlin.version=$KOTLIN_VERSION"
    "-Dprobe.build.dir=$build" "-Dmdep.outputFile=$build/classpath.txt")
  [[ -z "$gj" ]] || mvn_args+=("-Dgraphql-java.version=$gj")
  # resources:resources only copies, so an operation deleted from a scenario would keep running
  rm -rf "$build"/classes/scenarios/*/
  if ! mvn_isolated "${mvn_args[@]}" compile dependency:build-classpath > "$evidence/maven.log" 2>&1; then
    grep -E '^\[ERROR\]|^e: ' "$evidence/maven.log" | head -30 >&2 || true
    die "probe compile failed, see $evidence/maven.log"
  fi

  {
    echo "checkout: $REPO"
    echo "commit: $(git -C "$REPO" rev-parse HEAD)"
    echo "source fingerprint: $(fingerprint)"
    echo "install stamp: $(cat "$STAMP")"
    echo "command: probe.sh run $scenario${gj:+ --graphql-java $gj}"
  } > "$evidence/run.txt"

  set +e
  java "-Dprobe.out=$evidence" -cp "$build/classes:$(cat "$build/classpath.txt")" probe.MainKt "$scenario" "$evidence" \
    2> "$evidence/stderr.txt" | tee "$evidence/stdout.txt"
  exit_code="${PIPESTATUS[0]}"
  set -e
  echo "exit code: $exit_code" >> "$evidence/run.txt"
  echo "probe: exit $exit_code, evidence in $evidence"
  return "$exit_code"
}

cmd_clean() {
  if [[ -f "$WORK/lock/pid" ]] && kill -0 "$(cat "$WORK/lock/pid")" 2>/dev/null; then
    die "a probe run (pid $(cat "$WORK/lock/pid")) is still using $WORK"
  fi
  rm -rf "$WORK"
  echo "probe: removed $WORK; evidence kept in $EVIDENCE_ROOT"
}

case "${1:-}" in
  install) lock; cmd_install ;;
  doctor) cmd_doctor ;;
  list) cmd_list ;;
  run) shift; cmd_run "$@" ;;
  clean) cmd_clean ;;
  *) echo "usage: probe.sh {doctor|list|install|run <scenario> [--graphql-java VERSION] [--skip-install]|clean}" >&2; exit 64 ;;
esac
