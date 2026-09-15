#!/usr/bin/env bash
# Rolling restart of the scheduler cluster: one replica at a time, never both at once.
#
# `docker-compose up -d --force-recreate scheduler` recreates every replica of the service in one go, and
# 400s of no scheduler means fires pile up in QRTZ_TRIGGERS. A concurrent=0 task carries
# withMisfireHandlingInstructionDoNothing, so those piled-up fires are *discarded* — the deploy would
# silently skip scheduled runs, which is the opposite of the "never lose an execution" the 400s grace is
# there for.
#
# Run it after the new image exists (deploy-service.sh's scheduler branch does). It tops the cluster up to
# SCHEDULER_REPLICAS *before* stopping anything and only then replaces the replicas that were already
# running, so a first bring-up, a 1 -> 2 growth and a straight N-replica roll all keep the cluster at full
# strength. Growing from one replica without that pre-pass would mean a stop plus a ~90s boot with no
# scheduler in the cluster at all — and a node that joined the cluster with scheduler.enabled=false is not
# a failover target either, since such a node never starts its Quartz scheduler.
#
# What the guard can actually see: before every stop this script requires *another* replica to answer its
# container healthcheck, which is a GET on /actuator/health/liveness. That is liveness, not membership — the
# scheduler deliberately keeps its own `scheduler` indicator out of liveness, so a node running with
# scheduler.enabled=false (or one that never joined the shared QRTZ_* tables) answers happily and still
# satisfies the guard. There is nothing here that could do better: compose interpolates SCHEDULER_ENABLED
# identically for every replica, so no per-node signal exists to reject. "Do not keep a disabled node
# registered under this service name" is therefore a topology rule the release docs own — docs/deploy-
# harnax-scheduler.md is the file that carries scheduler deployment rules — and this script's guarantee has to
# be read as "another replica is alive", never as "another replica will take my triggers over".
#
# Two rolls of the same cluster at once defeat all of the above (each one sees the other's replica healthy and
# each stops its own), so the script holds an exclusive lock for its whole run and refuses to start without
# it. See LOCK_DIR below. Ctrl-C and SIGTERM end the roll — they do not merely drop the lock out from under a
# script that would then keep working (see on_signal).
#
# Usage: docker-new/roll-scheduler.sh
#
# Needs Compose v2 (the `docker compose` v2 CLI, or a `docker-compose` shim pointing at it). Two of this
# script's listings are v2-only: `ps --all --quiet`, which proves a stopped replica is really gone, and
# `ps --all --status exited --status created`, which finds the replicas an aborted roll left behind — the
# python 1.x `docker-compose` has no `--status` at all. The plain `ps --quiet` listings work on either. That
# first one is the load-bearing case, and it runs *after* a replica has been stopped and removed: without the
# probe below a v1 host gets through the first stop and then dies unable to prove the victim is gone, with the
# cluster one replica down. So the flags are probed once here, while nothing is touched.
#
# Knobs, all optional:
#   SCHEDULER_REPLICAS    replicas the cluster must end with (default 2). Lower bound 2 whenever a replica
#                         is already running — the roll cannot keep a one-node cluster alive — and 1 on a
#                         host where nothing runs yet, which is a bring-up rather than a roll.
#   SCHEDULER_STOP_GRACE  seconds `docker stop -t` allows an in-flight execution. Keep it equal to this
#                         service's stop_grace_period in docker-compose.yml (default 400)
#   SCHEDULER_HEALTH_WAIT seconds to wait for the cluster to be healthy again after each replacement
#                         (default 300; a replica needs ~60s of healthcheck start_period plus a poll)
#   COMPOSE_FILE          compose file, relative to the project root
#   LOCK_BASE_DIR         directory the roll lock directory is created in (default /tmp, and deliberately
#                         not $TMPDIR — see LOCK_BASE_DIR below; every caller must pass the same value)
#   MYSQL_ROOT_PASSWORD   only for the closing QRTZ_SCHEDULER_STATE read-out; unset falls back to the
#                         compose default and a wrong one costs a hint line, never a roll
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-new/docker-compose.yml}"
SERVICE="scheduler"
REPLICAS="${SCHEDULER_REPLICAS:-2}"
GRACE="${SCHEDULER_STOP_GRACE:-400}"
HEALTH_WAIT="${SCHEDULER_HEALTH_WAIT:-300}"
HEALTH_POLL_SECONDS=5
# What the lock has to name: the *cluster*, not the directory this copy of the repo sits in. Two rolls of one
# compose project are the failure the lock prevents, and every checkout of this repo drives the same one —
# compose takes the project name from the directory that holds the compose file, so `docker-new` here in all
# three of this repo's worktrees (verified: `docker-compose -f docker-new/docker-compose.yml config` prints
# `name: docker-new` from both the main checkout and .worktrees/scheduler-cluster-cutover, while
# basename "${PROJECT_DIR}" differed between them). The previous key, basename of the checkout, therefore gave
# one cluster as many locks as there are checkouts — which is exactly the concurrency the lock exists to stop.
#
# Chosen source for the name: compose's own rule, applied locally, rather than asking the CLI. `docker
# compose config` is the more authoritative answer, but it interpolates the whole file, and the `name:` field
# in that output only exists on compose v2 — a host whose `docker-compose` is the old python 1.x prints no
# project name at all, so the CLI is not a portable authority for a key that has to be identical on every
# shell that may start a roll. The rule is: COMPOSE_PROJECT_NAME if set, else the base name of the directory
# holding the compose file, lowercased and stripped to the characters compose allows. This file sets no
# top-level `name:` (grep for '^name:' is empty), so the directory rule is what applies; add it there, or
# export COMPOSE_PROJECT_NAME, and this derivation has to follow.
#
# The daemon joins the key so a shell pointed at a different one — a test daemon, a second context — is not
# blocked by an unrelated cluster's roll. Its name comes from the reachability preflight below, i.e. from the
# daemon itself rather than from DOCKER_HOST or the current context, which is the whole point: however you
# get there, the same daemon gives the same key.
#
# Base directory defaults to /tmp, *not* ${TMPDIR:-/tmp}: an operator shell with TMPDIR exported (macOS gives
# every GUI login a per-user $TMPDIR) and a cron or sudo deploy without it have to contend for one lock, and
# /tmp is the one place both see, including across users. The cost is that /tmp must be writable — a failure
# there is reported as "cannot create the lock", never as "another roll is running" (see acquire_lock).
# It is overridable, and has to stay that way: the error message below tells the operator to point it
# somewhere else, and a plain assignment would make that advice impossible to follow. Override it in the
# environment of *every* caller of this script (a cron entry and the shell that clears a stale lock included),
# because two different values are two locks and one unguarded cluster.
LOCK_BASE_DIR="${LOCK_BASE_DIR:-/tmp}"
# The rest of the key — compose project name plus daemon name — is assembled with the daemon preflight below,
# where both have actually been answered for.
LOCK_DIR=""
# The traps below run on every exit path, including the ones that die before a lock was ever taken, so both
# have to exist for `set -u`.
LOCK_HELD=0
LOCK_TOKEN=""

