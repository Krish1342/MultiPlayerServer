# MultiPlayerServer

This repository now includes an end-to-end demo setup:

1. Netty WebSocket server on `/game` using Protobuf packets.
2. Java demo bots that create/join a lobby and send player inputs.
3. Browser frontend that connects via WebSocket and renders server-authoritative world snapshots.

## Prerequisites

- Java 21 installed.
- `JAVA_HOME` pointing to the Java 21 installation.
- Maven available on `PATH`.

## Run The Server

```powershell
mvn -DskipTests exec:java -Dexec.mainClass="com.multiplayer.server.network.GameServer"
```

The server listens on port `8080` by default.

### Windows one-time setup + start script

From repository root:

```powershell
.\start_server.bat --setup
```

This persists `JAVA_HOME`, `MAVEN_HOME`, and appends their `bin` folders to your user `PATH`.

Then start the server any time with:

```powershell
.\start_server.bat
```

## Run Demo Bots

Open a second terminal:

```powershell
mvn -DskipTests exec:java -Dexec.mainClass="com.multiplayer.server.demo.DemoBotRunner" -Dexec.args="ws://localhost:8080/game 6 12"
```

Args format:

`<wsUrl> <botCount> <maxPlayers> [existingLobbyId]`

Example above starts 6 bots with max 12 players.

By default, bots now create/reuse a stable demo lobby with ID `demo0001`.
You can join this lobby from the browser frontend to see bots moving.

To make bots join a lobby created in the browser UI, pass the lobby id as the 4th arg:

```powershell
mvn -DskipTests exec:java -Dexec.mainClass="com.multiplayer.server.demo.DemoBotRunner" -Dexec.args="ws://localhost:8080/game 6 12 ab12cd34"
```

PowerShell note: use `-Dexec.mainClass` (not `.mainClass`).

## Run Browser Frontend

Open a third terminal from repository root:

```powershell
python -m http.server 5500
```

Then open:

`http://localhost:5500/demo/frontend/index.html`

In UI:

1. Click `Connect`.
2. Click `Create Lobby` or join an existing lobby ID.
3. Move with `WASD` or arrow keys.

The canvas shows only server snapshots (`STATE_UPDATE`), not client-predicted positions.
