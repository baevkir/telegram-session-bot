# Design: Characterization test suite (Spec 1 of 4)

**Date:** 2026-06-02
**Branch:** `characterization-tests` (off master after the telegrambots 10.0.0 merge)
**Status:** Approved design, ready for implementation plan

## Where this sits

This is the first of a sequenced program of specs agreed after a full multi-lens review:

1. **Characterization test suite** ← this spec — build the regression safety net first.
2. Runtime correctness & concurrency (groups A + B + E).
3. Starter conventions (group D).
4. Design refactor (group C).

Tests come first deliberately: Specs 2 and 4 reshape the reactive pipeline and the
dispatch engine, so a behavior-locking net must exist before they run.

## Goal

Establish a comprehensive, all-green test suite that locks the **current intended
behavior** of the dispatch engine and reactive flow, so the later concurrency rework and
design refactor can be verified to preserve behavior.

## Scope

**In scope:** characterization/unit tests for the stable behavior of the wire format,
command dispatch, parameter binding, method matching, session-context lifecycle, update
adaptation, error routing, and the per-chat reactive fold.

**Out of scope (deferred):**
- The 9 group-B bug behaviors — their tests land in Spec 2 alongside each fix (red→green),
  so this suite stays green and avoids rewrite churn.
- Concurrency-guarantee tests (per-chat ordering under contention, `sendMessage`
  thread-safety) — these verify behavior Spec 2 *introduces*, so they belong there.

This suite asserts **functional** behavior (routing / folding / binding / scoring), not
thread-safety.

## Test infrastructure

- JUnit 5 (Jupiter), AssertJ `3.27.7`, Mockito `5.17.0` — already on the test classpath.
- **Add `io.projectreactor:reactor-test` (test scope)** — confirmed absent; required for
  `StepVerifier`. Version managed by the Spring Boot 3.5 BOM.
- **Test fixtures** (`src/test/java/.../fixtures/`): a small set of `@BotCommand` sample
  command classes with `@CommandMethod` methods covering: literal+placeholder arg
  templates, required and optional (`scipAnswer`) parameters, each auto-injected parameter
  type, and a method that returns a `BotCommandResult`/api method. These drive the
  dispatcher and flow tests through real reflection rather than mocks.

## Testability seam

`CommandsSessionBot.handleUpdates(Flux<UpdateWrapper>)` becomes **package-private** (from
`private`) so a same-package test can drive it directly with `StepVerifier`. This is the
only production change in this spec. The outer wiring (`groupBy`/`merge`/`subscribe`) is
covered more lightly through public `consume()` + a mocked `TelegramClient`.

## Test classes

Mirror the main package structure under `src/test/java`. Use `@Nested` + `@DisplayName`
grouping and `@ParameterizedTest`/`@CsvSource` for wire-format permutations (replacing the
current near-duplicate methods). AssertJ assertions throughout, `assertThatThrownBy` for
guard cases.

1. **`CommandBuilderTest`** — `build()` output for command-only / answers / dynamic-params /
   combined; **round-trip** `parse(build(x))` identity against `MessageDescriptor`; the
   64-byte boundary case (assert the produced string and that the >64 path is exercised).
2. **`MessageDescriptorTest`** (extend existing) — edges: blank input → `Assert.isTrue`
   failure; dynamic param without value → empty-string value; multiple dynamic params;
   answers-only (no leading `/`); trailing-separator inputs. Convert the 7 near-duplicate
   methods to a parameterized test.
3. **`DynamicParametersTest`** — `canScipAnswer` boundary (`parseInt` + `>= index`),
   `needRefreshContext`, `commandApproved`, `getInitiator`, `empty()`.
4. **`CommandContextTest`** — `create()` rejects non-command; `addUpdate` rejects command;
   `getAnswers()` merges stored answers + pending arguments; `open→progress→close`
   transitions; `refreshContext` rebuild semantics; `getChatId` null handling.
5. **`UpdateWrapperTest`** — `wrap` for message vs callback; `getChatId` both branches and
   the throw path; `isCommand` OR-logic (`message.isCommand() || descriptor.isCommand()`);
   `getFrom` both branches; `getCallbackMessage`.
6. **`MethodMatcherTest`** — `getMatchingScore` (literal match, placeholder, mismatch,
   overflow → `MIN_VALUE`); highest-score-wins; default method when answers empty;
   `args.size() ≤ template.size()` filter; duplicate-template behavior noted (its fix is
   Spec 2, so test only the supported single-match path here).
7. **`CommandsDispatcherTest`** — `invoke` via fixture commands and a real
   `ApplicationContext` (`ApplicationContextRunner` or a small `@Configuration`): Jackson
   binding per parameter type; `scipAnswer` skip path; missing-required → renderer prompt
   (`invocationArgument` set); every auto-injection case (`UpdateWrapper command`/`update`,
   `Update update`, `User from`, `String chatId`, `DynamicParameters`, `CommandContext`);
   no-match → default renderer prompt.
8. **`CommandsSessionBotTest`** — `StepVerifier` over the package-private `handleUpdates`:
   feed a `Flux<UpdateWrapper>` and assert the emitted `PartialBotApiMethod`s for — command
   starts a fresh context; non-command appends; `refreshContext` rebuild; empty context →
   `HelpCommand`; auth-reject → `BotAuthException` surfaced; `progress` state →
   `addQuestionMessage` side effect. Plus one `consume()`-level test with a mocked
   `TelegramClient` verifying an update flows end-to-end to `execute(...)`.
9. **`CommandsSessionBotAutoConfigurationTest`** (extend existing) — broaden the
   activation assertions to the other `@ConditionalOnMissingBean` defaults actually present
   (`CommandsFactory`, `ErrorHandlerFactory`, `BotCommandErrorHandler`, `BotAuthErrorHandler`,
   `defaultParameterRenderer` + renderer family) and add one more override case beyond
   `AuthInterceptor`.

## Verification

- `mvn test` green (owner-run, per project convention).
- Coverage sanity: the dispatch engine classes (`CommandsDispatcher`, `MethodMatcher`,
  `CommandBuilder`, `MessageDescriptor`, `CommandContext`, `UpdateWrapper`,
  `ErrorHandlerFactory`) and `handleUpdates` each have at least the behaviors above asserted.

## Risks

- **Behavior-locking the wrong thing:** because group-B bugs are excluded, a test must not
  assert a known-buggy path; if a planned assertion overlaps a B item, skip it and note it
  for Spec 2.
- **Seam exposure:** widening `handleUpdates` to package-private is a minor encapsulation
  loosening, accepted for deterministic reactive testing.
- **Fixture realism:** fixture `@BotCommand` classes must mirror real downstream usage
  closely enough that the dispatch tests are meaningful, not tautological.