cd "$PROJECT_DIR"

die() {
  echo "❌ $*" >&2
  exit 1
}

compose() {
  docker-compose -f "$COMPOSE_FILE" "$@"
}

# compose accepts only [a-z0-9_-] in a project name (and docker in a daemon name), lowercased. Applied to both
# halves of the lock key so that two shells deriving the key from differently-spelled but identical inputs get
# the same path.
normalize_name() {
  printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9_-'
}

# Whether the lock at LOCK_DIR is this process's to give back. Two independent conditions, because the plain
# `rm -rf` this replaces ran from the EXIT trap of every exit path — including the refusal exit, where the
# directory belongs to the *other* roll, and including a roll that finished while a second one had already
# taken the lock over. The token carries a pid and a per-run nonce, so a match means "the directory here is
# the one mkdir made for me".
lock_is_mine() {
  [ -n "${LOCK_DIR}" ] \
    && [ "${LOCK_HELD}" -eq 1 ] \
    && [ "$(cat "${LOCK_DIR}/owner" 2>/dev/null || true)" = "${LOCK_TOKEN}" ]
}

release_lock() {
  # Called on a clean exit, a die, an INT and a TERM alike — and twice on the signal paths, since exiting from
  # the signal handler runs the EXIT trap too. Only SIGKILL leaves the directory behind now.
  if lock_is_mine; then
    rm -rf "${LOCK_DIR}"
  fi
  LOCK_HELD=0
  return 0
}

