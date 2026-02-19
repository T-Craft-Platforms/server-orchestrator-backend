# Postgres Desired-State + Kubernetes Observed-State Reconciliation (Portainer-like)

## Context / Problem

We want a Portainer-like experience for managing containerized Minecraft servers on Kubernetes:

- **Postgres stores configuration** (templates, deployments, user intent, audit history).
- **Kubernetes is the execution plane** and can be changed out-of-band (kubectl, other controllers, node/cluster
  events).
- The system must stay correct, observable, and recoverable even when Kubernetes changes occur that were not initiated
  through our API.

This document defines an architecture where Postgres is the **system of record for desired state**, Kubernetes is the *
*system of record for actual runtime state**, and a **reconciler** continuously converges the two.

## Goals

- Store **desired configuration** and **history** in Postgres.
- Provide near-real-time **status** and **progress** based on observed Kubernetes state.
- Detect and handle **drift** (out-of-band changes) safely.
- Be resilient to restarts, transient failures, and partial outages.
- Enable future extension to multi-cluster and agent-based models.

## Non-Goals

- Perfectly mirroring every Kubernetes field into Postgres.
- Replacing Kubernetes controllers (we integrate with them; we do not re-implement them).
- Exactly-once semantics across all side effects (we aim for _idempotency_ + _at-least-once_ reconciliation).

---

## Core Concepts

### Desired vs Observed

- **Desired state**: The user-approved configuration we intend to apply. Stored in Postgres as a stable spec.
- **Observed state**: What exists in Kubernetes now (including status). Derived from watches/informers and on-demand
  reads.
- **Reconciliation**: A loop that computes the delta between desired and observed and applies changes to converge.

### "Spec is stable, status is derived"

To avoid fighting Kubernetes defaulting/mutation and status churn:

- Store _intent_ in Postgres (spec).
- Cache _observations_ in Postgres for UI speed, but treat it as a cache.

---

## Data Model (Postgres)

### 1. Templates

Stores base templates and allowed mutations (guardrails):

- `templates`
    - `id`
    - `name`
    - `version`
    - `base_spec_json` (or structured schema)
    - `allowed_mutations_json` (rules/constraints)
    - `created_at`

### 2. Deployments (Stacks / Server Instances)

System-of-record for desired configuration:

- `deployments`
    - `id` (UUID)
    - `template_id`, `template_version`
    - `namespace`
    - `desired_spec_json` (final spec after applying allowed modifications)
    - `generation` (int, monotonic; increments on desired changes)
    - `last_applied_generation` (int)
    - `desired_state` (enum: `Active`, `Paused`, `Deleted` etc.)
    - `status_summary` (enum/string: `Healthy`, `Progressing`, `Degraded`, `Error`)
    - `created_at`, `updated_at`

### 3. Resource Mapping (Kubernetes objects we manage)

Track objects we own and their identity:

- `deployment_resources`
    - `deployment_id`
    - `kind`
    - `namespace`
    - `name`
    - `uid` (Kubernetes UID)
    - `last_seen_at`
    - `managed` (bool; true if we own/manage)
    - unique index on (`kind`,`namespace`,`name`) optional (depends on design)

**Why UID matters**: names can be reused; UID distinguishes "same object updated" from "deleted and recreated".

### 4. Observed Cache (Optional but recommended)

- `deployment_observed`
    - `deployment_id`
    - `observed_json` (snapshot, include selected status, conditions, replica counts, etc.)
    - `observed_at`

This is a cache for UI and debugging. Kubernetes remains authoritative.

### 5. Event Log (Append-only)

- `deployment_events`
    - `id`
    - `deployment_id`
    - `source` (enum: `user`, `reconciler`, `k8s_watch`, `system`)
    - `type` (string: `DeploymentQueued`, `ResourceDeletedExternally`, `ReconcileStarted`, `ReconcileSucceeded`,
      `ReconcileFailed`, etc.)
    - `data_json`
    - `created_at`

Use events for UI progress, auditing, and post-mortems.

### 6. Jobs / Queue (Optional)

If reconciliation isn’t purely event-driven, use a DB-backed job queue:

- `jobs`
    - `id`
    - `type`
    - `payload_json`
    - `state` (`queued`, `running`, `dead`)
    - `attempts`, `run_at`
    - `locked_at`, `locked_by`

---

## Ownership Model in Kubernetes

### Labels / Annotations (required)

Every resource we create must include:

- `app.kubernetes.io/managed-by=server-orchestrator`
- `server-orchestrator.io/deployment-id=<uuid>`
- `server-orchestrator.io/generation=<int>`
- optional: `server-orchestrator.io/template-version=<x>`

These enable:

- fast listing/watching by deployment
- drift detection
- garbage-collection safety checks

### OwnerReferences (recommended where possible)

If we create a "root" object (could be a ConfigMap, or eventually a CRD), set OwnerReferences so child objects are tied
to it.

- This makes cleanup safer and more Kubernetes-native.

---

## Observing Kubernetes Changes

### Watch-first, poll as fallback

- Use watches/informers for primary updates (low latency).
- Use periodic resync (e.g., 30–120s) as a safety net.

Watch resource types you manage (depending on your design):

