# 0019. Upstream's shared error sentences, generated

**Status:** Accepted, 2026-11-02. Refines ARCHITECTURE.md, rule 4.

## Context

Upstream has one table, `public/utils/friendly-error.js`, that every page falls back to: the
sentence for a refusal reason (403), for a case the server names (`reason` on a 400/404/409),
and the whole numbers a sentence may name from the answer's `params` (`CASE_PARAMS`). Each
native module ported the reasons its own page reads, by hand. A reason a module had not mapped
showed the general sentence, and the client dropped `params`, so modules pinned the server's
limits as constants. A sentence like "at most {{limit}} characters" showed its placeholder when
no module filled it in.

## Decision

- `upstream/scripts/extract_friendly_errors.py` (run by `upstream/sync.sh`) generates
  `core/api/FriendlyErrorTable.kt` from the pinned upstream: refusals, cases, params, gone
  reasons, `PARAM_MAX`. It is Kotlin, not an asset, so the modules' pure tests use it.
- `ApiProblem.Http.params` carries the whole numbers of `params` that upstream's
  `readParams` accepts.
- `FriendlyError.finish(e, chosen, fallback)` wraps each module's key mapping. If the
  module chose the general sentence of the place, it gives the shared sentence instead,
  when there is one. If the module's key is the sentence of the case `e` names, it adds
  that case's numbers.
- A key with numbers travels as `key?name=value` through the modules' existing key plumbing.
  `Strings.t` reads them back. i18n keys never contain `?`.
- Modules that pinned limits (Waste, Contacts, Documents) use the server's numbers first. The
  constants stay as a stand-in for an answer without them.

## Consequences

A new reason in upstream's table reaches every module at the next sync, with no module
change. A module's own mapping still wins, as upstream's pages do.