# A signal has to *end* the roll, not just unlock it. Nothing else here stops it: every CLI call in the roll
# loop is wrapped in `if ! …` or `|| …`, so the interrupted `docker stop` is caught, reported and followed by
# the `docker rm`, the replacement `up` and the next replica — a roll the operator believes aborted, running
# without a lock. Exiting from the handler is what makes the release honest.
#
# Exit status is 128+signo, the shell's own convention for dying of a signal, so a caller (deploy-service.sh,
# a cron wrapper) sees "interrupted" rather than "the roll failed".
on_signal() {
  local sig_name="$1" exit_status="$2" note="no lock had been taken yet"
  if [ "${LOCK_HELD}" -eq 1 ]; then
    release_lock
    note="lock ${LOCK_DIR} released"
  fi
  echo "❌ ${sig_name}: this roll is stopping here (${note})." >&2
  echo "   A replica this roll had already stopped may still be without its replacement. Check the cluster" \
    >&2
  echo "   ('docker-compose -f ${COMPOSE_FILE} ps --all ${SERVICE}'), then re-run this script — it re-lists" \
    >&2
  echo "   the replicas and rolls what is left." >&2
  exit "${exit_status}"
}

# Two rolls at once is the one failure mode this script cannot reason its way out of: each invocation sees the
# other's replica healthy, so each passes its own guard, and then each stops its own victim — the zero-enabled
# window the whole stop-one/replace-one design exists to prevent, reached from two directions instead of one.
# Hence a lock, taken before anything reads or touches the cluster and held for the whole roll.
#
# mkdir is the primitive: it is atomic and exclusive on every filesystem, needs no binary the deploy host may
# lack (flock ships with util-linux, and nothing in this repo's deploy scripts has ever required it), and
# leaves nothing half-written for the next reader.
acquire_lock() {
  local mkdir_error holder holder_pid
  # Traps first, while LOCK_HELD is still 0: the handlers above do nothing unless this process both took the
  # lock and can prove it, so arming them before mkdir costs nothing and closes the window in which a Ctrl-C
  # between mkdir and trap could leave the directory behind.
  trap 'release_lock' EXIT
  trap 'on_signal INT 130' INT
  trap 'on_signal TERM 143' TERM
  if ! mkdir_error="$(mkdir "${LOCK_DIR}" 2>&1)"; then
    if [ ! -d "${LOCK_DIR}" ]; then
      # A failed mkdir with nothing at the target is not contention — it is an unwritable or missing parent, a
      # read-only or full filesystem, or a non-directory squatting on the path. Saying "another roll is already
      # running" there sends the operator off to hunt a process that does not exist while the roll that needs
      # to happen quietly never does; and this script must not roll unguarded either.
      die "cannot create the scheduler roll lock at ${LOCK_DIR}: ${mkdir_error:-mkdir gave no reason}. This is not another roll holding it — there is nothing at that path — so the roll cannot start guarded and refuses to start at all. ${LOCK_BASE_DIR} defaults to /tmp rather than to TMPDIR so that an operator shell, cron and sudo all contend for one lock; make it writable, or export LOCK_BASE_DIR at a directory every caller of this script can create in — and export it *everywhere* this script is called from, because two values are two locks and one unguarded cluster."
    fi
    holder="$(cat "${LOCK_DIR}/owner" 2>/dev/null || true)"
    holder_pid=""
    case "${holder}" in
      pid=[0-9]*) holder_pid="${holder#pid=}"; holder_pid="${holder_pid%% *}" ;;
    esac
    if [ -n "${holder_pid}" ] && ! kill -0 "${holder_pid}" 2>/dev/null; then
      # Report the dead holder, do not steal from it. kill -0 cannot tell "the roll that made this directory was
      # SIGKILLed" from "that pid was recycled onto something else that is a roll", and guessing wrong in the
      # optimistic direction is the concurrency this lock exists to prevent.
      die "the scheduler roll lock at ${LOCK_DIR} belongs to a process that is no longer running (it recorded: ${holder}). Nothing is rolling right now, but this script will not clear a lock it did not create. Look before you touch it: 'ls -ld ${LOCK_DIR}' says whose uid made it (a cron or root roll leaves a directory your own account cannot clear, which is a reason to run the roll as that user, not a reason to reach for sudo on the delete), 'ps -p ${holder_pid}' plus the terminals, cron entries and CI steps that run this script say whether a roll is really in flight, and the recorded start time says whether that pid simply got recycled. Once you have satisfied yourself none is, move it aside rather than deleting it — 'mv ${LOCK_DIR} ${LOCK_DIR}.stale-\$(date +%s)' keeps the evidence for the next reader — and run this again."
    fi
    die "another scheduler roll is already running (${holder:-holder unknown, from a roll that recorded nothing}). Two concurrent rolls can each stop their own replica and leave the cluster with no scheduler, so this one refuses to start. Wait for the running roll to finish. If the holder above reads 'holder unknown', that is a specific shape and not a silent roll: the directory is made before the owner file is written, so a signal landing between the two leaves a lock with no pid in it, which no process will ever come back to clean up. There is nothing to match against `ps` in that case, so check the directory itself — 'ls -ld ${LOCK_DIR}' for the uid and the mtime, 'ls ${LOCK_DIR}' for whether an owner file exists at all — and confirm no roll of this project is in flight from the cron entry or CI job that would run one. Then move it aside rather than deleting it ('mv ${LOCK_DIR} ${LOCK_DIR}.stale-\$(date +%s)') and run this again; if it belongs to another uid, run the roll as that user instead of clearing it with elevated rights."
  fi
  LOCK_TOKEN="$(printf 'pid=%s user=%s nonce=%s started=%s cwd=%s project=%s daemon=%s' \
    "$$" "${USER:-unknown}" "${RANDOM}${RANDOM}$(date +%s)" \
    "$(date '+%Y-%m-%d %H:%M:%S %z')" "${PROJECT_DIR}" "${COMPOSE_PROJECT}" "${DAEMON_KEY}")"
  if ! printf '%s\n' "${LOCK_TOKEN}" > "${LOCK_DIR}/owner" 2>/dev/null; then
    # Without the owner file a later release_lock cannot prove the directory is ours, and would have to leave
    # it for the next operator to find. Better to undo a lock taken a microsecond ago — we know we made it.
    rm -rf "${LOCK_DIR}"
    die "created the roll lock directory ${LOCK_DIR} but could not write its owner file; nothing was touched. Check that ${LOCK_BASE_DIR} is writable."
  fi
  LOCK_HELD=1
}

