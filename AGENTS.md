# Project Guidelines

## Project Overview

Light app shell for the POS system, responsible for making the POS available on Android devices. It provides a login screen, then loads the full POS from a URL and uses Capacitor to bridge native features.

## Coding Style

- No fallbacks, no backward compatibility: We prioritize Nuxt 4 and Nuxt UI v4 patterns.
- Limit defensive coding: Keep the codebase clean and maintainable.
- Future implementation: Use TODO comments for planned methods/features and remove them once implemented.

## Tech Stack

- Framework: Nuxt 4 (SPA mode)
- UI: Vue 3 + Nuxt UI v4 (Tailwind CSS 4)
- Icons: NuxtIcon (`<Icon>`) with `lucide` icon set
- Bridge: Capacitor (for Android)
- I18N: None, english only.

## Architecture & Conventions

- Frontend is SPA: The shell is built to be client-side only (always `ssr: false`).
- Composables: Shared logic in `app/composables`.
- Platform Bridging: Use Capacitor for native features (printers, scanners, etc.).
- Flow: Login Screen -> Webview/URL Load -> Capacitor Interop.

## Fetching & API

- Use `CapacitorHttp` (from `@capacitor/core`) for all API requests.
- Usage: `import { CapacitorHttp } from '@capacitor/core'; const res = await CapacitorHttp.get({ url: '...' });`.
- Benefit: No CORS constraints when running on native platforms.
- Typing: Leverage Nuxt's auto-import typing for API responses when possible; don't hand-write types when inferable.

## UI/UX Guidelines

- Favor composition over custom widgets: Use Nuxt UI v4 defaults.
- Touch-Friendly: Optimize sizing and hit targets for touch/tablet use.
- Tailwind 4: Use Tailwind utilities only; no `<style>` blocks.
- Semantic Colors: Use `text-success`, `bg-primary`, etc.
- Responsive: Ensure the shell layout works across mobile and tablet devices.
- `USelect`/`USelectMenu`: `:model-value` is the value itself.

## Project Structure

- `app/` – Client files (components, composables, pages)
- `public/` – Static assets (icons, manifest)
- `nuxt.config.ts` – Nuxt configuration
- `package.json` – Dependencies and scripts