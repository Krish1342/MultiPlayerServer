const ui = {
  serverUrl: document.getElementById("serverUrl"),
  protoPath: document.getElementById("protoPath"),
  lobbyName: document.getElementById("lobbyName"),
  maxPlayers: document.getElementById("maxPlayers"),
  joinLobbyId: document.getElementById("joinLobbyId"),
  connectBtn: document.getElementById("connectBtn"),
  createBtn: document.getElementById("createBtn"),
  joinBtn: document.getElementById("joinBtn"),
  statusText: document.getElementById("statusText"),
  lobbyText: document.getElementById("lobbyText"),
  playersText: document.getElementById("playersText"),
  tickText: document.getElementById("tickText"),
  log: document.getElementById("log"),
  canvas: document.getElementById("worldCanvas"),
};

const state = {
  ws: null,
  schema: null,
  packetTypeEnum: null,
  packetTypeById: {},
  lobbyId: "",
  tick: 0,
  players: [],
  ready: false,
  keys: new Set(),
  inputSeq: 0,
  inputTimer: null,
  myHint: "",
};

const canvas = ui.canvas;
const ctx = canvas.getContext("2d");

function logLine(message) {
  const ts = new Date().toLocaleTimeString();
  ui.log.textContent = `[${ts}] ${message}\n${ui.log.textContent}`.slice(
    0,
    5000,
  );
}

async function loadProtoSchema() {
  const candidates = [];

  const customPath = ui.protoPath.value.trim();
  if (customPath) {
    candidates.push(customPath);
  }

  candidates.push("../../src/main/proto/game_messages.proto");
  candidates.push("/src/main/proto/game_messages.proto");
  candidates.push("../src/main/proto/game_messages.proto");

  let lastError = null;
  for (const path of candidates) {
    try {
      const root = await protobuf.load(path);
      logLine(`Loaded proto from ${path}`);
      return root;
    } catch (err) {
      lastError = err;
    }
  }

  throw new Error(
    `Unable to load proto file. Last error: ${String(lastError)}`,
  );
}

function packetTypeValue(typeName) {
  return state.packetTypeEnum[typeName];
}

function encodePacket(typeName, payloadBuffer) {
  const Packet = state.schema.Packet;
  const typeValue = packetTypeValue(typeName);
  if (typeValue === undefined) {
    throw new Error(`Unknown packet type: ${typeName}`);
  }

  const packet = Packet.create({
    type: typeValue,
    payload: payloadBuffer,
    timestamp: Date.now(),
  });
  return Packet.encode(packet).finish();
}

function sendPacket(typeName, payloadMessageType, payloadObject) {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
    logLine("WebSocket is not open");
    return;
  }

  const payloadMsg = payloadMessageType.create(payloadObject);
  const payloadBytes = payloadMessageType.encode(payloadMsg).finish();
  logLine(`Sending ${typeName}`);
  state.ws.send(encodePacket(typeName, payloadBytes));
}

function setConnectionStatus(status) {
  ui.statusText.textContent = status;
}

function refreshHud() {
  ui.playersText.textContent = String(state.players.length);
  ui.tickText.textContent = String(state.tick);
  ui.lobbyText.textContent = state.lobbyId || "-";
}

function drawWorld() {
  const width = canvas.width;
  const height = canvas.height;

  ctx.clearRect(0, 0, width, height);

  ctx.save();
  ctx.translate(width / 2, height / 2);

  for (const p of state.players) {
    const x = p.x * 0.5;
    const y = -p.y * 0.5;

    const hue = Math.abs(hashCode(p.playerId)) % 360;
    ctx.fillStyle = `hsl(${hue} 78% 45%)`;

    ctx.beginPath();
    ctx.arc(x, y, 12, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = "#0f1d35";
    ctx.font = "12px 'Space Grotesk', sans-serif";
    ctx.fillText(p.playerId, x + 14, y + 4);
  }

  ctx.restore();
}

function hashCode(value) {
  let hash = 0;
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash << 5) - hash + value.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}

function decodeIncomingPacket(arrayBuffer) {
  const Packet = state.schema.Packet;
  const decoded = Packet.decode(new Uint8Array(arrayBuffer));
  const typeName = state.packetTypeById[decoded.type] || "UNKNOWN";

  if (typeName === "CREATE_LOBBY") {
    const response = state.schema.CreateLobbyResponse.decode(decoded.payload);
    if (response.success) {
      state.lobbyId = response.lobbyId;
      ui.joinLobbyId.value = response.lobbyId;
      logLine(`Lobby created: ${response.lobbyId}`);
    } else {
      logLine(`Create lobby failed: ${response.message}`);
    }
    refreshHud();
    return;
  }

  if (typeName === "JOIN_LOBBY") {
    const response = state.schema.JoinLobbyResponse.decode(decoded.payload);
    if (response.success) {
      state.lobbyId = response.lobbyId;
      logLine(
        `Joined lobby ${response.lobbyId}. Players: ${response.playerCount}`,
      );
    } else {
      logLine(`Join lobby failed: ${response.message}`);
    }
    refreshHud();
    return;
  }

  if (typeName === "STATE_UPDATE") {
    const snapshot = state.schema.WorldStateSnapshot.decode(decoded.payload);
    state.tick = Number(snapshot.tick);
    state.players = snapshot.players;
    refreshHud();
    drawWorld();
    return;
  }

  logLine(`Received packet type: ${typeName}`);
}