# Ids of the scheduler replicas compose considers running, space separated.
#
# There are two listings because there are two right answers to "compose would not tell me":
#   * this tolerant one, used only inside the polling loops, where an empty result just means "not healthy
#     yet, poll again" and costs a sleep;
#   * list_running_strict / list_existing_strict below, used wherever an empty result changes what the script
#     *does* — which path to take, whether a victim really disappeared.
# An empty listing and a failed one look identical to a caller that discards the exit status, and the second
# one is not "the cluster is empty": treating it as such would send a roll down the bring-up path, where
# --no-recreate recreates nothing, and the script would report a successful deploy having touched nothing.
running_ids() {
  compose ps --quiet "$SERVICE" 2>/dev/null | tr '\n' ' ' || true
}

# Running replicas, but a listing that fails returns non-zero so the caller has to deal with it. Capture its
# output (`ids="$(list_running_strict)" || die …`): a die inside a command substitution only leaves the sub-
# shell, so this helper must not print-and-die by itself.
list_running_strict() {
  compose ps --quiet "$SERVICE"
}

# Every replica the project has for this service, running or not. The roll uses this, not the running-only
# listing, to prove a victim is gone: a container that is stopped but still exists is exactly what
# `up --no-recreate` starts again on the image it was created from.
list_existing_strict() {
  compose ps --all --quiet "$SERVICE"
}

