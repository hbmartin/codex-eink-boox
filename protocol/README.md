# Protocol module boundaries

The `protocol` module contains two deliberately different maturity levels.

## Active direct path

`JsonRpcCodec`, `CodexConnection`, and `DirectWebSocketConnection` support the app's **Direct diagnostic**
mode. `ProtocolCodexController` in the `app` module constructs this connection and maps its app-server
messages into UI-owned models. This path starts a dedicated `codex app-server` listener; it does not attach
to a task owned by a separate Codex Desktop process.

Direct mode is useful for protocol and e-ink UI development, but it requires the user to operate and secure
the listener. A debug build permits cleartext WebSockets only over loopback or recognized Tailscale
addresses. A release build requires `wss://`. The transport tradeoffs and host commands live in
[`docs/SETUP.md`](../docs/SETUP.md#choose-a-connection-path).

## Experimental managed-relay primitives

`ManagedRelayConnection`, its config, relay envelope/chunk codecs, replay buffer, and `CodexStateReducer`
are compatibility scaffolding. Managed connection types require an explicit Kotlin opt-in, and the Android
connection factory still rejects `ManagedRelay` profiles. `CodexStateReducer` is internal and test-only.

Their tests establish narrow properties such as decoding, unknown-field preservation, bounded assembly,
ACK/replay behavior, and deterministic state updates. They do **not** establish:

- an independent controller enrollment flow;
- legitimate authentication or token acquisition;
- compatibility with a live managed relay;
- reconnect behavior against a real enrolled host; or
- end-to-end co-control of a user-owned task.

Promoting this path requires a clean-room end-to-end test against a user-owned host and a legitimate
independent authentication flow. Until then, keep the runtime gate closed and do not describe managed
relay unit tests as working Managed Remote support.
