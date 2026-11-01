# 0010. MVP scope

**Status:** Accepted, 2026-11-01. Widened by [0015](0015-more-native-modules.md): Shopping, Meals,
Recipes, Notes and Pantry are native too.

## Context

Upstream's iOS plan starts with overview, tasks, shopping and calendar. This project's MVP is
narrower by choice.

## Decision

The MVP has three native areas:

- **Sign-in and session** ([spec](../spec/signin.md)).
- **Overview** ([spec](../spec/overview.md)): the user's own tile arrangement from upstream, all
  tiles rendered read-only, task tiles interactive.
- **Tasks** ([spec](../spec/tasks.md)) at **tiers 1 and 2**:
  - Tier 1: list with status, filters, create and edit (title, description, dates, priority,
    category, tags, assignees, points, visibility, lock), tick off with "who did it", undo,
    recurring tasks read-only.
  - Tier 2: recurrence editing, subtasks and description checklists, comments with mentions,
    linked documents read-only.

Everything else opens in the browser (ADR 0006).

## Consequences

- Tier 3 (completion history, archive, bulk actions, manual order, tag and category management,
  document upload) and every other module stay in the web app.
