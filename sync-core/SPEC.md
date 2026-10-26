# diligent-link v1 specification

This file is the source of truth for diligent-link, the protocol that Diligent (Android), the desktop companion and the iPhone app use to discover, pair with and sync to each other. It was copied verbatim from Appendix A of Diligent's AGENT_GUIDE.md when `sync-core` was created; from now on this copy wins over the guide and over any app's code. Do not edit the protocol below in place: a change in behaviour needs a new protocol version, so that devices running v1 keep working with each other. Notes on how `sync-core` reads the places where v1 is silent live in the library's KDoc, not here.

## Appendix A: diligent-link v1 (shared by all three apps)

Copy this appendix into `<APPS_REPO>/sync-core/SPEC.md` in Phase 5. After that, `SPEC.md` is the source of truth; changes need a new protocol version.

### A.1 Identity

- Each device creates, once, a self-signed X.509 certificate with an ECDSA P-256 key, valid 20 years. The private key lives in the platform's secure storage (Android Keystore wrapping, iOS Keychain, desktop OS keychain or an encrypted file).
- **Device ID** = SHA-256 of the certificate (DER), base32 without padding, first 32 characters, shown in groups of 4 (`ABCD-EFGH-...`).
- Each device has a user-editable display name and a platform: `android`, `ios`, `windows`, `macos` or `linux`.

### A.2 Discovery

- DNS-SD service type `_diligent._tcp` on the local network.
- TXT record: `v=1`, `name`, `platform`, `id` (device ID), `pair=1` when pairing requests are accepted (else `0`).
- Default port 47321; if busy, any free port, advertised in DNS-SD.
- Manual fallback: an IP address and port, or a QR code containing `diligent://<ip>:<port>/<device ID>`.

### A.3 Reachability

- Android: listening while the app is open, while a task runs, or always when "Stay reachable in the background" is on.
- Desktop: listening while the app runs (it lives in the tray).
- iPhone: listening only while the app is open (iOS suspends apps in the background).

### A.4 Transport

- TCP with TLS 1.3, both sides presenting their certificate (mutual TLS).
- Before pairing, any certificate is accepted, but only pairing messages are allowed.
- After pairing, only the pinned fingerprint of that peer is accepted. A different certificate for a known device ID is refused and reported as an identity change.
- Tailscale uses the same protocol to the peer's `100.64.0.0/10` address.
- Android phone to phone (Nearby Connections) runs the same TLS session over the Nearby byte stream.

### A.5 Framing

- Every message is a frame: 4-byte big-endian length, then UTF-8 JSON with at least `type` and `id`.
- File data: a JSON header frame `{ "type": "file.data", "id", "path", "offset", "length" }` immediately followed by `length` raw bytes.
- Unknown types are answered with `{ "type": "error", "code": "unsupported" }`.

### A.6 Pairing (6-digit code, SRP-6a)

1. Requester sends `pair.request` `{ name, platform, deviceId }`.
2. The target shows "<name> wants to connect. Code: 482 913". The code is 6 digits from a cryptographically secure random generator, valid 120 seconds, single use.
3. SRP-6a, RFC 5054 3072-bit group, SHA-512, username `diligent`, password = the 6 digits: target sends `pair.challenge` `{ salt, B }`; requester sends `pair.proof` `{ A, M1 }`; target verifies and sends `pair.confirm` `{ M2 }`; requester verifies `M2`.
4. Both derive the session key `K`. Each sends `pair.bind` `{ deviceId, name, platform, mac }` where `mac` = HMAC-SHA512(K, own certificate fingerprint followed by the peer's fingerprint). Each verifies the other's `mac`, which binds the TLS identities to the code and defeats a man in the middle.
5. Both store the pairing record and pin the peer's fingerprint.
6. Limits: 5 failed proofs lock pairing on the target for 5 minutes; a new request cancels an older pending code.
7. Interoperability: JVM uses BouncyCastle SRP6; iOS uses a maintained Swift SRP-6a library. A cross-platform test with fixed inputs must pass before release; if the implementations disagree, stop and ask.

**Pairing record:** `{ deviceId, name, platform, fingerprint, pairedAt, approval: "always" | "ask", lastSeen, lastSync, lastAddresses: { lan, tailscale } }`. Forget deletes the record and, when reachable, sends `pair.forget` to the peer.

### A.7 Sync (manual by default)

- **Project identity:** `.diligent/project.json` holds `{ "projectId": "<uuid>", "name" }` and is synced.
- **Manifest entry:** `{ path, size, mtimeMs, sha256, executable }` for every file not ignored; paths use `/`, relative to the project root.
- **Never synced by default:** `.git`, `node_modules`, `.venv`, `venv`, `__pycache__`, `dist`, `build`, `.next`, `.gradle`, `target`, `.diligent/logs`, `.idea/workspace.xml`, `.idea/shelf/`, `.env*`, plus anything in `.gitignore` and `.diligentignore`. `.env*` syncs only when allowed for that project on both devices. Other editor settings (`.vscode/`, the rest of `.idea/`) sync unless the user excludes them.
- **Unsaved changes:** apps that edit files save them before building a manifest. Apps that cannot see another editor's unsaved changes (the desktop app) remind the user to save first.
- **Baseline:** after each successful sync, both devices store the agreed manifest for that pair, in app data, never inside the project.
- **Comparison** (local L, remote R, baseline B):

  | Situation | Action |
  | --- | --- |
  | L changed since B, R unchanged | Send L |
  | R changed since B, L unchanged | Receive R |
  | Both changed and contents differ | Conflict |
  | Both changed to identical contents | Nothing |
  | New on both sides with different contents | Conflict |
  | Deleted on one side, unchanged on the other | Delete on the other side (after its snapshot) |
  | Deleted on one side, changed on the other | Conflict (keep the changed file) |
  | No baseline yet (first sync) | Same path with different contents is a conflict; otherwise copy missing files both ways |

- **Messages:** `sync.hello` `{ projectId }`, `sync.manifest` `{ entries }`, `sync.plan` `{ send, receive, delete, conflicts }`, `sync.confirm`, `file.request` `{ path }`, `file.data`, `sync.done` `{ manifest }`, `sync.cancel`.
- **Safety:** both sides snapshot before applying anything. Received files go to a temporary file, are checked against `sha256`, then renamed into place. A conflict keeps the local file and saves the remote one as `<name> (from <Device>).<ext>`; the apps then show a compare screen.
- **Approval:** if the responder's record says `ask`, it shows "<Device> wants to sync <project>. Allow?" before sending its manifest.
- **Automatic mode** repeats the same flow after saves or on a schedule, only when reachable, and stops while a conflict is unresolved.

### A.8 Remote run

- `run.start` `{ projectId, profile }` (the host offers a sync first if its copy is older), `run.stop`, `run.status`, `run.log` (streamed lines), `run.exit` `{ code }`.
- **Preview proxy:** the host opens a TCP proxy on the interface the session uses (LAN or Tailscale address), on a random port, forwarding to `127.0.0.1:<profile port>`, accepting connections only from the requester's current IP, closed when the run stops. The host replies `run.preview` `{ host, port }`.
- Profiles use the same `.diligent/run.json` format on every platform.

### A.9 AI relay (desktop only)

- `ai.info` returns whether Ollama is reachable on the desktop and which models exist.
- The desktop opens the same kind of IP-restricted proxy to its local Ollama port for the requesting device and replies `ai.endpoint` `{ host, port }`; it closes when the requester disconnects.