- Deployments/StatefulSets
- Services
- Ingress/Gateway/HTTPRoute (if applicable)
- ConfigMaps/Secrets (careful with secrets in logs/events)
- Pods (mostly for status aggregation)
- Events (optional; K8s events are noisy but useful)

### What gets recorded on watch events

On add/update/delete:

1. Update `deployment_resources` (`uid`, `last_seen_at`) when managed labels match.
2. Update `deployment_observed` cache (selected fields).
3. Append `deployment_events` entries for meaningful transitions/drift.
4. Trigger reconciliation for affected deployment.

---

## Drift Detection and Handling

### Drift categories

1. **Out-of-band edits to managed resources**
    - Example: someone changes env vars in a Deployment we manage.
2. **Out-of-band deletes**
    - Example: Service deleted manually.
3. **External environment changes**
    - Node drained, image pull failures, quota exceeded, etc. (not necessarily "drift", but affects observed state).

### Drift policies (per deployment / per resource-type)

**Enforce (default)**

- Reconciler restores desired state from Postgres.
- UI shows "externally modified; changes will be reverted".

**Adopt (explicit / guarded)**

- Only allowed if:
    - change is within permitted mutation rules, AND
    - user approves (or policy allows automatic adoption).
- System updates `desired_spec_json`, increments `generation`.

**Ignore (rare)**

- For fields or resources we intentionally do not manage.

Recommendation: start with **Enforce**; add **Adopt** later.

---

## Reconciliation Loop

### Triggers

- API writes new desired state (`generation++`).
- Watch event indicates drift or status change.
- Periodic resync timer.
- Manual "Redeploy / Reconcile now" button.

### Steps (idempotent)

1. Load desired spec and `generation` from Postgres.
2. Read observed state:
    - Prefer local cache, but do live GET for critical objects when applying changes.
3. Compute plan:
    - Create missing resources
    - Patch/update existing resources
    - Delete resources no longer desired (if we own them)
4. Apply changes to Kubernetes with idempotent operations (server-side apply recommended).
5. Write events for each major step.
6. On success:
    - set `last_applied_generation = generation`
    - update `status_summary` to healthy/progressing based on observed conditions

### Idempotency rules

- Reconcile may run multiple times; it must converge.
- Every "create" must handle "already exists".
- Every patch must be safe even if previous attempt partially applied.

### Rate limiting / backoff

- Use exponential backoff on repeated failures.
- Cap reconcile concurrency per cluster to avoid API overload.

---

## Postgres + Messaging (Optional): Outbox Pattern

If you later add RabbitMQ/NATS/Kafka for jobs/events:

- Write desired state + outbox event in the same DB transaction.
- A publisher relays outbox entries to the broker.
- Consumers run reconciliation / side effects.
  This prevents dual-write inconsistencies.

Even without a broker, the outbox table can drive in-process workers reliably.

---

## Multi-Cluster / Agent Model (Portainer-like future path)

If you will support multiple clusters:

- **Central control-plane** (API + Postgres) stores desired state.
- **Per-cluster agent/commander** runs:
    - watches
    - reconciliation
    - credentials stay local to the cluster

Postgres schema additions:

- `clusters` (id, name, endpoint metadata, trust model)
- `deployments.cluster_id`
- per-cluster RBAC/service accounts isolated

---

## Security / RBAC

Principles:

- API should not necessarily have broad cluster-admin.
- Reconcilers/agents should have the minimal RBAC for only the namespaces/resources they manage.
- Separate service accounts for:
    - read-only watchers
    - reconcilers (write permissions)
- Restrict secret reads; avoid storing secret values in event logs.

---

## Failure Modes and Recovery

### Kubernetes API temporarily unavailable

- Reconcile pauses, jobs retry with backoff.
- UI remains functional using last observed cache.
- Emit `ReconcileDeferred` events.

### Partial apply (some resources updated, others not)

- Next reconcile converges.
- Ensure steps are idempotent and ordered (e.g., ConfigMap before Deployment rollout).

### DB unavailable

- API returns errors for config changes.
- Reconcilers should fail safe; avoid uncontrolled changes.

### Watch misses events

- Periodic resync corrects state.
- Maintain `last_seen_at` and alert on stale deployments.

---

## Operational Requirements

- Metrics:
    - reconcile duration, success/failure counts
    - queue depth (if jobs table)
    - K8s API error rate, watch restarts
- Logs:
    - correlate reconcile attempts with `deployment_id` + `generation`
- Alerts:
    - reconcile failing repeatedly
    - drift detected repeatedly (possible fighting with another controller)
    - watch disconnected too long

---

## Implementation Milestones

1. Labeling/ownership conventions for all created resources.
2. Postgres schema: deployments + resources mapping + events.
3. Basic reconciler:
    - enforce desired state
    - periodic resync
4. Watchers/informers:
    - update observed cache
    - drift detection events
5. UI endpoints:
    - config from desired spec
    - status from observed cache
    - events stream (polling/SSE)
6. Hardening:
    - idempotency, retries/backoff
    - RBAC tightening
    - multi-cluster groundwork (optional)

---

## Acceptance Criteria

- A deployment created from a template converges to the desired Kubernetes objects.
- Manual deletion/modification of managed resources is detected and recorded.
- Reconciliation restores desired state (enforce policy) reliably.
- Postgres contains a durable audit trail of changes and reconciliation activity.
- System recovers from restart without losing track of deployments or ownership.
