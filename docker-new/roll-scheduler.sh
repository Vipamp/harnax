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
# registered under this service name" is therefore a topology rule the release docs own — see the
# two-instance section of docs/deploy-harnax-scheduler.md — and this script's guarantee has to be read as
# "another replica is alive", never as "another replica will take my triggers over".
#
# Two rolls of the same cluster at once defeat all of the above (each one sees the other's replica healthy and
# each stops its own), so the script holds an exclusive lock for its whole run and refuses to start without
# it. See LOCK_DIR below.
#
# Usage: docker-new/roll-scheduler.sh
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
# Keyed to the checkout and placed somewhere both an operator shell and a cron deploy agree on; the deploy
# host has exactly one. Two checkouts of this repo on one host would share a compose project name anyway
# (both derive it from the compose file's directory, `docker-new`) and collide at the daemon long before
# anything a lock guards against.
LOCK_DIR="${TMPDIR:-/tmp}/harnax-roll-scheduler-$(basename "${PROJECT_DIR}").lock"

cd "$PROJECT_DIR"

die() {
  echo "❌ $*" >&2
  exit 1
}

compose() {
  docker-compose -f "$COMPOSE_FILE" "$@"
}

release_lock() {
  # The trap runs on a clean exit, a die and a Ctrl-C alike; only SIGKILL leaves the directory behind.
  [ -n "${LOCK_DIR}" ] && rm -rf "${LOCK_DIR}"
  return 0
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
  local holder
  if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
    holder="$(cat "${LOCK_DIR}/owner" 2>/dev/null || echo 'holder unknown')"
    die "another scheduler roll is already running: ${holder}. Two concurrent rolls can each stop their own replica and leave the cluster with no scheduler, so this one refuses to start. Wait for the running roll to finish; if you are certain none is (the last one was SIGKILLed), remove ${LOCK_DIR} by hand."
  fi
  printf 'pid=%s user=%s started=%s cwd=%s\n' \
    "$$" "${USER:-unknown}" "$(date '+%Y-%m-%d %H:%M:%S %z')" "${PROJECT_DIR}" \
    > "${LOCK_DIR}/owner" 2>/dev/null || true
  trap 'release_lock' EXIT INT TERM
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
docker info >/dev/null 2>&1 || die "docker daemon is not reachable; refusing to roll the scheduler cluster"
compose config --services 2>/dev/null | grep -qx "${SERVICE}" \
  || die "no ${SERVICE} service in ${COMPOSE_FILE}"

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
    die "cannot list ${SERVICE} replicas after removing ${victim}, so cannot confirm it is gone. Refusing to bring the replacement up without that proof — ${victim} may still exist and --no-recreate would reuse it. At least one healthy peer is still serving: check 'docker-compose -f ${COMPOSE_FILE} ps --all ${SERVICE}', then re-run this script."
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
