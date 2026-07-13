# GuildWorkman (monorepo)

Backend API + on-chain contracts for **GuildWorkman**, a two-sided marketplace
connecting clients with skilled workers (electricians, plumbers, barbers,
carpenters, fashion designers, photographers, and more) for booked
appointments, with escrow-backed payments and verifiable reputation on Stellar.

## Layout

| Path | What | Stack |
|---|---|---|
| [`backend-api/`](backend-api/) | REST API — appointments, auth, payments | Java 17 / Spring Boot / Maven |
| [`soroban-contracts/`](soroban-contracts/) | On-chain escrow, reputation, loyalty-token | Rust / Soroban (Stellar) |

Each project keeps its own README, build, and dependencies:
- `backend-api/README.md` — API domain model, endpoints, running locally, deploy.
- `soroban-contracts/README.md` — contracts overview and build.

## CI

Workflows live at the repo root (`.github/workflows/`) and are **path-filtered**
so each stack only builds on its own changes:

- `build.yml`, `test.yml` — run on `backend-api/**` (Maven build / Docker deploy / tests).
- `soroban-ci.yml` — runs on `soroban-contracts/**` (Cargo test + wasm build).

## History

This repo (`guildworkman-core`) was formed by merging `guildworkman-contracts`
into the `guildworkman-api` backend with `git subtree` (both histories
preserved), then renamed to `guildworkman-core`. See [`MIGRATION.md`](MIGRATION.md).