# Membership test: list_has <needle> <space/newline separated id list>
list_has() {
  local needle="$1" hay="$2" id
  # shellcheck disable=SC2086  # the haystack is hex ids separated by whitespace; word splitting is the point
  for id in $hay; do
    if [ "${id}" = "${needle}" ]; then
      return 0
    fi
  done
  return 1
}

# Replicas that exist but are not running. An exited replica left behind by an aborted roll is invisible to
# running_ids yet still belongs to the service, so `up --no-recreate` can count it as one of the
# replicas and never replace it. Its logs are on the scheduler-logs volume, so removing it loses nothing.
stale_ids() {
  compose ps --all --quiet --status exited --status created "$SERVICE" 2>/dev/null | tr '\n' ' ' || true
}

# Health of one container. The `{{if .State.Health}}` guard matters: the bare `.State.Health.Status` form
# makes docker inspect itself fail on a container with no healthcheck defined, which then reads as a
# transient failure forever instead of what it is.
health_of() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null \
    || echo unknown
}

# Anything the daemon has not answered for counts as "not healthy yet", never as "this roll failed": a
# socket hiccup while a replica is being created has to cost a poll, not a stopped deployment.
healthy_count() {
  local n=0 id state
  # shellcheck disable=SC2046  # container ids are hex; splitting the list is the point
  for id in $(running_ids); do
    state="$(health_of "${id}")"
    if [ "${state}" = "healthy" ]; then
      n=$((n + 1))
    fi
  done
  echo "${n}"
}

# List what the daemon reports, optionally flagging the replica the caller was about to stop. Without the
# flag a refusal reads as a contradiction — the victim itself is usually perfectly healthy, and the operator
# wonders why a script refused while showing a healthy node. It refused because some *other* replica has to
# be there to cover the gap.
report_replicas() {
  local victim="${1:-}" id state flag
  # shellcheck disable=SC2046  # container ids are hex; splitting the list is the point
  for id in $(running_ids); do
    state="$(health_of "${id}")"
    flag=""
    if [ "${id}" = "${victim}" ]; then
      flag="   <- the replica this roll wanted to stop (its own health is not what the guard checks)"
    fi
    echo "    ${id} ${state}${flag}"
  done
}

# Wait until the whole cluster is back at REPLICAS healthy nodes. Poll-based rather than "wait for the one
# container that is new": a roll can legitimately create nothing (the cluster was already at or above the
# target), and then the thing worth waiting for is exactly this invariant.
wait_for_cluster() {
  local waited=0 healthy
  while true; do
    healthy="$(healthy_count)"
    if [ "${healthy}" -ge "${REPLICAS}" ]; then
      # Above REPLICAS is legitimate (the cluster may still carry replicas an earlier, larger --scale left
      # behind), so phrase the required number as the floor it is instead of printing "3/2".
      echo "  ${healthy} replica(s) healthy, at or above the ${REPLICAS} this cluster must keep"
      return 0
    fi
    if [ "${waited}" -ge "${HEALTH_WAIT}" ]; then
      echo "  only ${healthy} of the ${REPLICAS} required replicas healthy after ${HEALTH_WAIT}s" >&2
      return 1
    fi
    sleep "${HEALTH_POLL_SECONDS}"
    waited=$((waited + HEALTH_POLL_SECONDS))
  done
}

# The rule the whole script exists for: a replica only goes down while *another* one is up and answering its
# healthcheck. Both numbers come from the same listing, so a cluster that is only half up stops the roll
# instead of joining it. What "healthy" is here: `.State.Health.Status`, i.e. the liveness probe — see the
# header for why that is weaker than "the peer will take my triggers over", and why nothing in this script can
# close the gap.
peer_is_healthy() {
  local victim="$1" id state
  # shellcheck disable=SC2046  # container ids are hex; splitting the list is the point
  for id in $(running_ids); do
    if [ "${id}" = "${victim}" ]; then
      continue
    fi
    state="$(health_of "${id}")"
    if [ "${state}" = "healthy" ]; then
      return 0
    fi
  done
  return 1
}

