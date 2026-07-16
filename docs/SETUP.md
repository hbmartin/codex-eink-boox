# Setup guide

This guide covers building Codex Eink, installing it on a BOOX device, and connecting it to a user-owned
Codex host. Codex Eink is experimental and is not an official OpenAI client.

> [!IMPORTANT]
> **Direct diagnostic** is the only working connection mode in this repository. **Managed Remote** is a
> compatibility placeholder: entering or scanning a pairing code reports an incompatibility and does not
> exchange or store the pairing material. Controller enrollment and authentication must remain disabled
> until they pass the clean-room end-to-end gate described in
> [`research/PROTOCOL.md`](../research/PROTOCOL.md#controller-interoperability-gate).

## Connection options at a glance

| Path | Best for | Benefits | Tradeoffs |
| --- | --- | --- | --- |
| Build and UI only | Compose, reducer, and protocol development | No host listener or credential is needed | No live task traffic |
| Debug APK + Tailscale `ws://` | Normal device development | Simple private reachability; the helper script detects the host's Tailscale address | Both devices need the same trusted tailnet; WebSocket traffic is not independently protected by TLS; debug builds are not distribution builds |
| Debug APK + USB/ADB loopback | Desk-bound testing | No wireless listener and no Tailscale dependency | Requires USB debugging and a cable; the reverse tunnel must be recreated after it drops; poor fit for background use |
| Debug or signed release APK + `wss://` | Longer-lived or non-Tailscale networks | TLS protects the application connection and release builds accept it | Requires a trusted certificate and a correctly configured WebSocket reverse proxy; this repository does not provide that infrastructure |
| Managed Remote | Co-control through the managed relay | Would avoid exposing a host listener | Not implemented or supported; no legitimate independent controller-auth flow has been verified |

Direct diagnostic mode speaks to the `codex app-server` process that you start for it. It is useful for
protocol and UI development, but it is not the same topology as Codex Remote and does not take over a task
owned by another Codex Desktop process.

The codebase contains managed-relay envelopes, chunking, replay, and a domain reducer so interoperability
can be developed and tested without mixing relay DTOs into Compose. Those components are explicitly
experimental: the app factory does not construct a managed connection, and their unit tests cover wire and
state behavior only. They do not demonstrate controller enrollment, authentication, relay reachability, or
end-to-end task control. See the [`protocol` boundary notes](../protocol/README.md) before interpreting test
coverage or adding another setup path.

## Prerequisites

### Build workstation

- JDK 21. Set Android Studio's Gradle JDK to 21 as well as `JAVA_HOME` for command-line builds.
- Android SDK Platform 37. Platform Tools are needed only for `adb` installation or USB tunneling.
- Python 3.10+ for `./scripts/verify.sh`, which also checks the clean-room research tools.
- Bash and OpenSSL for `./scripts/setup-direct-host.sh`.
- A Codex CLI build whose `codex app-server --help` includes `--listen`, `--ws-auth`, and
  `--ws-token-file`. Having a `codex` launcher on `PATH` is insufficient if the installation itself is
  broken, so run `codex --version` before configuring the device.
- Network access on the first Gradle run so the wrapper and declared dependencies can be downloaded.

The Gradle wrapper is pinned in the repository; a separate system Gradle installation is unnecessary and
can introduce version drift.

### BOOX device

- Android 11/API 30 or newer.
- Permission to install an APK from your chosen file manager, browser, or USB debugging host.
- Camera permission only if scanning a QR code. Direct diagnostic setup does not need it.
- Notification permission on Android 13+ if background connection is enabled.
- For the Tailscale path, a Tailscale client joined to the same user-controlled tailnet as the host.

Allowing installation from unknown sources or enabling USB debugging expands which local apps or attached
computers can install software. Enable only the narrow source you use, trust the attached computer only
when necessary, and turn the setting back off if you do not need ongoing development access.

## Configure the Android toolchain

### Android Studio

1. Open the repository as an existing project.
2. In SDK Manager, install Android SDK Platform 37. Install Platform Tools too if you will use `adb`.
3. Set the Gradle JDK to JDK 21, then let project sync finish.
4. Select the `app` run configuration and the `debug` build variant for normal development.

Android Studio is the easiest route for SDK management, Compose previews, and on-device debugging. The
tradeoff is a larger installation and more IDE-managed state than the command-line route.

### Command line

Set `JAVA_HOME` to a JDK 21 installation and make the Android SDK discoverable through `ANDROID_HOME` or an
ignored `local.properties` file:

```properties
sdk.dir=/absolute/path/to/Android/sdk
```

Do not commit `local.properties`; it contains a workstation-specific path and is already ignored. Confirm
the selected runtime before a full build:

```bash
java -version
./gradlew --version
```

If Gradle reports that Platform 37 is missing, install that platform with Android Studio's SDK Manager or
your existing `sdkmanager` installation. The command-line route is lighter and reproducible in CI, but SDK
package installation and environment variables are your responsibility.

## Build and verify

For a quick, installable development APK:

```bash
./gradlew assembleDebug
```

The output is `app/build/outputs/apk/debug/app-debug.apk`. For the complete repository handoff check:

```bash
./scripts/verify.sh
```

The verification script runs JVM tests, Android lint, debug and release assembly, the research-tool help
check, and the Python research tests. It does not run connected-device instrumentation tests. Those can be
run separately when a device or emulator is available:

```bash
./gradlew connectedDebugAndroidTest
```

Connected tests provide more confidence in Android and Compose behavior, but they are slower and depend on
device state, so they are not part of the default verification script.

### Debug versus release

| Property | Debug | Release |
| --- | --- | --- |
| Application ID | `me.haroldmartin.codexeink.debug` | `me.haroldmartin.codexeink` |
| Signing | Automatically signed with the local Android debug key | Unsigned by default; signed only when all four release-signing build variables are present |
| Cleartext WebSocket | Allowed only for loopback and recognized Tailscale addresses | Disabled; endpoint must use `wss://` |
| Optimization | Development-friendly | Code and resource shrinking enabled |
| Intended use | Local development and sideloading | A basis for a privately signed, TLS-only build |

The two application IDs allow debug and release installs to coexist. They also have separate Android
Keystore entries and saved connection profiles. A debug APK is easy to install but should not be treated as
a production artifact. A release build narrows network behavior and enables shrinking, but the repository
intentionally does not contain a signing key or signing credentials.

### Release signing and publishing

Local release signing is enabled only when all four of these environment variables are non-empty:

- `ANDROID_SIGNING_STORE_FILE` — an absolute path to the release keystore outside the repository;
- `ANDROID_SIGNING_STORE_PASSWORD` — the keystore password;
- `ANDROID_SIGNING_KEY_ALIAS` — the signing-key alias; and
- `ANDROID_SIGNING_KEY_PASSWORD` — the signing-key password.

Inject them through a local secret manager or protected CI environment, then run:

```bash
./gradlew assembleRelease
```

With complete signing configuration, the output is `app/build/outputs/apk/release/app-release.apk`. Without
it, Gradle still succeeds but writes `app-release-unsigned.apk`. An incomplete environment also falls back
to unsigned output, so verify the exact filename and signature with Android SDK `apksigner` before sharing
an APK. Never put a keystore, password, or encoded keystore in `local.properties`, a shell script, Gradle
properties, build logs, or Git.

Publishing a GitHub Release runs the repository's `publish-apk` job. That job expects these GitHub Actions
secrets:

- `ANDROID_SIGNING_KEY_BASE64`;
- `ANDROID_SIGNING_STORE_PASSWORD`;
- `ANDROID_SIGNING_KEY_ALIAS`; and
- `ANDROID_SIGNING_KEY_PASSWORD`.

It decodes the keystore into temporary runner storage, builds and verifies `app-release.apk`, uploads it as
`codex-eink.apk`, and removes the temporary file. Base64 is only an encoding, not encryption; access to the
Actions secrets and release-publishing permission must remain tightly restricted.

Automated signing makes releases reproducible and updateable, but it concentrates a long-lived app identity
in CI. Back up the keystore securely, restrict who can publish releases or modify workflows, and retain the
same key for future updates. Losing the key prevents compatible updates; leaking it lets an attacker sign an
APK that appears to come from the same publisher.

## Install the debug APK

With a BOOX device connected to an authorized ADB host:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, transfer the APK to the device, open it in the BOOX file manager, and authorize that file
manager as an install source when Android prompts. ADB is faster for repeated development installs; manual
sideloading avoids leaving USB debugging enabled.

If Android reports an incompatible signing certificate, an existing copy was signed by a different key.
Removing that copy permits installation but also deletes its encrypted saved host profile, so rotate or
retain the host capability token deliberately before uninstalling.

## Choose a connection path

### Option A: debug over Tailscale

This is the most convenient untethered development setup.

1. Join the host and BOOX device to the same user-controlled tailnet and confirm they can reach each other.
2. On the host, from this repository, start the authenticated listener:

   ```bash
   ./scripts/setup-direct-host.sh
   ```

   The script selects the host's Tailscale IPv4 address, creates a 256-bit capability token if one does not
   already exist, restricts the token file permissions, prints the endpoint and token **path**, and leaves
   `codex app-server` in the foreground. It deliberately does not print the token itself.

3. Keep that terminal and host awake. Transfer the token from the printed file path to the BOOX device over
   a trusted channel without putting it in an issue, chat, screenshot, shell history, or repository file.
4. In Codex Eink, choose **Direct diagnostic** and enter:
   - any local label under **Host name**;
   - the printed `ws://100.x.y.z:4500` endpoint;
   - the capability token under **Capability token**.
5. Select **Save and connect**.

Tailscale encrypts traffic between its peers, but the app-server URL remains `ws://`, so there is no second
TLS layer between the two processes. Use this only on an owned, tightly controlled tailnet. The debug app
accepts cleartext only for loopback, the Tailscale IPv4 range, Tailscale IPv6, and `*.ts.net` names; it
rejects ordinary private-LAN `ws://` endpoints such as `192.168.x.x`.

Use `--bind TAILSCALE_IP` or `--port PORT` when auto-detection or port 4500 is unsuitable. Restrict any host
firewall opening to the Tailscale interface instead of exposing the port on every interface.

### Option B: debug over USB/ADB loopback

This path keeps the listener on the host loopback interface. In one terminal, start it with:

```bash
./scripts/setup-direct-host.sh --bind 127.0.0.1
```

In a second terminal, while the BOOX device is attached and authorized:

```bash
adb reverse tcp:4500 tcp:4500
```

Enter `ws://127.0.0.1:4500` in the debug app plus the capability token from the path printed by the setup
script. From the Android app, this loopback address reaches the host through ADB reverse.

This avoids a wireless listener, but USB debugging grants significant control to the authorized host. The
connection also disappears when the cable, ADB daemon, or reverse rule goes away, and the rule may need to
be recreated after reconnecting or rebooting. Do not use this path to evaluate untethered background
reliability.

### Option C: `wss://` through a TLS reverse proxy

Use this path for an ordinary LAN, a routed network, or a signed release build. Bind app-server to loopback,
then place a WebSocket-capable TLS reverse proxy in front of it:

```bash
./scripts/setup-direct-host.sh --bind 127.0.0.1 --port 4500
```

Configure the proxy to:

- present a certificate trusted by Android for the exact hostname in the app;
- accept `wss://` and proxy WebSocket upgrades to `ws://127.0.0.1:4500`;
- preserve the `Authorization: Bearer ...` header; and
- expose only the intended interface and port.

The exact proxy and certificate lifecycle are deployment-specific and deliberately outside this
repository. TLS protects the token and traffic on the network, but it adds certificate issuance, renewal,
DNS, proxy, and firewall responsibilities. A self-signed certificate will not work unless the device is
explicitly configured to trust it, which broadens the device trust configuration and is easy to get wrong.

Never expose a plain `ws://` app-server listener to the internet. Do not treat the capability token as a
substitute for transport encryption on an untrusted network.

## Credential lifecycle

The helper stores its token at `${CODEX_HOME:-$HOME/.codex}/codex-eink/app-server-token`, with directory and
file permissions restricted to the current host user. It reuses the existing token so the saved BOOX
profile survives host restarts. Reuse is convenient, but increases the lifetime of a leaked credential.

Treat the token as a host-control capability:

- transfer it only to devices you control;
- do not print it in build logs, paste it into chat, or commit it;
- use **Forget host** in the app before disposing of or lending the BOOX device; and
- if exposure is suspected, stop app-server, remove the token file, rerun the helper to generate a new one,
  and update or forget every device that held the old token.

The app encrypts the saved endpoint and token with an Android Keystore AES-GCM key and excludes app data
from Android backup and transfer. This protects the stored file from casual copying, but it does not make a
rooted or actively compromised device trustworthy. Forgetting the host removes the encrypted profile and
its app-specific Keystore key.

## Background connection on BOOX

Without **Keep connected in the background**, the app disconnects when it leaves the foreground. Enabling
the option starts an Android foreground service, shows an ongoing connection notification, reconnects after
boot, and posts attention notifications for approvals, questions, completion, and connection failure.

For the most reliable background behavior:

1. Allow notifications when prompted.
2. Exempt Codex Eink from Android battery optimization.
3. Disable BOOX Freeze or equivalent automatic app freezing for Codex Eink.
4. Allow background activity/autostart if the BOOX firmware exposes those controls.
5. Test a harmless notification after the screen has slept; BOOX firmware behavior varies.

The tradeoff is higher idle network and battery use, a persistent notification, and a longer-lived host
capability on an always-connected device. Attention notifications use private lock-screen visibility, but
the unlocked notification tray may still show a limited approval, question, or connection summary. Leave
background connection off if you only need occasional foreground control.

Persistent or session-wide approvals still require an explicit second confirmation in the app. Background
connectivity does not weaken or bypass that confirmation.

## Troubleshooting

### Gradle cannot find Java or the Android SDK

Confirm that both `java -version` and `./gradlew --version` select JDK 21, and that SDK Platform 37 is
installed. Check `ANDROID_HOME` or the ignored `local.properties` path; do not copy another developer's
machine-specific file into the repository.

### The debug app rejects a `ws://` endpoint

Cleartext is intentionally limited to loopback and recognized Tailscale addresses. Use the Tailscale or
ADB-reverse path, or configure a trusted `wss://` endpoint. A `192.168.x.x` or `10.x.x.x` URL is not accepted
merely because it is private.

### The app cannot connect

Confirm that the foreground `codex app-server` process is still running, the BOOX device can reach the
endpoint and port, and the saved token exactly matches the helper's current token file. Check firewall and
Tailscale policy without posting raw logs, tokens, hostnames, account/device identifiers, prompts, command
output, or diffs publicly.

### Managed Remote reports that compatibility is not verified

This is expected. The UI accepts pairing input only so the gated product flow can be developed safely; the
current controller does not send, persist, or redeem it. Use Direct diagnostic mode for live development.

### Background connection stops after the screen sleeps

Verify notification permission, battery-optimization exemption, BOOX Freeze/autostart settings, and that
the persistent connection notification remains present. Firmware updates can reset vendor-specific power
settings. Tailscale itself may also need the equivalent background and battery exemptions.

### A saved profile no longer decrypts

Android Keystore material can be invalidated by app-data restoration attempts, signing/package changes, or
device security changes. Use **Forget host**, rotate the host token if its custody is uncertain, and create a
new profile. The app intentionally does not back up connection credentials.
