# Repository guidance

- Keep `app`, `protocol`, and `eink-ui` responsibilities separated. Relay wire DTOs must not leak into Compose UI.
- Preserve unknown JSON fields and render unknown timeline items instead of failing closed-loop sessions.
- Never log or commit credentials, pairing artifacts, prompts, diffs, command output, account/device IDs,
  proprietary APK contents, JADX output, or unredacted network captures.
- Persistent/session-wide approvals require explicit secondary confirmation in the UI.
- E-ink UI must remain meaningful in black and white and use no authored animation, ripple, elevation, or shadow.
- Run `./scripts/verify.sh` before handing off changes.
- Managed Remote claims require an end-to-end test against a user-owned host and a legitimate independent auth flow.