for knob in "REPLICAS:SCHEDULER_REPLICAS" "GRACE:SCHEDULER_STOP_GRACE" "HEALTH_WAIT:SCHEDULER_HEALTH_WAIT"; do
  var="${knob%%:*}"
  env_name="${knob##*:}"
  case "${!var}" in
    '' | *[!0-9]*) die "${env_name} must be a number, got '${!var}'" ;;
  esac
done

[ -f "${COMPOSE_FILE}" ] || die "compose file not found: ${COMPOSE_FILE} (run it from a checkout or set COMPOSE_FILE)"
# One round trip does both jobs: it proves the daemon is there (this roll is pointless without it) and it
# answers *which* daemon, in the daemon's own words rather than via DOCKER_HOST or the current context, which
# is the half of the lock key that says "the cluster I am about to touch". A daemon that answers with no name
# gets no lock key, so this dies instead of guessing one — see LOCK_BASE_DIR above.
if ! DAEMON_NAME="$(docker info -f '{{.Name}}' 2>/dev/null)"; then
  die "docker daemon is not reachable; refusing to roll the scheduler cluster"
fi
DAEMON_KEY="$(normalize_name "${DAEMON_NAME}")"
[ -n "${DAEMON_KEY}" ] \
  || die "the docker daemon answered the preflight but reported no name, so this roll cannot key its lock on it; refusing to run unguarded"
# Compose's own rule for the project name, applied to the directory holding the compose file (COMPOSE_FILE is
# relative to PROJECT_DIR, which the script already cd-ed into, and which is where compose gets it too).
compose_project_raw="${COMPOSE_PROJECT_NAME:-$(basename "$(cd "$(dirname "${COMPOSE_FILE}")" && pwd)")}"
COMPOSE_PROJECT="$(normalize_name "${compose_project_raw}")"
[ -n "${COMPOSE_PROJECT}" ] \
  || die "the compose project name derives to nothing ('${compose_project_raw}' has no [a-z0-9_-] in it); cannot key the roll lock"
LOCK_DIR="${LOCK_BASE_DIR}/harnax-roll-scheduler-${COMPOSE_PROJECT}+${DAEMON_KEY}.lock"
compose config --services 2>/dev/null | grep -qx "${SERVICE}" \
  || die "no ${SERVICE} service in ${COMPOSE_FILE}"
# The v2-only listings, asked for once here instead of at the moment they matter: the roll later proves a
# stopped replica is gone with `ps --all`, and that call comes *after* a stop that may have used the whole
# grace period. Running the exact flag set the roll depends on is the point — a 1.x CLI that takes `--all` but
# has no `--status` fails here too — and so is the position: everything below this line may touch a container.
if ! compose ps --all --quiet --status exited --status created "${SERVICE}" >/dev/null 2>&1; then
  die "this docker-compose cannot list replicas with '--all --status', which is Compose v2 syntax. The roll depends on two such listings: the one that proves a stopped replica is really gone and the one that finds replicas an aborted roll left behind — the first of them runs after a replica is already down. Nothing has been touched. Point docker-compose at the v2 CLI (the 'docker compose' plugin, or a shim for it) and re-run, or take the cluster down deliberately instead of rolling it."
fi

# Everything above reads; from here the script may write. Lock first, so a second roll — another operator, a
# cron deploy, or a `deploy-service.sh scheduler` started by hand while one is already running — is refused
# instead of being trusted to coordinate on its own. What the lock does not cover is deploy-all.sh, which takes
# the whole stack down by design; nothing that stops every container is made safe by a roll lock.
acquire_lock

