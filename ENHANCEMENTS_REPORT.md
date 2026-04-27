# Multiplayer Server Enhancements Report

Date: 2026-04-27
Project: MultiPlayerServer

## Summary
This report documents two gameplay enhancements implemented in the authoritative server pipeline:
1. Box collider based player-vs-player collision resolution.
2. Shift-based sprint speed multiplier.

---

## 1) Box Collider Enhancement

### Objective
Prevent players from occupying the same space and make movement interactions feel physically consistent.

### Approach
- Kept simulation authoritative on the server game loop.
- Added an AABB (axis-aligned bounding box) collision resolution phase in `GameLoop`.
- Represented each player as a square collider using `PlayerState.RADIUS`.
- On overlap, pushed both players apart along the axis of least penetration.

### Old Code (Before Enhancement)
```java
private void tick() {
    tickCount++;

    // Phase 1 — Process all queued inputs
    processInputs();

    // Phase 2 — Update world (positions already applied in processInputs)
    // Additional game logic (collision, physics) would go here.

    // Phase 3 — Build snapshot and broadcast
    broadcastWorldState();
}
```

### New Code (After Enhancement)
```java
private void tick() {
    tickCount++;

    // Phase 1 — Process all queued inputs
    processInputs();

    // Phase 2 — Update world (positions already applied in processInputs)
    // Additional game logic (collision, physics) would go here.
    resolveCollisions();

    // Phase 3 — Build snapshot and broadcast
    broadcastWorldState();
}

private void resolveCollisions() {
    if (playerStates.size() < 2) return;

    List<PlayerState> states = List.copyOf(playerStates.values());
    float halfSize = PlayerState.RADIUS;
    float combinedHalfSize = halfSize * 2.0f;

    for (int i = 0; i < states.size(); i++) {
        for (int j = i + 1; j < states.size(); j++) {
            PlayerState p1 = states.get(i);
            PlayerState p2 = states.get(j);

            float dx = p1.getX() - p2.getX();
            float dy = p1.getY() - p2.getY();

            float overlapX = combinedHalfSize - Math.abs(dx);
            float overlapY = combinedHalfSize - Math.abs(dy);

            if (overlapX > 0 && overlapY > 0) {
                if (overlapX < overlapY) {
                    float pushX = (dx > 0) ? overlapX : -overlapX;
                    p1.setPosition(p1.getX() + pushX * 0.5f, p1.getY());
                    p2.setPosition(p2.getX() - pushX * 0.5f, p2.getY());
                } else {
                    float pushY = (dy > 0) ? overlapY : -overlapY;
                    p1.setPosition(p1.getX(), p1.getY() + pushY * 0.5f);
                    p2.setPosition(p2.getX(), p2.getY() - pushY * 0.5f);
                }
            }
        }
    }
}
```

### Notes
- Collision handling runs every tick inside the room simulation thread.
- This keeps behavior deterministic and server-authoritative.

---

## 2) Sprint Enhancement (Shift + Movement)

### Objective
Increase movement speed while Shift is held with movement keys, and immediately return to normal speed on Shift release.

### Approach
- Extended input protocol with a `sprinting` boolean in `InputPacket`.
- Frontend sends `sprinting=true` when left/right Shift is held.
- Server applies multiplier in authoritative `PlayerState.applyInput(...)`.
- `GameLoop` forwards `input.getSprinting()` to movement logic.

### Old Code (Before Enhancement)
```java
// PlayerState.java
private static final float SPEED = 3.0f;

public void applyInput(float dx, float dy) {
    dx = clamp(dx, -1.0f, 1.0f);
    dy = clamp(dy, -1.0f, 1.0f);

    this.x = clamp(this.x + dx * SPEED, MIN_BOUND + RADIUS, MAX_BOUND - RADIUS);
    this.y = clamp(this.y + dy * SPEED, MIN_BOUND + RADIUS, MAX_BOUND - RADIUS);
}
```

```java
// GameLoop.java
ps.applyInput(input.getDx(), input.getDy());
```

```proto
// game_messages.proto
message InputPacket {
    string player_id = 1;
    float  dx        = 2;
    float  dy        = 3;
    int64  sequence  = 4;
}
```

### New Code (After Enhancement)
```java
// PlayerState.java
private static final float SPEED = 3.0f;
private static final float SPRINT_MULTIPLIER = 1.8f;

public void applyInput(float dx, float dy, boolean sprinting) {
    dx = clamp(dx, -1.0f, 1.0f);
    dy = clamp(dy, -1.0f, 1.0f);

    float speedMultiplier = sprinting ? SPRINT_MULTIPLIER : 1.0f;
    float appliedSpeed = SPEED * speedMultiplier;

    this.x = clamp(this.x + dx * appliedSpeed, MIN_BOUND + RADIUS, MAX_BOUND - RADIUS);
    this.y = clamp(this.y + dy * appliedSpeed, MIN_BOUND + RADIUS, MAX_BOUND - RADIUS);
}
```

```java
// GameLoop.java
ps.applyInput(
        input.getDx(),
        input.getDy(),
        input.getSprinting());
```

```proto
// game_messages.proto
message InputPacket {
    string player_id = 1;
    float  dx        = 2;
    float  dy        = 3;
    int64  sequence  = 4;
    bool   sprinting = 5;   // true while Shift is pressed
}
```

```javascript
// demo/frontend/app.js (relevant part)
const sprinting =
  state.keys.has(KEY.SHIFT_LEFT) || state.keys.has(KEY.SHIFT_RIGHT);

sendPacket("PLAYER_INPUT", state.schema.InputPacket, {
  playerId: state.myHint,
  dx,
  dy,
  sprinting,
  sequence: state.inputSeq,
});
```

### Notes
- Sprint is fully authoritative because speed is applied only on the server.
- Frontend key handling was also improved to use `event.code` and avoid stuck-key states.

---

## Validation Snapshot
- Build verified with Maven compile on Java 21.
- Runtime issues encountered earlier were due to stale server process on port 8080; after cleanup, updated server build ran correctly.

---

## Files Touched for These Enhancements
- `src/main/java/com/multiplayer/server/game/GameLoop.java`
- `src/main/java/com/multiplayer/server/game/PlayerState.java`
- `src/main/proto/game_messages.proto`
- `demo/frontend/app.js`
- `demo/frontend/index.html`
