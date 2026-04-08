# Multiplayer Server Review Guide

This document is designed for project review preparation. It explains the full system in practical detail:

- what is used
- why it is used
- what is done
- why it is done

It also includes run steps, architecture flow, file-by-file explanation, strengths, risks, and likely reviewer questions.

## 1) Project Purpose

This is a server-authoritative multiplayer game demo.

High-level goal:

- clients send only input (move intent)
- server simulates the world state
- server broadcasts authoritative snapshots
- clients render server snapshots

Why this matters:

- prevents client-side cheating for core movement/state
- keeps all players in sync with one source of truth

## 2) Tech Stack (What + Why)

| What is used                                | Why it is used                                                | What is done                                                    | Why it is done                               |
| ------------------------------------------- | ------------------------------------------------------------- | --------------------------------------------------------------- | -------------------------------------------- |
| Java 21                                     | modern language/runtime + virtual threads + stable LTS target | compiles/runs backend services                                  | strong concurrency model and maintainability |
| Maven                                       | standard Java build lifecycle                                 | dependency resolution, protobuf generation, compile/package/run | reproducible build and easy onboarding       |
| Netty 4.1                                   | high-performance non-blocking networking                      | WebSocket server and packet transport pipeline                  | scalable concurrent socket handling          |
| WebSocket                                   | bidirectional full-duplex protocol for real-time apps         | browser and bot realtime communication                          | low-latency game messaging                   |
| Protocol Buffers                            | compact schema-driven binary protocol                         | packet schema, code generation, typed payloads                  | efficient serialization and strict contract  |
| HikariCP                                    | fast JDBC connection pool                                     | pooled DB connections                                           | low DB overhead and good throughput          |
| H2 (dev)                                    | lightweight in-memory DB                                      | local auth storage during demo                                  | zero external DB setup in dev                |
| JDBC (plain)                                | direct SQL access with minimal abstraction                    | user auth queries and inserts                                   | performance simplicity, lower complexity     |
| SLF4J + Logback                             | standard logging facade and backend                           | structured console logs for server/bots                         | observability and debugging                  |
| Browser frontend (HTML/CSS/JS + protobufjs) | no build tooling required for demo UI                         | connect/join/create lobby, send input, render snapshots         | quick visual verification and demos          |

## 3) Repository Structure

- Backend source: [src/main/java/com/multiplayer/server](src/main/java/com/multiplayer/server)
- Protobuf schema: [src/main/proto/game_messages.proto](src/main/proto/game_messages.proto)
- Logging config: [src/main/resources/logback.xml](src/main/resources/logback.xml)
- Frontend demo: [demo/frontend](demo/frontend)
- Build config: [pom.xml](pom.xml)
- Startup helper: [start_server.bat](start_server.bat)
- Run instructions: [README.md](README.md)

## 4) Build and Run (Exact Flow)

## 4.1 Prerequisites

Required:

- Java 21 installed
- Maven installed

Important:

- This project is configured for Java 21 with preview enabled in [pom.xml](pom.xml).
- If Java 25 is active, compilation can fail with source/preview mismatch.

## 4.2 Commands

Server:

```powershell
mvn --% -DskipTests exec:java -Dexec.mainClass=com.multiplayer.server.network.GameServer
```

Demo bots:

```powershell
mvn --% -DskipTests exec:java -Dexec.mainClass=com.multiplayer.server.demo.DemoBotRunner -Dexec.args="ws://localhost:8080/game 6 12"
```

Frontend static host:

```powershell
python -m http.server 5500
```

Then open:

- http://localhost:5500/demo/frontend/index.html

PowerShell note:

- Use `mvn --% ...` when passing dotted properties like `-Dexec.mainClass`.

## 4.3 What happens at runtime

1. server starts and creates DB pool
2. schema init creates users table if missing
3. stable demo lobby demo0001 is prepared
4. clients connect through WebSocket endpoint /game
5. packets are decoded and routed by type
6. player input enters per-lobby simulation queue
7. game loop ticks at 60 Hz and broadcasts state snapshots
8. frontend and bots display authoritative updates

## 5) Layered Architecture (Design Intent)

The system follows transport -> application -> persistence separation.

## 5.1 Transport Layer

Primary files:

