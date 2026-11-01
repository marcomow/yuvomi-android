# Spec: Notes

Status: draft · Upstream checked: 2.83.0 · Decisions: ADR 0003, 0006, 0007, 0011, 0015

## Purpose

The household's board of notes as native screens, at parity with the web app's Notes page
(`public/pages/notes.js`): read the board, find a note, tick its checklist lines, write and
change notes, pin them, sort them into categories and manage those categories.

## Scope

Everything the web page lets a member do. Board, search, creator and category filters,
note read view, editor (title, Markdown text with the formatting toolbar, categories, colour,
pin, visibility, lock), delete with undo, pin and unpin, ticking checklist lines, category
manager (add, rename, reorder, delete). Nothing stays in the browser.

Requirements and acceptance criteria are filled in as they are built.
