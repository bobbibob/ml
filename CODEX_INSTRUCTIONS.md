# CODEX INSTRUCTIONS FOR THIS REPOSITORY

You are working in a safety sandbox branch for the ML project.

## Branch rules
- Primary human backup branch: `stable_build`
- Your working branch: `codex/sandbox` or another `codex/**` branch
- Never target `stable_build` directly
- Never force-push to `stable_build`
- Never modify GitHub workflows outside the dedicated `codex-*` workflows unless explicitly asked

## Project overview
This repo contains:
1. Android app in `app/`
2. Cloudflare Worker backend in `cloudflare-tasks/`
3. Firebase Cloud Messaging integration
4. Local/remote sync logic for ML summaries, tasks, packs, and notifications

## High-level product areas
### ML area
- daily summaries
- pack sync via R2/Cloudflare
- local SQLite + merged db behavior
- cross-device summary sync

### Tasks area
- create/edit/delete/complete tasks
- assignee + creator
- push notifications for task events
- reminder configuration
- backend task routes in worker

## Core constraints
- Do not break ML while fixing Tasks
- Do not block the ML screen with heavy pack sync
- Do not remove existing safety/debug behavior unless explicitly asked
- Be careful with SQLite schema differences between local DB and pack DB
- For Firebase push, prefer data-driven notification handling on Android when navigation matters

## File map
### Android app
- `app/src/main/java/com/ml/app/MainActivity.kt`
- `app/src/main/java/com/ml/app/ui/SummaryScreen.kt`
- `app/src/main/java/com/ml/app/ui/SummaryViewModel.kt`
- `app/src/main/java/com/ml/app/ui/TasksScreen.kt`
- `app/src/main/java/com/ml/app/ui/AddDailySummaryScreen.kt`
- `app/src/main/java/com/ml/app/data/SQLiteRepo.kt`
- `app/src/main/java/com/ml/app/data/repository/TasksRepository.kt`
- `app/src/main/java/com/ml/app/data/repository/DailySummarySyncRepository.kt`
- `app/src/main/java/com/ml/app/notifications/MlFirebaseMessagingService.kt`

### Backend worker
- `cloudflare-tasks/src/index.ts`
- `cloudflare-tasks/wrangler.jsonc`
- `cloudflare-tasks/schema.sql`

## How GitHub Actions are used here
This repo uses dedicated Codex workflows only on `codex/**` branches.

### Android workflow
Workflow file: `.github/workflows/codex-android-build.yml`

It does the following:
1. checks out the selected ref
2. sets up Java 17
3. restores Gradle cache
4. reconstructs the signing keystore from `KEYSTORE_B64`
5. exports Android/R2 environment variables expected by `app/build.gradle.kts`
6. runs:
   ```bash
   ./gradlew :app:assembleDebug
   ```
7. uploads the produced debug APK as an artifact

### Worker check workflow
Workflow file: `.github/workflows/codex-worker-check.yml`

It does the following:
1. checks out the selected ref
2. sets up Node 20
3. installs dependencies needed for Worker validation
4. runs TypeScript/Wrangler validation against `cloudflare-tasks`
5. fails fast on syntax/config issues before deploy

### Manual deploy workflow
Workflow file: `.github/workflows/codex-manual-deploy.yml`

It is manual on purpose. It should be used only after build/check are green.
It can build Android, deploy Worker, or both.

## How to work safely
For every bugfix or feature:
1. make the smallest possible change
2. keep changes isolated to the relevant files
3. avoid broad refactors unless the user asks
4. after editing, explain what changed and what to verify
5. prefer one logical fix per commit

## Commit discipline
Good commit examples:
- `fix task creation request and loading state`
- `send task push as data only message`
- `stop pack sync from blocking ml screen`

Bad commit examples:
- `fix stuff`
- `update app`
- `changes`

## What to verify after changes
### If Android UI changed
- app compiles
- screen opens
- button states make sense
- no frozen loading states
- no duplicate navigation events

### If Worker changed
- route still returns correct JSON
- auth paths still return unauthorized when expected
- push payload contains the fields Android expects
- slow push sending should not block create/update requests if UX depends on quick response

### If notification behavior changed
- payload includes `title`, `body`, and `task_id` when needed
- Android notification builds from `message.data`
- tap opens the intended screen
- if a specific task should open, task routing must work without relying on unstable timing

## Special warnings for this repo
- `SummaryScreen`, `SummaryViewModel`, and `TasksScreen` have had duplicate effect/navigation issues before. Check for duplicate `LaunchedEffect` blocks.
- `SQLiteRepo.kt` is critical. Small schema assumptions can break ML, profit calculation, or sync.
- `cloudflare-tasks/src/index.ts` is large. Patch carefully and search exact blocks before replacing.
- Keep ML and Tasks fixes separated when possible.

## Preferred workflow for Codex
1. create small patch
2. run the Codex branch Actions
3. summarize what changed
4. list concrete manual checks
5. wait for human validation before the next risky step