- [src/main/java/com/multiplayer/server/network/GameServer.java](src/main/java/com/multiplayer/server/network/GameServer.java)
- [src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java](src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java)
- [src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java](src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java)

### What is used

- Netty ServerBootstrap
- NioEventLoopGroup
- Channel pipeline with HTTP codec + WebSocket protocol handler
- BinaryWebSocketFrame

### Why it is used

- non-blocking IO scales for many clients
- clean protocol upgrade flow HTTP -> WS
- binary framing aligns with protobuf payloads

### What is done

- binds server to port 8080
- upgrades websocket on /game
- accepts only binary frames
- parses protobuf Packet envelope

### Why it is done

- establish robust realtime wire transport for all gameplay/auth messages

## 5.2 Application Layer

Primary files:

- [src/main/java/com/multiplayer/server/network/MessageRouter.java](src/main/java/com/multiplayer/server/network/MessageRouter.java)
- [src/main/java/com/multiplayer/server/lobby/LobbyManager.java](src/main/java/com/multiplayer/server/lobby/LobbyManager.java)
- [src/main/java/com/multiplayer/server/lobby/Lobby.java](src/main/java/com/multiplayer/server/lobby/Lobby.java)
- [src/main/java/com/multiplayer/server/game/GameLoop.java](src/main/java/com/multiplayer/server/game/GameLoop.java)
- [src/main/java/com/multiplayer/server/game/PlayerState.java](src/main/java/com/multiplayer/server/game/PlayerState.java)

### What is used

- message routing switch by packet type
- thread-safe maps/lists and atomic state transitions
- finite-state machine (WAITING, COUNTDOWN, PLAYING, ENDED)
- single-thread per-lobby simulation loop
- concurrent input queue

### Why it is used

- keep logic deterministic and easy to reason about
- reduce lock contention by isolating simulation to one thread per lobby
- enforce legal lobby lifecycle transitions

### What is done

- handles LOGIN, CREATE_LOBBY, JOIN_LOBBY, LEAVE_LOBBY, PLAYER_INPUT
- maps channel -> lobby for fast routing and cleanup
- starts game loop when gameplay begins
- computes authoritative x/y positions and sends world snapshots

### Why it is done

- make server the source of truth
- keep gameplay consistent across all clients

## 5.3 Persistence Layer

Primary files:

- [src/main/java/com/multiplayer/server/db/DatabaseManager.java](src/main/java/com/multiplayer/server/db/DatabaseManager.java)
- [src/main/java/com/multiplayer/server/db/UserRepository.java](src/main/java/com/multiplayer/server/db/UserRepository.java)

### What is used

- HikariCP pooled datasource
- H2 in-memory JDBC URL in dev
- plain JDBC prepared statements

### Why it is used

- lightweight and fast local setup
- direct SQL control with low overhead

### What is done

- creates users table if missing
- supports register/authenticate/userExists
- stores password hash as SHA-256 hex

### Why it is done

- provide basic auth path for demo and architecture completeness

## 6) Concurrency Model (Critical for Review)

## 6.1 Netty Event Loop responsibilities

- accepts sockets
- processes websocket frames
- routes packets
- must stay non-blocking

Why:

- blocking here hurts all channels on same event loop thread

## 6.2 Blocking operations offloaded

In [src/main/java/com/multiplayer/server/network/MessageRouter.java](src/main/java/com/multiplayer/server/network/MessageRouter.java), login DB authentication is offloaded with virtual thread start.

What:

- blocking JDBC call runs outside event loop

Why:

- keeps transport thread responsive under load

## 6.3 Simulation isolation

In [src/main/java/com/multiplayer/server/lobby/Lobby.java](src/main/java/com/multiplayer/server/lobby/Lobby.java), each lobby owns one single-thread executor for simulation.

What:

- one thread runs that lobby game loop

Why:

- deterministic state updates and minimal locking complexity

## 7) Protocol Design (Packet Envelope Pattern)

Schema file:

- [src/main/proto/game_messages.proto](src/main/proto/game_messages.proto)

Design:

- top-level Packet has:
  - type enum
  - payload bytes
  - timestamp

Why this pattern:

- one transport frame type in network pipeline
- extensible message catalog without pipeline rewiring
- easy cross-language clients (Java and JS)

Main message groups:

