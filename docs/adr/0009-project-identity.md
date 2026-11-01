# 0009. Project identity

**Status:** Accepted, 2026-11-01. Distribution amended by [0013](0013-apk-releases.md).

## Context

"Yuvomi" is upstream's name (renamed from Oikos over a trademark conflict). Upstream has said it
does not plan an Android client. This project is a proof of concept.

## Decision

- The project is an **unofficial proof of concept**. The README says so in its first lines and
  links upstream.
- **No contact with upstream** for now; this is revisited if the project goes beyond a POC.
- **No public distribution.** Builds are debug APKs from CI artifacts.
- **Application ID** `io.github.marcomow.yuvomi`. **Launcher label** "Yuvomi". The sign-in screen
  and the about sheet say "Unofficial Android client". Both are revisited before any public
  release.
- **MIT licence**, like upstream. Code, comments, docs and commit messages in **English**.

## Consequences

- Nothing here can be mistaken for an official upstream release.
- Choosing a public name and application ID is a required step before distribution.
