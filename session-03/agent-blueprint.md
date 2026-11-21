# Agent blueprint: Parts Reservation

Companion to `implementation-plan.md`. No orchestrator yet (trainer's advice): the human runs each agent in order and
approves between steps. Every agent reads its context from files, so changing a rule means editing a document, not the agent.

## Agents

| Agent | Status | One job | Reads | Writes | Stops and asks the human when |
|---|---|---|---|---|---|
| `ba-agent` | Existing, **unchanged** (50 lines) | Turn requirements into a spec | `session-02/requirements.md`, `pipeline/features/*`, `pipeline/decisions/*`, `pipeline/rules/*` | `pipeline/features/feature-N-parts-reservation.md` | the feature number clashes, or a requirement is ambiguous |
| `design-agent` | **New**, under 60 lines | Draft or update the ADR from the spec | the spec, `pipeline/rules/*`, existing ADR | `architecture-adr.md` | the spec and a standing rule conflict |
| `design-review-agent` | **New**, under 60 lines | Review the ADR with Ask, Challenge, Decide. Never edits the ADR. | ADR, spec, the backend code | `design-review.md` | it finds a drawback it cannot rank |
| `developer-agent` | Existing, **unchanged** (38 lines) | Build one plan task at a time | spec, ADR, `implementation-plan.md`, skills, rules | code under `backend/` | a task needs `Job`, `bookJob` or `ApiError` changed |
| `tester-agent` | Existing, **unchanged** (33 lines) | Test against the spec's Definition of Done, report by R-number | spec, plan, code | tests, test report | a test cannot run, or code does not meet the spec |

`bugfix-agent` (Day 2) is separate. It is not part of this flow.

## Handoff order, inputs and outputs

| Step | Agent | Needs (approved) | Returns | Human approves |
|---|---|---|---|---|
| 1 | ba-agent | requirements | committed spec | **Gate A:** spec and feature number |
| 2 | design-agent | spec | ADR | **Gate B:** ADR |
| 3 | design-review-agent | ADR + spec | review with Approve / Improve / Alternative | **Gate C:** which improvements to accept, then ADR is updated |
| 4 | plan (human or design-agent) | approved ADR + review | implementation plan | **Gate D:** plan |
| 5 | developer-agent | spec, ADR, plan task T1 | data models | none (low risk) |
| 6 | developer-agent | T2 | protected Reserve action | **Gate E:** review of the high-risk action |
| 7 | developer-agent | T3 | endpoints | none |
| 8 | tester-agent | spec + code (starts only after T3 exists) | tests plus pass/fail by R-number | **Gate F:** test report |
| 9 | developer-agent | tests passed | decision log | **Gate G:** decision log |

## Every agent reports
Code or document produced, tests run and results (never claim a pass that was not run), and **unanswered questions**.

## Rules that apply to all agents
- **Error rule:** retry a failing step at most 2 times, then stop and report what failed. No endless retries (tokens cost money).
- **Scope rule:** do only the assigned task. Never change a spec to fit code. Never change `Job`, `bookJob`, `ApiError`.
- **Context rule:** read the ADR and rules from files each run. If a rule changes, update the document, then re-run. Do not edit agents for rule changes.
- **Size rule:** each agent file stays under about 60 lines. Add another small agent instead of growing one.
- **Update order:** if a rule changes, fix the earliest affected output (requirements, ADR, review, plan, blueprint) first, then the rest.

## Human decisions and trade-offs
- Priority: preventing double reservation comes before any extra feature.
- Trade-off: safety over a faster first draft, so two review gates (C, E) before most code exists.
- The human records approval or the change needed at each gate. Agents never self-approve.

## Not included now
An orchestrator agent, Jira/MCP integration, model choice per agent, cost tuning (later sessions).

## Agent files
Created: `.claude/agents/design-agent.md` and `.claude/agents/design-review-agent.md` (front matter, role, inputs, output,
rules). Not committed yet.
