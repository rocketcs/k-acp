# Agent registration batch workflow

## Goal

Create a local workflow that generates ten agent-context records, registers each record with the auto-registration API in sequence, waits a random 1–30 seconds between registrations, and returns the ten registration results and API keys.

## Constraints

- The context generator must be `scripts/generate_agent_context.py` and produce `agent_context.json` with ten records.
- The workflow must make calls serially so the delay applies between individual requests.
- The workflow code node cannot run local processes, read files, or sleep: its security checker prohibits those capabilities.
- Registration is an external, quota-consuming operation. Saving or publishing the workflow must not execute it.
- API keys and complete HTTP response bodies are returned in the workflow result as requested; they must not be persisted to a shared workflow definition.

## Design

Two local, registered tools provide the capabilities intentionally excluded from the workflow code node.

1. `generate_agent_contexts`
   - Runs `python3 scripts/generate_agent_context.py --number 10 --output agent_context.json` from the project root.
   - Parses the generated file and returns the ten context objects as the tool result.
   - Validates that exactly ten objects were produced before returning.

2. `random_delay`
   - Accepts a minimum and maximum wait time in seconds.
   - Chooses an inclusive random delay between 1 and 30 seconds, blocks for that period, and returns the chosen delay.
   - Is executed only after successful registrations except the final item.

The workflow uses a data-iteration `LOOP` node over the generator result. Its subworkflow runs an `HTTP_EXTERNAL` node configured as a synchronous JSON POST to `https://ai.zhiliaobiaoxun.com/web-api/internal/auto-register`, with the current context object as the request body. A result-shaping node retains the context, complete HTTP response, `api_key`, `device_id`, `remaining_calls`, success state, and error data. The subworkflow then invokes `random_delay` when another iteration remains.

The loop output is a ten-element result array. The final node returns a JSON object containing `results`, `api_keys` (successful items only), `success_count`, and `failure_count`.

## Error handling

- HTTP uses a 10-second connection timeout, 30-second read/write timeouts, and up to three retries for transient 429 and 5xx statuses.
- A failed or malformed response becomes a failed result item; it does not abort subsequent registrations.
- A missing `api_key` is treated as failure even if an HTTP response was received.
- The random delay is not applied after the tenth successful registration and is skipped after a failed registration.

## Validation

- Verify generator output is valid JSON with ten distinct context objects.
- Validate the saved workflow structure before publishing.
- Run the workflow once only after the user explicitly chooses to consume ten registration calls.
- Confirm the final result has ten items and exactly one API key for each successful registration.
