# Detailed Team Contribution Ledger

Project: Real-Time Authoritative Multiplayer Game Server
Team: Arnav Karwa, Krish Lodha, Samay Gandhi
Guide: Suhas Joshi

This document provides a detailed, file-level contribution mapping for report and submission use.

## 1. Arnav Karwa - Frontend and Integration

### 1.1 Primary ownership summary

- Owned browser UI and client interaction flow.
- Owned frontend-to-backend WebSocket integration from browser side.
- Owned final integration verification across server, bots, and browser demo.
- Co-owned report structuring and documentation polish.

### 1.2 File-level detailed contributions

#### `demo/frontend/index.html`

- Designed the UI layout with a two-panel structure (controls + viewport).
- Added all user input controls required for demo execution:
  - server URL input
  - proto path input
  - lobby name and max players
  - join lobby ID field
  - connect/create/join action buttons
- Added HUD blocks for real-time indicators:
  - connection status
  - lobby ID
  - active player count
  - simulation tick count
- Integrated external assets:
  - Google Fonts (Space Grotesk)
  - protobuf.js CDN script
  - local `app.js` and `styles.css`
- Defined canvas and logging region for live state visualization.

#### `demo/frontend/styles.css`

- Created complete visual theme using CSS custom properties and layered gradients.
- Implemented responsive two-column to single-column layout via media queries.
- Styled all form controls, action buttons, HUD cards, canvas, and log output.
- Added interaction polish:
  - hover and disabled states for buttons
  - panel depth and blur effects
  - entrance animations (`slide-in`, `fade-up`)
- Established readable visual hierarchy for report-ready screenshots.

#### `demo/frontend/app.js`

- Implemented frontend state model for websocket, schema, lobby, players, keys, and input sequence tracking.
- Implemented protobuf schema loading with multi-path fallback behavior.
- Implemented packet construction and sending flow:
  - packet envelope encoding
  - typed payload encoding
  - typed dispatch function for CREATE_LOBBY, JOIN_LOBBY, PLAYER_INPUT
- Implemented websocket lifecycle handling:
  - connect
  - onopen/onmessage/onclose/onerror
- Implemented incoming packet decoding and response handling for:
  - CREATE_LOBBY response
  - JOIN_LOBBY response
  - STATE_UPDATE snapshot
- Implemented real-time HUD refresh and authoritative canvas rendering.
- Implemented keyboard-driven input loop with 100 ms dispatch interval.
- Implemented utility features:
  - logging buffer capping
  - deterministic per-player color hashing
- Ensured movement visualization is server-authoritative (snapshot-driven rendering only).

#### `README.md` (integration-facing parts)

- Added/validated frontend run instructions and browser flow.
- Added practical sequence for end-to-end demo execution:
  - start server
  - run bots
  - host static frontend
  - connect and join lobby
- Added clear operator guidance for interacting with demo lobby.

#### `report.md`

- Structured report sections for formal submission flow.
- Added screenshot placement references and integrated figure captions.
- Aligned report content with actual frontend behavior shown in screenshots.

### 1.3 Integration checkpoints handled

- Verified frontend connects to `ws://localhost:8080/game`.
- Verified create/join UI pathways align with backend packet schema.
- Verified tick, player count, and lobby ID HUD values update from server responses.
- Verified screenshot-ready states:
  - connected idle state
  - active lobby with multiple player snapshots

---

## 2. Krish Lodha - Backend and Networking

### 2.1 Primary ownership summary

- Owned backend transport setup and networking path in Netty.
- Owned packet routing and channel-to-lobby association flow.
- Owned server bootstrap and shutdown lifecycle handling.
- Owned auth-routing integration with persistence layer for login handling.

### 2.2 File-level detailed contributions

#### `src/main/java/com/multiplayer/server/network/GameServer.java`

- Implemented server bootstrap entry point.
- Configured boss and worker event loop groups.
- Set channel options (`SO_BACKLOG`, `SO_KEEPALIVE`, `TCP_NODELAY`).
- Wired server initializer into child pipeline.
- Added server startup logging and graceful shutdown flow.
- Added JVM shutdown hook integration for proper cleanup.
- Connected DB manager and lobby manager lifecycle into server lifecycle.

#### `src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java`

- Implemented production transport pipeline for `/game` path.
- Added HTTP handshake and upgrade handlers:
  - `HttpServerCodec`
  - `HttpObjectAggregator`
  - `ChunkedWriteHandler`
- Added websocket protocol handling and compression support.
- Connected application frame handler (`WebSocketFrameHandler`) to the pipeline.

#### `src/main/java/com/multiplayer/server/network/WebSocketChannelInitializer.java`

