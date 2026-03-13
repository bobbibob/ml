# Codex sandbox setup for ML

This folder contains everything needed to create a safe Codex branch and isolated GitHub Actions workflows.

## Goal
- Keep `stable_build` as the human-controlled backup branch.
- Create a separate branch for Codex, recommended: `codex/sandbox`.
- Run dedicated Actions only for Codex branches.
- Avoid accidental deploys from `stable_build` while Codex is experimenting.

## Files included
- `.github/workflows/codex-android-build.yml`
- `.github/workflows/codex-worker-check.yml`
- `.github/workflows/codex-manual-deploy.yml`
- `CODEX_INSTRUCTIONS.md`

## Recommended branch model
- `stable_build` = protected backup / recovery branch
- `codex/sandbox` = Codex working branch
- optional short-lived branches from it:
  - `codex/tasks-*`
  - `codex/ml-*`

## Suggested one-time setup
From your repo root:

```bash
git checkout stable_build
git pull --ff-only origin stable_build
git checkout -b codex/sandbox
mkdir -p .github/workflows
cp /path/to/codex_branch_setup/.github/workflows/codex-*.yml .github/workflows/
cp /path/to/codex_branch_setup/CODEX_INSTRUCTIONS.md .
git add .github/workflows/codex-android-build.yml         .github/workflows/codex-worker-check.yml         .github/workflows/codex-manual-deploy.yml         CODEX_INSTRUCTIONS.md
git commit -m "add codex sandbox workflows and instructions"
git push -u origin codex/sandbox
```

## Secrets and variables expected by workflows
### Android signing/build secrets
- `KEYSTORE_B64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

### Android runtime env secrets used by BuildConfig
- `R2_ENDPOINT`
- `R2_BUCKET`
- `R2_ACCESS_KEY`
- `R2_SECRET_KEY`
- `R2_OBJECT_KEY`
- `R2_REGION`
- `UPDATED_BY`

### Cloudflare deploy secrets
- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

### Worker runtime secrets/vars to configure in Cloudflare
These are not injected by GitHub automatically unless you add deploy steps to set them.
- `FIREBASE_CLIENT_EMAIL`
- `FIREBASE_PRIVATE_KEY`
- `FIREBASE_PROJECT_ID`
- D1 binding `DB`
- R2 binding `R2`

## What each workflow does
### `codex-android-build.yml`
Builds `:app:assembleDebug` on pushes and PRs for `codex/**` branches and uploads the APK artifact.

### `codex-worker-check.yml`
Runs a non-deploy validation for `cloudflare-tasks/src/index.ts` on `codex/**` branches. It installs Node, Wrangler, and TypeScript, then performs a dry-run style validation.

### `codex-manual-deploy.yml`
Manual workflow (`workflow_dispatch`) for Codex branch maintainers only. It can:
- deploy the Worker from the selected ref
- build Android debug APK
- do both in one run

Use it only after a green Android build and green worker check.

## Safe operating rule
Codex should not commit directly to `stable_build`.
All Codex work goes to `codex/**`, is validated there, and only then gets cherry-picked or merged manually by you.
