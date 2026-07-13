# Migration: fold `guildworkman-contracts` into this repo (monorepo)

Runbook for merging the Soroban smart-contracts repo into `guildworkman-api`
(the repo since renamed `guildworkman-core`), preserving both commit histories. **Do it on a fresh clone + feature branch;
nothing is destructive; the contracts repo is archived (never deleted) only
after the merge is verified.**

> **Final layout.** For a symmetric two-package monorepo, the Spring API lives
> under **`backend-api/`** and the contracts under **`soroban-contracts/`**.
> Step 1 below grafts the contracts at `soroban/` via subtree; they were then
> renamed to `soroban-contracts/` and the backend module relocated into
> `backend-api/` (a plain `git mv`, tracked as renames — history preserved).
> Root keeps `.github/`, `MIGRATION.md`, and the monorepo `README.md`.

## Decisions (confirmed)

| Decision | Choice |
|---|---|
| Base repo | `guildworkman-api`, renamed to `guildworkman-core` (keeps its history, 6 PRs, issues) |
| Absorbed repo | `guildworkman-contracts` (git@github.com:workman-labs/guildworkman-contracts.git, default branch `main`) |
| Layout | **`backend-api/`** (Spring API) + **`soroban-contracts/`** (Soroban) |
| Technique | **`git subtree`** (preserves original commit SHAs) + `git mv` for the symmetric relocation |
| Target branch | `development` (api's default) |
| Contracts repo after | **Archive** (read-only) — not delete |

## Step 1 — Safe working copy + graft history

```bash
git clone git@github.com:workman-labs/guildworkman-core.git gw-monorepo
cd gw-monorepo
git checkout development
git checkout -b chore/merge-contracts-monorepo

git remote add contracts git@github.com:workman-labs/guildworkman-contracts.git
git fetch contracts
git subtree add --prefix=soroban contracts main
```

Result: contracts' `main` lands under `soroban/` (`Cargo.toml`, `Cargo.lock`,
`contracts/`, `.gitignore`, `LICENSE`, `README.md`, `.github/`) as a merge
commit, with the 9 contract commits kept as ancestors (original SHAs).

## Step 2 — CI consolidation (required)

GitHub only runs workflows in the **root** `.github/workflows`. The contracts'
`ci.yml` just landed at `soroban/.github/workflows/ci.yml`, where it won't run.

```bash
git mv soroban/.github/workflows/ci.yml .github/workflows/soroban-ci.yml
git rm -r soroban/.github
```

Then add `paths:` filters so each stack only builds on its own changes:

- `.github/workflows/build.yml` and `test.yml` (Spring/Maven) — restrict to:
  `src/**`, `pom.xml`, `.mvn/**`, `mvnw*`, `Dockerfile`, `docker-compose.yml`,
  and their own workflow files.
- `.github/workflows/soroban-ci.yml` (Rust) — restrict to `soroban/**` and its
  own workflow file, and set `defaults.run.working-directory: soroban`
  (Cargo now lives under `soroban/`).

```bash
git commit -am "ci: path-filtered backend + soroban workflows"
```

## Step 3 — Docs / references

- Root `README.md`: add a "monorepo layout" note (`src/` = Spring API,
  `soroban/` = Soroban contracts).
- Confirm nothing references the contracts repo **by URL** as a build
  dependency (per the README the contracts are "not yet integrated", so this
  should be clean — verify).

## Step 4 — Verify locally (before pushing)

```bash
./mvnw -q -DskipTests package            # backend unaffected
(cd soroban && cargo build)              # contracts build from the new path
git log --oneline -- soroban/            # the 9 contract commits are present
git log --oneline -5                     # api history intact + subtree merge
```

## Step 5 — Push -> PR -> merge

```bash
git push -u origin chore/merge-contracts-monorepo
gh pr create --base development \
  --title "Merge guildworkman-contracts into the monorepo (soroban/)" --body "..."
```

Review the diff (especially CI), then merge into `development`.

## Step 6 — Archive contracts (only after the merge is verified)

- Optional: commit a notice on the contracts repo first — "Moved to
  `guildworkman-core/soroban-contracts`."
- GitHub: **Settings -> Archive repository** (read-only; PRs + history kept).
- Update any external links / deploy docs pointing at the old repo.

## Preserved vs. not

- **Preserved:** api's full history + all 6 PRs + issues; contracts' 9 commits
  (original SHAs); every `Merge pull request #N` commit message in both
  histories; contracts' 3 PRs (browsable in the archived repo).
- **Not in the monorepo:** contracts' 3 PR pages/reviews (they remain in the
  archived repo — PRs can't be transferred across repos); contracts' non-`main`
  branches (`clippy-cleanup`, `rebrand-guildworkman`, `remove-sabi-references`
  — also in the archive).

## Rollback

- Pre-merge: delete the branch.
- Post-merge: the contracts repo is untouched until you archive it, so worst
  case re-clone. **Do not archive until you're satisfied.**

## Note on `subtree` history paths

`subtree` keeps SHAs, but *historical* paths stay at the contracts root (e.g.
`Cargo.toml`, not `soroban/Cargo.toml`) before the merge point. `git log` and
`git blame` still work. If every historical path must read as `soroban/...`,
use `git filter-repo --to-subdirectory-filter soroban` on a contracts clone +
an `--allow-unrelated-histories` merge instead (cost: rewritten SHAs). For 9
commits, `subtree` is the chosen approach.