- Implemented alternative initializer variant for websocket setup.
- Added named pipeline stages for easier debugging and maintenance.
- Wired `WebSocketFrameHandler` with `MessageRouter` dependencies.

#### `src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java`

- Implemented frame-type validation and binary-only acceptance policy.
- Implemented protobuf packet decode from frame bytes.
- Routed decoded packets to `MessageRouter`.
- Added malformed frame handling and channel close path.
- Added exception handling for channel-level robustness.
- Added disconnect hook to trigger lobby cleanup via router.
- Ensured safe buffer lifecycle through `SimpleChannelInboundHandler` auto-release.

#### `src/main/java/com/multiplayer/server/network/MessageRouter.java`

- Implemented core dispatch switch for packet types.
- Implemented login request parsing and response generation.
- Offloaded blocking auth work to virtual thread path.
- Implemented create/join/leave lobby message handlers.
- Implemented channel-to-lobby map for fast input routing and cleanup.
- Implemented player-input forwarding to lobby simulation queue.
- Implemented safe disconnect cleanup path (`onDisconnect`).
- Implemented packet response helper to wrap typed payload into envelope and send as binary frame.

#### `src/main/java/com/multiplayer/server/network/GameFrameHandler.java`

- Implemented handshake, connect/disconnect, and frame logging handler.
- Added binary/text frame differentiation scaffold for extension.
- Preserved as diagnostic/extension handler in networking module.

#### `src/main/java/com/multiplayer/server/db/DatabaseManager.java`

- Implemented HikariCP datasource creation and pool sizing.
- Configured default in-memory H2 JDBC setup for development.
- Implemented schema initialization (`users` table creation).
- Implemented connection accessor and datasource shutdown path.
- Added prepared baseline for MySQL migration via constructor overload.

#### `src/main/java/com/multiplayer/server/db/UserRepository.java`

- Implemented authenticate, register, and userExists data operations.
- Implemented secure query construction using prepared statements.
- Implemented SHA-256 hashing utility for password verification flow.
- Added operational logging for auth outcomes and DB errors.

#### `pom.xml`

- Defined Java 21 compile target.
- Configured Netty, Protobuf, HikariCP, H2, logging dependencies.
- Configured protobuf code generation plugin.
- Configured compiler and jar plugins for executable packaging.
- Configured OS plugin extension for protoc compatibility.

#### `start_server.bat`

- Added simplified server startup script path for Windows execution.
- Added setup mode to persist environment-related convenience values.

### 2.3 Networking and backend validation checkpoints

- Verified websocket handshake and binary packet path.
- Verified request/response routing for login and lobby operations.
- Verified disconnect cleanup path prevents stale lobby mapping.
- Verified server starts on default port 8080 and shuts down cleanly.

---

## 3. Samay Gandhi - Game Logic, Testing and Validation

### 3.1 Primary ownership summary

- Owned lobby lifecycle and state-transition behavior validation.
- Owned deterministic game-loop operation and movement-state integrity validation.
- Owned bot client tooling for concurrent scenario execution.
- Owned test evidence generation and runtime behavior validation.

### 3.2 File-level detailed contributions

#### `src/main/java/com/multiplayer/server/lobby/Lobby.java`

- Implemented finite-state machine states and transition rules:
  - WAITING
  - COUNTDOWN
  - PLAYING
  - ENDED
- Implemented transition validation and CAS-based state updates.
- Implemented player add/remove logic with concurrent-safe collection strategy.
- Implemented automatic start/cancel countdown triggers by player count.
- Implemented game loop start and stop hooks on state changes.
- Implemented input bridge to simulation queue.
- Implemented running game join behavior (new player registration path).
- Implemented lobby lifecycle logging for state and membership traceability.

#### `src/main/java/com/multiplayer/server/lobby/LobbyManager.java`

- Implemented lobby registry maps and lookup paths.
- Implemented create/join/leave/remove lobby operations.
- Implemented persistent demo lobby behavior (`demo0001`) for repeatable demos.
- Implemented auto-cleanup when non-demo lobby becomes empty.
- Implemented remove-player-from-all on disconnect.
- Implemented join outcome record (`JoinLobbyResult`) for precise client feedback.

#### `src/main/java/com/multiplayer/server/game/GameLoop.java`

- Implemented fixed-timestep simulation loop at 60 Hz.
- Implemented tick pacing with adaptive sleep and spin-wait balancing.
- Implemented lock-free input queue drain on each tick.
- Implemented authoritative state update and world snapshot build.
- Implemented broadcast flow to all active lobby channels.
- Implemented player add/remove lifecycle management inside loop context.

#### `src/main/java/com/multiplayer/server/game/PlayerState.java`

