# AGENTS.md

## Purpose
You are working on a third-party codebase that is not originally written by the current operator.
Your primary goal is to analyze the system and produce a reliable system analysis document.
Do not assume undocumented intent. Infer carefully from code, configuration, runtime behavior, and repository evidence.

## Core operating mode
Default to "analyze first, modify last".
Unless explicitly asked to change code, do not refactor business logic or rewrite files unnecessarily.
Prefer reading, tracing, summarizing, and documenting over editing.

## Main deliverable
Produce a document at:

/docs/system-analysis.md

If `/docs` does not exist, create it.

## Required output structure
The system analysis document must contain these sections in this exact order:

1. Executive Summary
2. Project Overview
3. Business Purpose Inferred From the Codebase
4. High-Level Architecture
5. Runtime and Deployment Model
6. Directory and Module Breakdown
7. Request/Data Flow
8. Core Domain Model
9. External Dependencies and Integrations
10. Configuration and Environment Variables
11. Database and Persistence Layer
12. Authentication and Authorization
13. Background Jobs / Queues / Schedulers
14. API Surface / UI Surface
15. Build, Test, and Local Run Instructions
16. Observed Design Patterns and Conventions
17. Risks, Technical Debt, and Unknowns
18. Glossary
19. Evidence Appendix

## Analysis rules
- Ground every important claim in repository evidence.
- Use only evidence you can directly inspect: source files, configs, docs, tests, Docker files, CI files, migration files, and scripts.
- Distinguish clearly between:
    - Confirmed
    - Strongly inferred
    - Unclear / unknown
- Do not present guesses as facts.
- When evidence conflicts, record the conflict explicitly.
- Prefer the current implementation over outdated README claims.
- If there are multiple apps/services in one repo, document each separately and then explain how they connect.

## Evidence requirements
For every major section, include:
- relevant file paths
- short evidence notes
- why that evidence supports the conclusion

At the end of the document, include an "Evidence Appendix" with bullets in this format:

- Claim:
- Confidence: Confirmed | Inferred | Unclear
- Evidence:
    - path/to/file.ext
    - path/to/other/file.ext
- Notes:

## Repository inspection checklist
You must inspect, when present:
- README and docs
- package manifests and lockfiles
- build scripts
- Docker / compose / deployment manifests
- CI workflows
- env templates
- migrations / schema files
- API route definitions
- framework entrypoints
- test files
- infra directories
- monitoring / logging setup
- auth-related middleware
- job worker / cron definitions

## Priority reading order
Start with the smallest set of files that reveals the overall architecture:
1. README / docs
2. package manifests / build files
3. app entrypoints
4. routing layer
5. config and env files
6. persistence layer
7. integration clients
8. tests
9. deployment and CI

Expand only as needed.

## Architecture extraction instructions
When analyzing architecture, explicitly identify:
- application type: monolith, modular monolith, microservices, SPA + API, server-rendered app, CLI, worker, library, etc.
- primary runtime(s)
- entrypoints
- major modules and boundaries
- inbound interfaces
- outbound integrations
- persistence technologies
- async processing mechanisms
- deployment assumptions

## Data flow instructions
Trace at least 3 representative flows if applicable:
- user request flow
- write/update flow
- background or async flow

For each flow, describe:
- trigger
- entrypoint
- key modules touched
- validations/auth checks
- persistence or external calls
- output/result

## Unknowns handling
If something cannot be proven from the repo, add it to "Risks, Technical Debt, and Unknowns".
Examples:
- missing environment contracts
- ambiguous ownership boundaries
- dead code suspicion
- undocumented side effects
- unclear production topology

## Editing constraints
Do not change application code unless the operator explicitly asks.
Allowed edits by default:
- create `/docs/system-analysis.md`
- create supporting diagrams as markdown if useful
- create a short `/docs/repo-map.md` only if it helps the main analysis

## Diagram format
If architecture is non-trivial, include one Mermaid diagram in the document for:
- high-level component architecture
  and optionally one for:
- request/data flow

Keep diagrams simple and text-renderable.

## Validation before finishing
Before finalizing:
- verify all major claims against at least one concrete file path
- remove unsupported statements
- mark uncertain conclusions clearly
- ensure the document is understandable to a new engineer in under 10 minutes

## Final response format
When reporting back to the operator:
1. summarize what the system appears to be
2. mention where the document was created
3. list the biggest unknowns
4. mention whether conclusions are confirmed or inferred