# The first listing is the strict one, because everything the script decides next keys off "how many replicas
# are up". If compose could not answer and the answer were taken as "none", the script would call that a cold
# cluster, run a --no-recreate up that recreates nothing, and exit 0.
if ! running_listing="$(list_running_strict)"; then
  die "cannot list the ${SERVICE} replicas from ${COMPOSE_FILE} even though the daemon answered the preflight. Refusing to guess: 'no replicas' and 'I cannot tell' look the same from here, and guessing wrong is how a roll reports a success it never performed."
fi
# shellcheck disable=SC2206,SC2046  # ids are hex; splitting the listing into the array is the point
containers=(${running_listing})

# A replica count this cluster cannot converge to has to be refused *before* the first command that could
# touch a container — not by the per-stop guard halfway through. With SCHEDULER_REPLICAS=1 and two replicas
# up, the top-up below would have already asked compose to scale the service down (a scale-down reaps
# containers on a stop timeout Docker picks, not our GRACE), and the loop would stop replica A, converge the
# cluster to one node, and only then refuse to stop B: half a roll done, the new image nowhere, and a smaller
# cluster than before it started. On a host where nothing runs yet, 1 is fine — that path brings replicas up
# and stops none.
if [ "${#containers[@]}" -eq 0 ]; then
  MIN_REPLICAS=1
else
  MIN_REPLICAS=2
fi
if [ "${REPLICAS}" -lt "${MIN_REPLICAS}" ]; then
  die "SCHEDULER_REPLICAS=${REPLICAS} cannot be rolled with ${#containers[@]} replica(s) running: this script keeps at least ${MIN_REPLICAS} node(s) in the cluster at every moment, and a target below that means either scaling the service down by hand (its own stop timeout, no peer check) or a roll that stops one replica and then refuses to touch the next. Set SCHEDULER_REPLICAS=${MIN_REPLICAS} or more, or take the cluster down deliberately."
fi

# shellcheck disable=SC2206,SC2046  # ids are hex; splitting the list into the array is the point
containers_stale=($(stale_ids))
if [ "${#containers_stale[@]}" -gt 0 ]; then
  echo "removing ${#containers_stale[@]} not-running scheduler replica(s) left behind"
  docker rm "${containers_stale[@]}" >/dev/null 2>&1 || true
fi

if [ "${#containers[@]}" -eq 0 ]; then
  # The only path that starts from an empty cluster, so it is the only one that wants its dependencies:
  # scheduler's depends_on gates mysql on service_healthy (plus admin/router being started), and bringing
  # replicas up against a database that is not there yet would report a deploy the containers immediately
  # restart-loop their way out of. --no-deps is what the roll paths below must keep — there the other
  # services are already serving and a roll has no business touching any of them while a replica is down.
  echo "no scheduler container running; bringing the cluster up at ${REPLICAS} replicas"
  compose up -d --scale "${SERVICE}=${REPLICAS}" --no-recreate "${SERVICE}" \
    || die "first bring-up failed: the cluster has no scheduler at all"
  exit 0
fi

echo "${#containers[@]} scheduler replica(s) running; rolling to ${REPLICAS}"

# Full strength before the first stop: with a single replica this is the step that adds the second node,
# and the one whose failure means nothing has been touched yet.
compose up -d --no-deps --scale "${SERVICE}=${REPLICAS}" --no-recreate "${SERVICE}" \
  || die "could not top the cluster up to ${REPLICAS} replicas; nothing was stopped, ${#containers[@]} replica(s) still serving"

if ! wait_for_cluster; then
  report_replicas >&2
  die "the cluster did not reach ${REPLICAS} healthy replicas before anything was stopped; nothing has been rolled, fix the replicas above first"
fi