- lobby: CreateLobbyRequest/Response, JoinLobbyRequest/Response, LeaveLobbyRequest
- game: InputPacket, PlayerSnapshot, WorldStateSnapshot
- auth: LoginRequest, LoginResponse

## 8) File-by-File Review Notes

## 8.1 Core network

### [src/main/java/com/multiplayer/server/network/GameServer.java](src/main/java/com/multiplayer/server/network/GameServer.java)

- used: Netty bootstrap, event loops
- done: server startup/shutdown lifecycle
- why: central runtime entry point

### [src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java](src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java)

- used: ordered Netty pipeline handlers
- done: protocol stack setup for /game
- why: cleanly separate transport stages

### [src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java](src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java)

- used: SimpleChannelInboundHandler auto release behavior
- done: parse binary packet and route, close on malformed packets
- why: memory safety and protocol strictness

### [src/main/java/com/multiplayer/server/network/MessageRouter.java](src/main/java/com/multiplayer/server/network/MessageRouter.java)

- used: switch routing, concurrent map, virtual thread for auth
- done: command handling and response writes
- why: central orchestration point for app logic

## 8.2 Lobby/game

### [src/main/java/com/multiplayer/server/lobby/LobbyManager.java](src/main/java/com/multiplayer/server/lobby/LobbyManager.java)

- used: concurrent maps and stable demo lobby id
- done: create/join/leave/remove orchestration
- why: consistent multi-lobby lifecycle management

### [src/main/java/com/multiplayer/server/lobby/Lobby.java](src/main/java/com/multiplayer/server/lobby/Lobby.java)

- used: FSM and atomic CAS transitions
- done: manages players, transitions, and game loop startup/shutdown
- why: state correctness and deterministic behavior

### [src/main/java/com/multiplayer/server/game/GameLoop.java](src/main/java/com/multiplayer/server/game/GameLoop.java)

- used: fixed timestep loop at 60 Hz, concurrent input queue
- done: process input and broadcast snapshots each tick
- why: authoritative real-time simulation

### [src/main/java/com/multiplayer/server/game/PlayerState.java](src/main/java/com/multiplayer/server/game/PlayerState.java)

- used: clamped input and bounded world coords
- done: applies movement updates
- why: prevent invalid movement and out-of-range state

## 8.3 Persistence

### [src/main/java/com/multiplayer/server/db/DatabaseManager.java](src/main/java/com/multiplayer/server/db/DatabaseManager.java)

- used: Hikari config and schema initialization
- done: creates pooled datasource and users table
- why: reliable DB access and fast startup

### [src/main/java/com/multiplayer/server/db/UserRepository.java](src/main/java/com/multiplayer/server/db/UserRepository.java)

- used: prepared statements and SHA-256
- done: register/authenticate/check user
- why: minimum viable auth data access

## 8.4 Demo clients

### [src/main/java/com/multiplayer/server/demo/DemoBotClient.java](src/main/java/com/multiplayer/server/demo/DemoBotClient.java)

- used: Java WebSocket client + scheduled virtual-thread input loop
- done: joins lobby and emits movement input periodically
- why: load and behavior simulation for demos

### [src/main/java/com/multiplayer/server/demo/DemoBotRunner.java](src/main/java/com/multiplayer/server/demo/DemoBotRunner.java)

- used: CompletableFuture flow orchestration
- done: starts host + N bots and drives demo lifecycle
- why: easy end-to-end verification script

## 8.5 Frontend

### [demo/frontend/index.html](demo/frontend/index.html)

- used: simple static page with control panel + canvas
- done: UI for connect/lobby and world rendering view
- why: visual review and manual interaction

### [demo/frontend/app.js](demo/frontend/app.js)

- used: protobufjs + browser WebSocket
- done: loads proto, encodes outgoing packets, decodes snapshots, draws players
- why: interoperable JS client against Java protobuf protocol

### [demo/frontend/styles.css](demo/frontend/styles.css)

- used: responsive layout, HUD styling, canvas background/grid
- done: readable modern demo UI
- why: better demo clarity and usability

## 8.6 Logging

### [src/main/resources/logback.xml](src/main/resources/logback.xml)

- used: console appender + debug level for project package
- done: prints detailed runtime events
- why: easier diagnostics in development and review

## 9) Data Flow End-to-End

