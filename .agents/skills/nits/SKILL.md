---
name: nits
description: Use to perform an adversarial review of a plan or design by delegating to a specialized subagent. This ensures objectivity and protects the primary agent's context from contamination while providing a skeptical audit of proposed implementations.
---

# Nits: Adversarial Plan Review (Delegation)

When this skill is activated, you (the Primary Agent) must delegate the review task to a specialized subagent (e.g., `generalist` or another capable codebase investigator). This ensures an "outside-in" skeptical perspective and prevents confirmation bias.

## Instructions for Primary Agent

1.  **Identify the Plan**: Capture the current plan, design document, or proposed steps that need reviewing.
2.  **Invoke Subagent**: Delegate the task to an appropriate subagent (e.g., `generalist`).
3.  **Construct the Delegation Prompt**: Use the following template, ensuring the file paths are correctly resolved relative to the workspace root:

```markdown
# ADVERSARIAL AUDIT REQUEST

I need you to perform a skeptical, "pre-mortem" audit of the following plan. Your goal is to identify why this plan will FAIL.

## Your Instructions
1. Read your specialized persona and workflow from `skills/nits/references/adversarial-reviewer.md`.
2. Follow the audit vectors defined in `skills/nits/references/attack-surface.md`.
3. Use your tools (search, read, etc.) to verify the codebase and falsify the plan's assumptions.
4. Report back with your findings using the "Reporting to Primary Agent" format.

## The Plan to Review
[INSERT PLAN CONTENT HERE]
```

4.  **Process Findings**: Review the material risks and unverified assumptions. Use these findings to refine your plan or address the risks before proceeding with implementation.

## Why Delegate?
- **Objectivity**: A subagent acts as an independent auditor, reducing confirmation bias.
- **Context Efficiency**: Complex investigation logs are handled by the subagent, keeping the Primary Agent's history lean.
- **Critical Skepticism**: The subagent is explicitly prompted to be adversarial and falsify the approach, looking for "showstoppers" rather than literal "nits".
