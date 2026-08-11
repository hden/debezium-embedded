---
name: check-my-names
description: Check whether proposed or existing code names convey their intended responsibility to a fresh, lower-capability evaluator. Use when reviewing candidate names, naming public APIs, types, modules, or packages, or auditing names in a PR without changing code.
---

# Check My Names

Let a fresh reader explain them back.

Treat each code name as a message. A fresh reader should be able to state its responsibility without seeing the implementation.

## Prepare the Test

1. Collect one or more code names that are candidates for the same kind of thing. Include any package, module, namespace, or owner context that helps distinguish the name. Do not include signatures, documentation, source, tests, or adjacent identifiers.
2. Before involving an evaluator, write the intended responsibility for each name in one sentence. Derive it from source, tests, documentation, or an explicit user statement.
3. Optionally provide shared domain vocabulary that a reader may reasonably know, such as the meaning of a project term. Do not include target-specific behavior, source facts, or the intended responsibility. Omit it when no shared vocabulary is needed.
4. Read [evaluator model guidance](references/evaluator-models.md) when choosing an evaluator.

## Run the Blind Test

Create one fresh evaluator context per check request. A request may contain one or many candidate names. Separate requests when they require different domain vocabulary or candidate kinds.

Keep the evaluator blind:

- Give it no source, tests, documentation, intended responsibility, or conversation history.
- When the platform supports it, disable filesystem, shell, editor, browser, network, MCP server, skill, and other tool access. Reading source is a cheat; writing is an incident.
- Always include the no-tools instruction in the evaluator prompt.

If the evaluator reads files, searches, uses a tool, or otherwise accesses information beyond the allowed context, the test is void. Report `invalid` for every name in that request instead.

Give the evaluator the candidate list using this prompt. Omit the `Allowed context` block when no shared vocabulary is needed:

```text
This is a naming test. Do not read files, search, or use tools. Answer only from
the names and any allowed context below. If you go looking, the test is void.

Allowed context:
<optional shared domain vocabulary>

For each name, write exactly one sentence guessing what it does from the words
alone. If a name tells you too little, answer CANNOT TELL. That answer is useful,
so do not strain.

These names are candidates for the same <candidate kind>. Answer each independently
as if it were the only one you had seen.

Code names to check:

- <code name 1>
- <code name 2>
- <code name 3>

Output a numbered list, one sentence or CANNOT TELL per name. No preamble,
commentary on your method, or ranking.
```

## Compare the Readback

Compare the evaluator's sentence with the intended responsibility. Assess two contracts separately:

- **Job alignment:** Does it identify the user problem or responsibility the name exists for?
- **Mechanism alignment:** Does it predict the method or output accurately enough? A branded skill name may intentionally rely on its description for this level.

Classify the result:

| Verdict | Meaning |
|---------|---------|
| `pass` | The inferred job agrees, and the mechanism is aligned or intentionally description-backed rather than claimed by the name. |
| `vague` | The inference is compatible but too broad, omits a material responsibility, or leaves a material mechanism unclear. |
| `misleading` | The inference creates an incompatible job, responsibility, or material mechanism expectation. |
| `opaque` | The evaluator cannot infer a useful responsibility. |
| `invalid` | The evaluator violated the blind-test boundary. Do not use this result. |

Use `description-backed` only for deliberately short or branded names whose description clearly supplies the omitted mechanism. Do not use it to excuse an incompatible expectation.

Report one row per input:

```markdown
| Code name | Intended responsibility | Blind readback | Job alignment | Mechanism alignment | Verdict |
|----------------|------------------------|----------------|---------------|---------------------|---------|
| `my.app.billing/send-payment-reminder!` | Sends reminders for unpaid invoices. | Sends payment reminders. | aligned | compatible but broad | vague |
```

## Boundaries

- Do not generate replacement names, refactor code, or change files. This skill only tests communication quality.
- Do not treat a strong model's plausible completion as evidence that the name communicates enough.
- Do not let the evaluator compare, rank, or revise candidates. It must answer each independently.

## Companion Skill: Evolutionary Naming

Use [evolutionary-naming](https://github.com/kawasima/evolutionary-naming) after a `vague`, `misleading`, or `opaque` result when the user wants to improve the identifier. It provides an incremental audit and improvement process; this skill provides the independent readback test.

Recommended loop: check a name here, improve it with [evolutionary-naming on skills.sh](https://www.skills.sh/kawasima/evolutionary-naming/evolutionary-naming), then rerun this check on the revised name.
