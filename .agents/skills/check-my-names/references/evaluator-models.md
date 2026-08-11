# Evaluator Model Guidance

The evaluator is deliberately less capable than the coordinating agent. A stronger evaluator can supply missing meaning from broad knowledge and make an unclear name look successful. Lower cost is useful because the workflow uses one fresh evaluator per check request, but cost is not the goal.

## Selection Criteria

Choose a model that is:

- materially less capable than the coordinating agent;
- competent with general programming vocabulary and the target language's naming conventions;
- fresh for every check request; and
- instructed not to use tools, with tools disabled when the platform supports that boundary.

Do not choose a model so weak that it cannot understand ordinary language or language conventions. If an eligible lower-capability evaluator is unavailable, report the test as `invalid`; do not treat a same-capability evaluator as equivalent.

## Vendor Starting Points

These are examples, not requirements. Model availability and relative capability change; prefer the vendor's current small, low-cost, code-literate tier.

| Vendor | Starting point | Notes |
|--------|----------------|-------|
| Anthropic | Latest Haiku tier | A practical lower-capability evaluator when the coordinating agent is Sonnet or Opus. |
| OpenAI | GPT-5.6 Luna | OpenAI's efficient, high-volume tier. |
| Google | Gemini 3.5 Flash-Lite | Google's low-cost, high-throughput Flash-Lite tier. |

For current availability, consult the vendor's official model documentation before configuring automation.
