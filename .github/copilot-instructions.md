# GitHub Copilot Instructions

This repository contains the **Spring Boot backend** for Server Orchestrator.
Use it for API implementation, security, persistence, orchestration logic, and backend tests.

## Repository Scope

Prefer changes in this repository for:
- Spring Boot application code under `src/main/java`
- Configuration under `src/main/resources`
- Database schema migrations (Flyway)
- Security, RBAC, and authorization enforcement
- Integration and unit tests under `src/test/java`

Avoid placing frontend or API contract source files here unless explicitly requested.
Those belong in their dedicated repositories.

## Planning Documents

All backend-specific ideas, architecture notes, and implementation plans are stored under `plans/*.md`.
Before proposing major backend changes, review relevant plan documents first.
Example: `plans/postgres-desired-vs-k8s-observed-reconciliation.md`.

## Project Context

Server Orchestrator is a Kubernetes-first platform for managing containerized game servers with:
- Multi-tenant namespace isolation
- Template-driven provisioning
- Namespace RBAC and controlled delegation
- Resource and file management workflows
- Unified UI and API for operators and automation

This backend is the control-plane implementation of that domain.

## Technology Stack

- Java 25
- Spring Boot 4.x
- Spring Web MVC
- Spring Security + OAuth2 Resource Server
- Spring Data JPA
- Flyway + PostgreSQL
- Maven wrapper (`./mvnw`, `mvnw.cmd`)
- Testcontainers for integration tests

## How Copilot Should Assist Here

When creating or editing code in this repo:
1. Preserve clear layering (web, service, domain, persistence, security)
2. Keep business rules in services, avoid pushing logic into controllers
3. Validate inputs at API boundaries and fail with explicit, stable error responses
4. Enforce authorization checks on all read and write paths
5. Prefer explicit mappings (DTO <-> entity), avoid leaking persistence models in API payloads
6. Keep methods focused and side effects obvious
7. Do not use em dashes, use commas, parentheses, or colons instead

## API and Contract Guidance

- Treat the OpenAPI repository as the source of truth for API contracts
- Do not invent response shapes that diverge from the contract
- If backend implementation requires contract changes, call that out clearly
- Use predictable REST semantics and status codes
- Keep pagination, filtering, and sorting behavior consistent across endpoints

## Security and RBAC Guidance

- Deny by default, grant by explicit permission
- Enforce tenant (namespace) boundaries on every data access path
- Never trust client-supplied namespace or ownership values without server-side checks
- Keep secrets and sensitive fields masked in logs and responses
- Ensure mutations are audit-log friendly (actor, action, target, timestamp)

## Persistence and Migration Guidance

- Use Flyway for schema changes, never rely on ad-hoc schema drift
- Keep migrations forward-only and deterministic
- Add indexes and constraints that enforce domain invariants
- Model entity relationships explicitly, avoid hidden cascading side effects
- Keep transaction boundaries in service layer methods

## Testing Guidance

When adding or modifying backend behavior:
- Add unit tests for business logic
- Add integration tests for persistence, security, and API behavior
- Prefer Testcontainers-backed tests for PostgreSQL-dependent features
- Verify both success and failure paths (403, 404, 409, validation errors)
- Keep tests deterministic and isolated

## Operational Guidance

- Keep Actuator endpoints secure and minimally exposed
- Prefer configuration via `application.yaml` and environment variables
- Avoid hardcoded infrastructure values in code
- Keep startup behavior compatible with Docker Compose and Kubernetes environments

## Quality Bar

Changes produced in this repository should be:
- Correct with respect to RBAC and tenant isolation
- Consistent with Server Orchestrator domain language
- Backed by tests for meaningful behavior changes
- Small, reviewable, and maintainable
- Explicit about follow-up work that belongs in API or frontend repositories