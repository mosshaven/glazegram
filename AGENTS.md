# Project Instructions

## Project

This repository contains a Telegram client for Android built from scratch
using TDLib.

## Current phase

We are building a stable functional client core and its visual quality together.
Functional correctness and UI quality are developed side by side; neither is
postponed in favor of the other.

## Design direction

Material Design (Material 3, including dynamic Monet color) is the active,
primary Glazegram design system.

Reusable design-system work is allowed and expected:
- theme tokens (color, typography, shape, spacing, motion);
- reusable UI primitives and components;
- polished app bars, lists, drawers, chat surfaces, composers and context actions.

Broad speculative rewrites are still forbidden. Incremental, reviewable slices only.

Future alternative appearance systems (an Apple-inspired mode, a Liquid
Glass-inspired mode, extensive customization) are planned separately. They must
NOT contaminate the current Material implementation: no parallel theming layers,
no conditional visual modes, no abstraction "for future skins" beyond ordinary
clean component boundaries.

## Platform

Android only.

Do not implement:
- desktop application
- web application
- iOS application
- cross-platform UI

## Technology

- Kotlin
- Android SDK
- Jetpack Compose
- TDLib
- Kotlin Coroutines
- Flow / StateFlow

## Workflow

Current work is defined by `docs/CURRENT_TASK.md`. Read it before coding.
Follow `docs/WORKFLOW.md` for roles and process.
