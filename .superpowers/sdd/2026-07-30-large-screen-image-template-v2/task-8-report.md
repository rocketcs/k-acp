# Task 8 report — preserve persisted AG-UI user-message identity

## Change

- `ui/src/views/Chat/index.vue`: the normal `submitMessage` path now supplies `userMsg.data.data.id` on the runtime user message after that message has been persisted.
- `ui/src/views/Chat/runtimeMessageId.test.mjs`: focused regression coverage asserts that the persisted ID reaches the runtime message and that `useChatStream` preserves it through `String(m.id)`.

The change leaves display/DOM IDs, feature provenance, attachments, session creation, and Chat opt-in behavior untouched.

## TDD record

### Red

Command:

```sh
pnpm --dir ui exec node --test src/views/Chat/runtimeMessageId.test.mjs
```

Outcome: failed as expected (1 test failed, 0 passed). The assertion expected the runtime message to include `id: userMsg.data.data.id`; the current production call contained only `role` and `content`, reproducing the source of the outgoing `"undefined"` ID.

### Green

Command:

```sh
pnpm --dir ui exec node --test src/views/Chat/runtimeMessageId.test.mjs
```

Outcome: passed (1 test passed, 0 failed).

## Regression and type checks

Command:

```sh
pnpm --dir ui exec node --experimental-strip-types --test src/features/large-screen-image/submission.test.ts src/features/large-screen-image/messageDisplay.test.ts
```

Outcome: passed (25 tests passed, 0 failed). This includes the persisted-provenance and stream-to-DOM key-bridge coverage.

Command:

```sh
pnpm --dir ui type-check
```

Outcome: passed (`vue-tsc --build` exited successfully).

Command:

```sh
git diff --check
```

Outcome: passed (no output).

## Graph maintenance

Command:

```sh
graphify update .
```

Outcome: completed successfully. Graphify reported its installed skill/package version mismatch and pre-existing optional SQL parser warnings; no tracked `graphify-out/` changes remained after the update.