- Implemented server-authoritative mutable player position model.
- Implemented input clamping for bounded directional control.
- Implemented speed scaling and world-bound clamping.
- Implemented concise runtime state representation for logs/debug.

#### `src/main/java/com/multiplayer/server/demo/DemoBotClient.java`

- Implemented websocket bot client with packet encode/decode path.
- Implemented create-lobby and join-lobby request helpers.
- Implemented periodic randomized movement sender for load-like behavior.
- Implemented response parsing for create/join/state-update messages.
- Implemented fragmented binary frame reassembly in listener.
- Implemented controlled close and input-scheduler shutdown.

#### `src/main/java/com/multiplayer/server/demo/DemoBotRunner.java`

- Implemented host-plus-joiner bot orchestration pattern.
- Implemented existing-lobby and create-lobby flow selection.
- Implemented join fan-out and aggregate completion waiting.
- Implemented run-until-enter interactive control for demo sessions.
- Implemented orderly bot resource cleanup in finally block.

#### `src/main/proto/game_messages.proto`

- Defined packet envelope and typed message catalog used by server and clients.
- Defined lobby request/response messages.
- Defined input and world snapshot messages.
- Maintained schema compatibility needed for Java and JS consumers.

#### `PROJECT_GUIDE.md`

- Prepared reviewer-focused system explanation with run flow, architecture mapping, and rationale.
- Documented concurrency strategy, transport flow, and protocol design for evaluation readiness.

### 3.3 Testing and validation checkpoints

- Verified multi-bot join and concurrent input flow.
- Verified state update broadcast and periodic tick progression.
- Verified lobby transitions under join/leave edge conditions.
- Verified deterministic movement boundaries and input clamping behavior.

---

## 4. Shared and Cross-Team Contributions

### 4.1 Shared integration activities

- End-to-end connectivity testing across server, bots, and browser UI.
- Protocol compatibility checks between Protobuf schema and runtime parsers.
- Runtime logging review during debugging and stabilization.

### 4.2 Shared documentation activities

- Refinement of `report.md` for formal academic submission.
- Architecture diagrams and section consistency cleanup.
- Figure placement and screenshot linkage for outcome evidence.

### 4.3 Shared release-readiness activities

- Verification of startup instructions and command sequence in `README.md`.
- Validation of reproducible local demo setup on Windows.

---

## 5. Complete File Ownership Matrix

| File                                                                            | Primary Owner | Secondary Support         |
| ------------------------------------------------------------------------------- | ------------- | ------------------------- |
| `src/main/java/com/multiplayer/server/network/GameServer.java`                  | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java`  | Krish Lodha   | Arnav Karwa               |
| `src/main/java/com/multiplayer/server/network/WebSocketChannelInitializer.java` | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java`       | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/network/GameFrameHandler.java`            | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/network/MessageRouter.java`               | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/lobby/LobbyManager.java`                  | Samay Gandhi  | Krish Lodha               |
| `src/main/java/com/multiplayer/server/lobby/Lobby.java`                         | Samay Gandhi  | Krish Lodha               |
| `src/main/java/com/multiplayer/server/game/GameLoop.java`                       | Samay Gandhi  | Krish Lodha               |
| `src/main/java/com/multiplayer/server/game/PlayerState.java`                    | Samay Gandhi  | Krish Lodha               |
| `src/main/java/com/multiplayer/server/db/DatabaseManager.java`                  | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/db/UserRepository.java`                   | Krish Lodha   | Samay Gandhi              |
| `src/main/java/com/multiplayer/server/demo/DemoBotClient.java`                  | Samay Gandhi  | Arnav Karwa               |
| `src/main/java/com/multiplayer/server/demo/DemoBotRunner.java`                  | Samay Gandhi  | Arnav Karwa               |
| `src/main/proto/game_messages.proto`                                            | Samay Gandhi  | Krish Lodha               |
| `demo/frontend/index.html`                                                      | Arnav Karwa   | Samay Gandhi              |
| `demo/frontend/styles.css`                                                      | Arnav Karwa   | Samay Gandhi              |
| `demo/frontend/app.js`                                                          | Arnav Karwa   | Krish Lodha               |
| `README.md`                                                                     | Arnav Karwa   | Krish Lodha               |
| `PROJECT_GUIDE.md`                                                              | Samay Gandhi  | Krish Lodha               |
| `report.md`                                                                     | Arnav Karwa   | Krish Lodha, Samay Gandhi |
| `pom.xml`                                                                       | Krish Lodha   | Samay Gandhi              |
| `start_server.bat`                                                              | Krish Lodha   | Arnav Karwa               |

---

## 6. Note for Evaluation Use

This contribution ledger is organized for academic evaluation and aligns with the role distribution section in `report.md`.
