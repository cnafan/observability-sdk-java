# Development delivery workflow

Follow this workflow after implementing a change in this repository. It records the
maintainer's delivery preferences for local development and automation.

## Scope and local files

- Treat `docs/` as a local working directory. Do not stage, commit, or push its
  contents unless the maintainer explicitly requests it.
- The version of `src/main/resources/observability/a2at.yaml` remains `1.0.0` until
  organization-wide version alignment is explicitly approved.
- Never add webhook URLs, access tokens, private certificates, or other credentials to
  repository files, commit messages, PR descriptions, or CI logs.

## Verify before committing

Run checks appropriate to the files changed, and report only results that actually ran.

1. For Java implementation changes, run:

   ```powershell
   mvn -B -ntp clean verify
   ```

2. For the local storage Compose stack, validate its syntax when its configuration
   changes:

   ```powershell
   Set-Location deploy/local-storage
   docker compose config --quiet
   ```

3. For `deploy/local-storage/verify.ps1`, run a PowerShell syntax check. Run the full
   storage verification only when Docker is available; it starts local containers and
   retains data unless `-Shutdown` is supplied.

4. Before committing, run:

   ```powershell
   git diff --check
   git status --short
   ```

Ensure generated artifacts, `target/`, local verification reports, and local `docs/`
files are excluded from the staged change.

## Commit and push

- Use an English Conventional Commit message, such as `feat(sdk): add exporter option`,
  `fix(validation): reject invalid attribute`, `test(deploy): add storage verification`,
  or `docs(api): clarify trace context`.
- Every commit must include DCO sign-off. Use `git commit -s -m "<message>"` and confirm
  the `Signed-off-by` trailer before pushing.
- Use the configured maintainer identity `cnafan <zhangqiang326@huawei.com>` for commits.
- Create a dedicated feature or fix branch from the latest `origin/main`. Do not push
  directly to `main`.
- Push the branch to the `fork` remote, then confirm the remote tracking branch and
  working tree status.

## Pull requests

- Create PRs against `project-openan/observability-sdk-java:main`.
- PR titles and descriptions must be in English.
- Use the sections `Changes`, `Verification`, and `Related Issue` in the PR body.
- Every SDK PR must include the exact line `Related to #20`.
- Do not use `Closes #20`, `Fixes #20`, or `Resolves #20`; Issue #20 is a long-running
  tracking issue and must remain open after a PR merges.
- Include verification results and the count of commits with valid DCO sign-off. Do not
  claim an end-to-end storage result when the storage queries did not complete.
- After creating or updating a PR, check its state, current head commit, CI status, and
  DCO status.

## Feishu PR notifications

- Creating, updating, reopening, or merging a PR does not authorize a Feishu notification.
  Send a card only after the maintainer explicitly requests notification for that exact PR
  and update.
- Before sending, retrieve current PR title, branch, head commit, checks, and DCO status.
  Do not reuse results from an earlier notification.
- The card includes `@郭飞 @周杰` as the default reviewers. Until their Feishu IDs are
  configured, use display-only text; it does not create actual mentions.
- Keep the bot webhook in a local secret file only. Never print it, commit it, or include
  it in a PR body or card content.
- A notification is a new message. Do not send a follow-up card unless it is explicitly
  requested again.

## Release boundary

- Do not create release tags, publish Maven artifacts, or send release notifications
  unless the maintainer explicitly authorizes that release action.