function startInputLoop() {
  if (state.inputTimer) {
    return;
  }

  state.inputTimer = setInterval(() => {
    if (!state.lobbyId || !state.ready) {
      return;
    }

    const left = state.keys.has("ArrowLeft") || state.keys.has("a");
    const right = state.keys.has("ArrowRight") || state.keys.has("d");
    const up = state.keys.has("ArrowUp") || state.keys.has("w");
    const down = state.keys.has("ArrowDown") || state.keys.has("s");

    let dx = 0;
    let dy = 0;

    if (left) dx -= 1;
    if (right) dx += 1;
    if (up) dy += 1;
    if (down) dy -= 1;

    if (dx === 0 && dy === 0) {
      return;
    }

    state.inputSeq += 1;

    sendPacket("PLAYER_INPUT", state.schema.InputPacket, {
      playerId: state.myHint,
      dx,
      dy,
      sequence: state.inputSeq,
    });
  }, 100);
}

function stopInputLoop() {
  if (state.inputTimer) {
    clearInterval(state.inputTimer);
    state.inputTimer = null;
  }
}

async function connect() {
  ui.connectBtn.disabled = true;
  setConnectionStatus("connecting");

  try {
    const root = await loadProtoSchema();

    state.schema = {
      Packet: root.lookupType("com.multiplayer.server.Packet"),
      CreateLobbyRequest: root.lookupType(
        "com.multiplayer.server.CreateLobbyRequest",
      ),
      JoinLobbyRequest: root.lookupType(
        "com.multiplayer.server.JoinLobbyRequest",
      ),
      InputPacket: root.lookupType("com.multiplayer.server.InputPacket"),
      CreateLobbyResponse: root.lookupType(
        "com.multiplayer.server.CreateLobbyResponse",
      ),
      JoinLobbyResponse: root.lookupType(
        "com.multiplayer.server.JoinLobbyResponse",
      ),
      WorldStateSnapshot: root.lookupType(
        "com.multiplayer.server.WorldStateSnapshot",
      ),
    };
    state.packetTypeEnum = root.lookupEnum(
      "com.multiplayer.server.Packet.Type",
    ).values;
    state.packetTypeById = Object.fromEntries(
      Object.entries(state.packetTypeEnum).map(([name, id]) => [id, name]),
    );

    const ws = new WebSocket(ui.serverUrl.value.trim());
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
      state.ws = ws;
      state.ready = true;
      state.myHint = `frontend-${Math.random().toString(36).slice(2, 8)}`;
      ui.createBtn.disabled = false;
      ui.joinBtn.disabled = false;
      setConnectionStatus("connected");
      logLine("WebSocket connected");
      startInputLoop();
    };

    ws.onmessage = (event) => {
      decodeIncomingPacket(event.data);
    };

    ws.onclose = () => {
      setConnectionStatus("closed");
      state.ready = false;
      ui.createBtn.disabled = true;
      stopInputLoop();
      logLine("WebSocket closed");
    };

    ws.onerror = () => {
      logLine("WebSocket error");
    };
  } catch (err) {
    setConnectionStatus("error");
    logLine(`Connect failed: ${String(err.message || err)}`);
  } finally {
    ui.connectBtn.disabled = false;
  }
}

ui.connectBtn.addEventListener("click", connect);

ui.createBtn.addEventListener("click", () => {
  if (!state.ready) {
    return;
  }

  const maxPlayers = Number(ui.maxPlayers.value);
  sendPacket("CREATE_LOBBY", state.schema.CreateLobbyRequest, {
    lobbyName: ui.lobbyName.value.trim() || `frontend-${Date.now()}`,
    maxPlayers,
  });
});

ui.joinBtn.addEventListener("click", () => {
  if (!state.ready) {
    return;
  }

  const lobbyId = ui.joinLobbyId.value.trim();
  if (!lobbyId) {
    logLine("Enter a lobby id to join.");
    return;
  }

  sendPacket("JOIN_LOBBY", state.schema.JoinLobbyRequest, { lobbyId });
});

window.addEventListener("keydown", (event) => {
  state.keys.add(event.key);
});

window.addEventListener("keyup", (event) => {
  state.keys.delete(event.key);
});

drawWorld();
refreshHud();
