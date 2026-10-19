#!/usr/bin/env bash
# Full E3 runs, one after another (docs/EXPERIMENTS.md sections 5 and 7).
#
#   nohup caffeinate -i scripts/run-e3.sh > runs/e3-runner.log 2>&1 &
#
# Each run writes runs/<runId>.jsonl (the runner's log) and runs/<runId>.out (process output, appended on resume).
# A run whose last run_finished already has validCycles = cycles is skipped; an unfinished one is resumed by the
# runner. The script stops, instead of going on to the next run, when a run exits non-zero, ends without
# run_finished, or reports budgetExhausted. After each run one line goes to runs/e3-progress.log.
# e3-l1-ambiguous-en is not part of P1 (section 9, 2026-10-19) and is not run here.
set -u
cd "$(dirname "$0")/.."

RUNS=(
  e3-b0-ambiguous e3-b1-ambiguous e3-a1-ambiguous e3-v1-ambiguous
  e3-b0-disambiguated e3-b1-disambiguated e3-a1-disambiguated e3-v1-disambiguated
)
JAR=build/libs/keristra-core-0.0.1-SNAPSHOT.jar
OLLAMA_URL=${OLLAMA_URL:-http://localhost:11434}
TESTS_MODEL=gemma4:26b
PROGRESS=runs/e3-progress.log

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }
stop() { log "STOP: $*"; exit 1; }

JAVA_HOME_21=$(/usr/libexec/java_home -v 21 2>/dev/null) || stop "JDK 21 not found (/usr/libexec/java_home -v 21)"
JAVA="$JAVA_HOME_21/bin/java"

preflight() {
  [ -f "$JAR" ] || stop "$JAR not found; build it with ./gradlew bootJar"
  if [ -n "$(find src/main -newer "$JAR" -type f -print -quit)" ]; then
    stop "$JAR is older than src/main; rebuild it with ./gradlew bootJar"
  fi
  local tags
  tags=$(curl -sf -m 10 "$OLLAMA_URL/api/tags") || stop "Ollama does not answer at $OLLAMA_URL"
  echo "$tags" | python3 -I -c '
import json, sys
names = [m.get("name") for m in json.load(sys.stdin).get("models", [])]
sys.exit(0 if sys.argv[1] in names else 1)' "$TESTS_MODEL" || stop "Ollama has no $TESTS_MODEL"
  # The app reads the key from the environment or from application-local.properties (spring.config.import).
  if [ -z "${ANTHROPIC_API_KEY:-}" ] && ! grep -Eq '^anthropic\.api\.key=.+' application-local.properties 2>/dev/null; then
    stop "ANTHROPIC_API_KEY is not set (neither in the environment nor as anthropic.api.key in application-local.properties)"
  fi
}

# Prints "<validCycles> <target> <budgetExhausted>" from the last run_finished, or nothing if there is none.
last_finished() {
  python3 -I - "$1" <<'PY'
import json, sys
last = None
try:
    with open(sys.argv[1]) as f:
        for line in f:
            if '"run_finished"' in line:
                event = json.loads(line)
                if event.get("event") == "run_finished":
                    last = event
except FileNotFoundError:
    pass
if last is not None:
    print(last.get("validCycles"), last.get("target"), str(last.get("budgetExhausted")).lower())
PY
}

# One progress line from the last run_finished; GT-wrong accepted cycles = groundTruth.acceptedWrong.
progress_line() {
  python3 -I - "$1" "$2" "$3" <<'PY'
import json, sys
path, run_id, seconds = sys.argv[1], sys.argv[2], int(sys.argv[3])
last = None
with open(path) as f:
    for line in f:
        if '"run_finished"' in line:
            event = json.loads(line)
            if event.get("event") == "run_finished":
                last = event
cost = last.get("costUsd")
print("\t".join([
    last["finishedAt"],
    run_id,
    f"validCycles={last.get('validCycles')}/{last.get('target')}",
    f"errorCycles={last.get('errorCycles')}",
    "outcomes=" + json.dumps(last.get("outcomes"), sort_keys=True, separators=(",", ":")),
    f"acceptedWrong={last['groundTruth'].get('acceptedWrong')}",
    "costUsd=" + ("null" if cost is None else f"{cost:.2f}"),
    f"session={seconds // 3600}h{seconds % 3600 // 60:02d}m",
]))
PY
}

mkdir -p runs
log "E3: ${#RUNS[@]} runs, jar $JAR ($(git rev-parse --short HEAD 2>/dev/null))"

for run_id in "${RUNS[@]}"; do
  config="experiments/$run_id.json"
  jsonl="runs/$run_id.jsonl"
  [ -f "$config" ] || stop "$config not found"
  target=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["cycles"])' "$config")

  read -r valid logged_target exhausted <<< "$(last_finished "$jsonl")"
  if [ -n "${valid:-}" ] && [ "$valid" != "None" ] && [ "$valid" -ge "$target" ]; then
    log "$run_id: already has $valid/$target valid cycles, skipped"
    continue
  fi

  preflight
  if [ -f "$jsonl" ]; then log "$run_id: resuming (${valid:-no run_finished yet}/$target valid)"; else log "$run_id: starting"; fi
  started=$(date +%s)
  echo "=== $(date '+%Y-%m-%d %H:%M:%S') session start" >> "runs/$run_id.out"
  "$JAVA" -jar "$JAR" --spring.profiles.active=experiment \
    --keristra.experiment.config="$config" --keristra.experiment.output-dir=runs >> "runs/$run_id.out" 2>&1
  code=$?
  seconds=$(( $(date +%s) - started ))

  [ "$code" -eq 0 ] || stop "$run_id exited with code $code after ${seconds}s; see runs/$run_id.out"
  read -r valid logged_target exhausted <<< "$(last_finished "$jsonl")"
  [ -n "${valid:-}" ] || stop "$run_id ended without run_finished; see runs/$run_id.out"

  line=$(progress_line "$jsonl" "$run_id" "$seconds")
  echo "$line" >> "$PROGRESS"
  log "$run_id: $line"
  [ "$exhausted" = "false" ] || stop "$run_id: budgetExhausted ($valid/$target valid cycles); rerun the script to resume it"
done

log "E3: all runs have their valid cycles"
