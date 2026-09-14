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
# Usage: docker-new/roll-scheduler.sh
#
# Knobs, all optional:
#   SCHEDULER_REPLICAS    replicas the cluster must end with (default 2)
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

cd "$PROJECT_DIR"

die() {
  echo "❌ $*" >&2
  exit 1
}

compose() {
  docker-compose -f "$COMPOSE_FILE" "$@"
}

# Ids of the scheduler replicas compose considers running, space separated. A listing that fails comes back
# empty, which is the safe direction: it can only lead to the bring-up path or to a health-check timeout,
# never to stopping a replica that is serving.
running_ids() {
  compose ps --quiet "$SERVICE" 2>/dev/null | tr '\n' ' ' || true
}

# Replicas that exist but are not running. An exited replica left behind by an aborted roll is invisible to
# the listing above yet still belongs to the service, so `up --no-recreate` can count it as one of the
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

report_replicas() {
  local id state
  # shellcheck disable=SC2046  # container ids are hex; splitting the list is the point
  for id in $(running_ids); do
    state="$(health_of "${id}")"
    echo "    ${id} ${state}"
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
      echo "  ${healthy}/${REPLICAS} replicas healthy"
      return 0
    fi
    if [ "${waited}" -ge "${HEALTH_WAIT}" ]; then
      echo "  ${healthy}/${REPLICAS} replicas healthy after ${HEALTH_WAIT}s" >&2
      return 1
    fi
    sleep "${HEALTH_POLL_SECONDS}"
    waited=$((waited + HEALTH_POLL_SECONDS))
  done
}

# The rule the whole script exists for: a replica only goes down while another one is up and healthy.
# Both numbers come from the same listing, so a degraded cluster stops the roll instead of joining it.
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

# shellcheck disable=SC2206,SC2046  # ids are hex; splitting the list into the array is the point
containers=($(stale_ids))
if [ "${#containers[@]}" -gt 0 ]; then
  echo "removing ${#containers[@]} not-running scheduler replica(s) left behind"
  docker rm "${containers[@]}" >/dev/null 2>&1 || true
fi

# shellcheck disable=SC2206,SC2046  # ids are hex; splitting the list into the array is the point
containers=($(running_ids))

if [ "${#containers[@]}" -eq 0 ]; then
  echo "no scheduler container running; bringing the cluster up at ${REPLICAS} replicas"
  compose up -d --no-deps --scale "${SERVICE}=${REPLICAS}" --no-recreate "${SERVICE}" \
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
    report_replicas >&2
    die "refusing to stop ${victim}: no other replica is healthy (see above). Rolling it now would leave the cluster without a scheduler."
  fi
  echo "stopping ${victim} (waiting up to ${GRACE}s for its in-flight execution)"
  if ! docker stop -t "${GRACE}" "${victim}" >/dev/null; then
    echo "  docker stop ${victim} failed — it may already be gone; continuing with the replacement"
  fi
  # Remove it rather than leave it stopped: `up --no-recreate` keeps a container it already knows about
  # and starts it again on the image it was created from, so this slot would never pick up the build the
  # deploy just made.
  docker rm "${victim}" >/dev/null 2>&1 || true

  echo "waiting for the replacement to report healthy"
  if ! compose up -d --no-deps --scale "${SERVICE}=${REPLICAS}" --no-recreate "${SERVICE}"; then
    report_replicas >&2
    die "compose up failed while replacing ${victim}; the replicas above are what the cluster has left"
  fi
  if ! wait_for_cluster; then
    report_replicas >&2
    die "the replacement for ${victim} did not make the cluster healthy again; the replicas above are what it has now — fix this node before rolling any further"
  fi
done

echo "cluster rolled; QRTZ_SCHEDULER_STATE rows:"
# harnax_admin, not harnax_scheduler: in release 1 the scheduler's datasource — and with it the QRTZ_*
# cluster tables — still lives in admin's database. Expect ${REPLICAS} rows, CHECKIN_INTERVAL 15000; a
# missing row means that node never joined the cluster.
compose exec -T mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-root123456}" \
  -e "SELECT INSTANCE_NAME, CHECKIN_INTERVAL FROM harnax_admin.QRTZ_SCHEDULER_STATE;" \
  || echo "  (run that query by hand if mysql is not reachable here)"