1. client sends binary websocket frame
2. frame contains Packet(type, payload, timestamp)
3. server frame handler decodes Packet
4. router dispatches by Packet.Type
5. lobby/game/auth handlers execute
6. game loop updates authoritative state
7. server broadcasts STATE_UPDATE snapshots
8. clients render updated positions

Why this flow:

- keeps network concerns, domain logic, and persistence concerns separated

## 10) Design Strengths

- authoritative server loop
- clear layered structure
- explicit lobby state machine
- per-lobby single-thread simulation model
- protobuf contract reusable by Java and JS clients
- practical demo harness (bots + browser)

## 11) Risks / Gaps / Improvement Opportunities

1. security: SHA-256 password hashing is demo-grade

- improve with bcrypt/Argon2 + per-user salt

2. tests: no automated tests currently

- add unit tests for lobby FSM and message routing
- add integration tests for websocket packet flows

3. production DB config

- add environment-based datasource config for MySQL 8
- include migrations and startup health checks

4. resilience and validation

- add stronger payload validation and rate limits
- add flood/abuse protections for input packets

5. cleanup of legacy/alternative classes

- [src/main/java/com/multiplayer/server/network/GameFrameHandler.java](src/main/java/com/multiplayer/server/network/GameFrameHandler.java)
- [src/main/java/com/multiplayer/server/network/WebSocketChannelInitializer.java](src/main/java/com/multiplayer/server/network/WebSocketChannelInitializer.java)

These appear to be alternate older path compared to active pair:

- [src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java](src/main/java/com/multiplayer/server/network/WebSocketServerInitializer.java)
- [src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java](src/main/java/com/multiplayer/server/network/WebSocketFrameHandler.java)

## 12) Review Talking Points (Ready-to-Say)

Use these concise lines during your review:

- This is a server-authoritative multiplayer architecture where clients send input and never own final world state.
- Transport is Netty WebSocket with protobuf binary frames for low-latency typed messaging.
- The application layer routes packets by type, manages lobby lifecycle with an explicit FSM, and runs one simulation thread per lobby.
- Blocking JDBC authentication is offloaded to virtual threads so Netty event loops remain non-blocking.
- Every simulation tick produces a full authoritative world snapshot sent to connected clients.
- H2 plus HikariCP is used for development convenience and connection efficiency, with MySQL intended for production.

## 13) Likely Reviewer Questions and Suggested Answers

Q1: Why not trust client position updates?
A: trusting position from clients enables cheating and desync. Input-only clients plus server simulation keeps fairness and consistency.

Q2: Why protobuf over JSON?
A: protobuf is smaller and faster for frequent real-time messages, and schema contracts reduce integration ambiguity.

Q3: Why one game thread per lobby?
A: it simplifies correctness. With single-writer simulation, we avoid complex locking in core game state updates.

Q4: Why virtual threads here?
A: DB auth is blocking I/O. Virtual threads allow isolation of blocking work without blocking Netty event loops.

Q5: How does this scale?
A: event-loop networking scales connection handling, while per-lobby loops isolate simulation workload. Horizontal scaling would partition lobbies across server instances.

## 14) Quick Demo Script for Live Review

1. start server and show startup logs
2. start bots and show periodic tick/player logs
3. open frontend and connect
4. join demo lobby id or create one
5. move with WASD/arrow keys
6. explain that canvas follows server snapshots (STATE_UPDATE), not local prediction

## 15) Command Cheat Sheet

```powershell
# Use Java 21 in current terminal session (if needed)
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path="$env:JAVA_HOME\bin;" + $env:Path

# Build
mvn clean test

# Run server
mvn --% -DskipTests exec:java -Dexec.mainClass=com.multiplayer.server.network.GameServer

# Run bots
mvn --% -DskipTests exec:java -Dexec.mainClass=com.multiplayer.server.demo.DemoBotRunner -Dexec.args="ws://localhost:8080/game 6 12"

# Frontend host
python -m http.server 5500
```

Open:

- http://localhost:5500/demo/frontend/index.html

## 16) One-Line Summary

This project demonstrates a clean, server-authoritative multiplayer architecture with Netty + protobuf transport, lobby FSM orchestration, per-lobby deterministic simulation, and practical demo clients for review-ready verification.
