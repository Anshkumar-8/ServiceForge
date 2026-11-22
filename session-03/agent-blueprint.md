# Agent blueprint: Parts Reservation

Planning only; no agents were run for this document. No orchestrator: the human runs each agent in order.

| Agent | Assigned work | Input needed | Output produced | Handoff to |
|---|---|---|---|---|
| Requirements (`ba-agent`, existing, unchanged) | Turn the feature idea into requirements and acceptance criteria | Feature idea, existing specs, decisions and rules | Requirements/spec file | Design agent, after human review |
| Design (`design-agent` and `design-review-agent`) | Draft the ADR, then review it with Ask / Challenge / Decide | Approved requirements, rules, backend code | ADR and design review | Build agent, after human design approval |
| Build (`developer-agent`, existing) | Implement one plan task at a time | Approved ADR, plan task, rules | Backend and UI code | Test agent |
| Test (`tester-agent`, existing) | Write and run tests against the acceptance criteria | Requirements, ADR, code | Test report by case | Human, for delivery approval |

**Order:** Requirements -> Design -> Build (T-01 to T-05) -> Test (T-06). Each agent has one small job and reads its context from files, so a changed rule means editing a document, not the agent. Each agent file stays under about 60 lines.

## Human approvals (separate from the critic)
1. **Design approval** before any building starts (requirements, ADR and review).
2. **Review of T-02** before T-03, because it is the highest-risk task.
3. **Test-result approval** before delivery.

The **reservation critic** is different: it is a check inside the application that runs on every Confirm. It is not a build agent and not a human approval. A human approving the design does not finalize any reservation, and a passing critic does not approve the design.

## Missing information, failures and stopping
- **Missing information:** the requesting agent stops and lists the question; the **human** answers, and the answer is written into the requirements or ADR (earliest affected document first).
- **Failed tests:** the Test agent reports the case and stops. The **Build agent** fixes code (the spec does not change to fit the code); the Test agent re-runs.
- **Stop and ask for help:** after 2 failed attempts at the same step, if two documents disagree, or if a task would change Feature 1 scheduling or the travel buffer.
- Never claim a test passed unless it was run.

## Journal (draft: edit in your own words)
I moved from writing the overlap fix by hand to deciding whether the AI's requirements and designs were right. I first chose a 15-minute reservation expiry, then dropped it when the AI pointed out it needs a Confirm step to make sense; this task now adds a Confirm step anyway, so that "no expiry" decision needs revisiting. I accepted the AI's idea to keep stock as a calculated number instead of subtracting from it, because release-twice becomes safe by design. Still unclear: whether unconfirmed holds should expire, who may release a reservation, and whether the critic should be an AI agent.
