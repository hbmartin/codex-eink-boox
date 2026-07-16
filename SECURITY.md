# Security

## Reporting

Do not open a public issue containing access tokens, pairing codes, account IDs, device IDs, private prompts,
command output, or repository diffs. Revoke the affected Codex Remote device and rotate credentials first.

## Design boundaries

- Connection secrets are encrypted with a non-exportable Android Keystore AES-GCM key.
- Android backup and device transfer are disabled for app data.
- Approval notifications open the app; they never approve an action directly.
- Direct WebSocket connections require a bearer capability token. Release builds require `wss://`; debug
  builds allow `ws://` only for loopback or Tailscale addresses used during local development.
- Managed Remote remains compatibility-gated until third-party authentication can be completed without
  extracting first-party credentials, spoofing attestation, or weakening account security.