for victim in "${containers[@]}"; do
  if ! peer_is_healthy "${victim}"; then
    report_replicas "${victim}" >&2
    die "refusing to stop ${victim}: no other replica is answering its healthcheck (see above). Rolling it now would leave the cluster without a scheduler."
  fi
  echo "stopping ${victim} (waiting up to ${GRACE}s for its in-flight execution)"
  if ! docker stop -t "${GRACE}" "${victim}" >/dev/null; then
    echo "  docker stop ${victim} failed — it may already be gone; checking before anything else"
  fi
  # Remove it rather than leave it stopped: `up --no-recreate` keeps a container it already knows about
  # and starts it again on the image it was created from, so this slot would never pick up the build the
  # deploy just made.
  docker rm "${victim}" || echo "  docker rm ${victim} reported a failure (see above)"

  # And then prove that removal instead of assuming it. If the container survived — a daemon that refused the
  # rm, a stop that timed out into a process that would not die — the `up --no-recreate` two lines below
  # starts it again on the old image, the health wait counts it as one of the healthy replicas, and the roll
  # exits 0 reporting a deployment that is running the image the deploy replaced. The check is against the
  # all-status listing, not the running one: "stopped but not removed" is exactly the state that has to fail
  # here, and a running-only listing would have called it gone.
  if ! existing="$(list_existing_strict 2>/dev/null)"; then
    report_replicas "${victim}" >&2
    # Say what was just measured, not what this roll would have liked: the stop above may have burned the whole
    # grace period, so whatever peer covered this replica before the stop is not a fact about now.
    healthy_now="$(healthy_count)"
    die "cannot list ${SERVICE} replicas after removing ${victim}, so cannot confirm it is gone. Refusing to bring the replacement up without that proof — ${victim} may still exist and --no-recreate would reuse it. Just re-read, after a stop that may have used the whole ${GRACE}s: ${healthy_now} replica(s) of this project answer healthy right now (the listing above is every replica the daemon had, with the state each reported). If that is not at least one, the cluster has no scheduler until you fix a replica — start one with 'docker-compose -f ${COMPOSE_FILE} up -d --no-deps --scale ${SERVICE}=${REPLICAS} ${SERVICE}' and watch it. Then clear ${victim} (docker ps -a, docker rm -f ${victim} if it is wedged) and re-run this script: the replicas above are what the cluster has, and this roll stopped there."
  fi
  if list_has "${victim}" "${existing}"; then
    report_replicas "${victim}" >&2
    die "${victim} still exists after docker stop + docker rm. Refusing to run the replacement up over it: --no-recreate would start this same container on the image it was created from, so the roll would report the new build while running the old one. Clear it by hand (docker ps -a, then docker rm -f ${victim} if it is wedged) and re-run this script — the replicas above are what the cluster has, and this roll stopped there."
  fi

  echo "waiting for the replacement to report healthy"
  if ! compose up -d --no-deps --scale "${SERVICE}=${REPLICAS}" --no-recreate "${SERVICE}"; then
    report_replicas "${victim}" >&2
    die "compose up failed while replacing ${victim}; the replicas above are what the cluster has left"
  fi
  if ! wait_for_cluster; then
    report_replicas "${victim}" >&2
    die "the replacement for ${victim} did not make the cluster healthy again; the replicas above are what it has now — fix this node before rolling any further"
  fi
done

echo "cluster rolled; QRTZ_SCHEDULER_STATE rows:"
# harnax_admin, not harnax_scheduler: in release 1 the scheduler's datasource — and with it the QRTZ_*
# cluster tables — still lives in admin's database.
#
# Do NOT read this as "expect ${REPLICAS} rows". A Quartz node never deletes its own state row, not even on a
# clean shutdown, and a peer only drops a dead instance's row as part of clusterRecover — after the interval
# its own check-in thread compares against, which with a 15s check-in and the default misfire threshold lands
# somewhere around 20~50s. So straight after a two-replica roll 3 or 4 rows is the normal answer: the live
# members plus the instances this roll just stopped. LAST_CHECKIN_TIME is what separates them — a member's
# stamp is unix-ms and moves every 15s, a corpse's does not. If you want the row count to equal the replica
# count, run this again a minute later; if you want proof a node joined at all, look for its own instance name
# advancing.
compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-root123456}" \
  -e "SELECT INSTANCE_NAME, LAST_CHECKIN_TIME, CHECKIN_INTERVAL FROM harnax_admin.QRTZ_SCHEDULER_STATE;" \
  || echo "  (run that query by hand if mysql is not reachable here)"
