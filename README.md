# Codex Eink

Codex Eink is a monochrome Android client for monitoring and controlling Codex from a BOOX e-ink device.
It is a standalone Kotlin/Jetpack Compose project optimized for Android 11 and newer.

> [!IMPORTANT]
> OpenAI documents the host-side Codex app-server and Remote feature, but not the third-party Android
> controller enrollment API. Managed Remote support is therefore guarded by a compatibility gate until
> clean-room interoperability tests prove a legitimate independent login and pairing flow. The debug build
> can connect directly to an authenticated app-server for protocol and UI development; that mode does not
> claim live co-control of a separately running Codex Desktop task.

## What is implemented

- E-ink-first adaptive task list and timeline UI with no authored animation, shadow, or ripple.
- Streaming-friendly models for messages, plans, reasoning summaries, commands, file changes, tools,
  web activity, errors, approvals, and user questions.
- Managed relay envelope primitives and a direct authenticated WebSocket diagnostic transport.
- Inline approval decisions, with confirmation for session-wide or policy-changing grants.
- QR/manual pairing entry and encrypted Android Keystore credential storage.
- Optional always-connected foreground service with reconnect, attention notifications, and boot recovery.
- Clean-room APK research scripts that keep proprietary artifacts, decompiled code, and captures out of Git.

## Setup and build

See the [setup guide](docs/SETUP.md) for workstation and BOOX preparation, installation, connection
options, credential handling, background behavior, troubleshooting, and the tradeoffs between debug,
release, Tailscale, USB, and TLS-terminated setups.

The shortest path to an installable development build requires JDK 21 and Android SDK 37:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` is optional; the APK can also be copied to a BOOX device and opened there. Before handing off a
change, run the complete repository check (Python 3.10+ is also required for its research-tool tests):

```bash
./scripts/verify.sh
```

The debug APK is sideloadable. Without the release-signing environment variables described in the
[setup guide](docs/SETUP.md#release-signing-and-publishing), `assembleRelease` produces an unsigned artifact
that cannot be installed or distributed as a release APK.

## Direct diagnostic connection

This mode is for app-server protocol/UI development on a trusted private network. It creates a new
app-server endpoint for this client; it does not attach to or co-control a separately running Codex Desktop
task. Never expose the listener or its capability token to the public internet.

On the host, either run `./scripts/setup-direct-host.sh --bind TAILSCALE_IP` or create a high-entropy token file
and start Codex with capability-token authentication manually:

```bash
mkdir -p "$HOME/.codex/codex-eink"
umask 077
openssl rand -hex 32 > "$HOME/.codex/codex-eink/app-server-token"
codex app-server \
  --listen ws://TAILSCALE_IP:4500 \
  --ws-auth capability-token \
  --ws-token-file "$HOME/.codex/codex-eink/app-server-token"
```

Enter the private WebSocket URL and token under **Direct diagnostic**. The helper rejects public and
ordinary LAN bind addresses; it accepts only loopback or Tailscale IPs. Debug builds apply the same
restriction to `ws://`, while other networks and release builds require `wss://`.
The [setup guide](docs/SETUP.md#choose-a-connection-path) covers the security and operational tradeoffs of
each transport.

## BOOX setup

For persistent connections, enable **Keep connected in the background**, allow notifications, disable
BOOX Freeze for Codex Eink, and exempt the app from battery optimization. These settings trade battery life
for reliable approvals and completion notifications.

## Clean-room research

See [`research/README.md`](research/README.md). Downloaded APK/XAPK files, JADX output, authenticated
captures, tokens, and account/device identifiers must stay in ignored artifact directories. Only original
code, redacted fixtures, hashes, and independently written protocol notes belong in this repository.

## Modules

- `app` — Android UI, encrypted settings, notifications, foreground service, and protocol adapter.
- `protocol` — active direct JSON-RPC/WebSocket support plus experimental relay framing and test-only domain
  reducer primitives; see [`protocol/README.md`](protocol/README.md) for the runtime boundary.
- `eink-ui` — self-contained monochrome Compose design system derived from the GoL Screensaver project.
- `research` — reproducible clean-room acquisition, verification, indexing, and compatibility notes.

## Status and safety

This is an experimental personal-sideload project, not an official OpenAI client. It does not extract
first-party tokens, copy OpenAI code/assets, spoof application signatures or attestation, or bypass account
security. If managed-controller authentication is signing-bound or otherwise unavailable to third-party
clients, the compatibility gate remains closed.

## License

MIT. The license applies only to original code in this repository, not to third-party APKs or decompiled
materials used privately for interoperability research.
