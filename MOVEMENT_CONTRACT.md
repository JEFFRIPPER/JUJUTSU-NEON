# JUJUTSU NEON — Movement Contract

This file is a hard contract. A movement change is invalid if any invariant below is violated.

## Authority model
- Blindfold OFF: vanilla Minecraft movement is untouched.
- Gojo blindfold ON: Jujutsu Neon owns movement physics.
- Exactly one movement state may own motion in a tick.
- Legal client states are: `LOCKED`, `DASH`, `FLIGHT`, `WATER_RUN`, `WATER`, `GROUND`, `AIR`.
- `LOCKED` is forced by Max Blue, Hollow Purple cast, the Maximum Purple cutscene, the Domain Expansion cast cutscene and the domain stun (the whole domain plus 2 minutes after it). During cutscenes the camera is a separate entity, so vanilla sends no movement packets: the client moves the player along the cutscene path and sends `PosRot` to the server every tick itself; the server only checks limits. Flight is ended and dash/jump input is ignored. A stunned player has zero movement input but keeps vanilla gravity and knockback.
- The domain wall is clamped on the client at local PlayerTick END (before the position is sent) and enforced on the server; nobody crosses it in either direction.
- A state transition must release ownership before the next state writes velocity/position.
- No second event handler may independently start the same dash or rewrite the same movement vector.

## Coordinate invariants
- Camera-forward is the look-relative forward basis.
- Camera-right is `(-forward.z, 0, forward.x)`.
- W = +forward.
- S = -forward.
- A = -right.
- D = +right.
- No downstream patch may invert A/D again.

## Ground / air invariants
- Ordinary walking/running must visibly drive vanilla limb locomotion; no rigid sliding doll.
- Walking off a ledge may not cause a horizontal stop-frame.
- Charged jump preserves horizontal momentum.
- Jumps: a short Space press is a normal vanilla jump (0.42, ~1.25 blocks) on ground and in air. Holding Space on ground for 0.75 s (15 ticks) gives a 10-block jump (1.3433). There are no other charge tiers.
- At most 4 jumps in a row, including the ground jump. The counter resets on ground and in water.
- Airborne WASD control preserves vertical velocity.
- Super Run (Ctrl) speed is 1.20 blocks/tick; walking is 0.30. View bobbing is suppressed while Super Run is held.
- GROUND movement sets velocity at ClientTick START and lets vanilla physics move the player. No post-tick forced `move()` for GROUND: it desyncs body yaw (sideways running), causes jitter and micro-freezes.
- Ascending 1–2 block obstacles on GROUND uses vanilla step-up with `setMaxUpStep(2.0)` while GROUND owns motion; the previous step height is restored when it releases ownership.
- A wall of 3+ blocks never freezes movement: the remaining motion slides along the wall (vanilla collision), including in the swept step-up solver used by DASH / WATER_RUN.
- Descending terrain must follow the ground smoothly instead of snapping/rubber-banding.
- Client collision may never enter a block that still exists authoritatively on the server.

## Water invariants
- WATER and FLIGHT are mutually exclusive.
- Entering water may never activate or preserve custom flight.
- Without Ctrl: custom WATER state behaves like normal swimming, including vertical swim controls.
- With Ctrl: WATER_RUN owns surface-running only. The water surface acts as ground: within 0.48 below to 0.65 above the surface the player is held at the surface by velocity at ClientTick START and moved by vanilla physics at Super Run speed (no post-tick forced move).
- The server never repositions the player or sends velocity for water running; it only spawns effects.
- If surface-running cannot acquire the surface, control falls back to WATER; it never freezes the player.

## Dash invariants
- Air Dash does not exist.
- A dash may start only with ground support.
- Q starts a dash only when BOTH hands are empty.
- Hands are checked at ClientTick START, before vanilla Drop Item runs; checking after the drop sees an empty hand and dashes together with the drop.
- Holding Q to drop a stack never turns into a dash when the stack runs out; a dash needs a fresh press with empty hands.
- Any item in either hand + Q remains Minecraft item-drop behavior; no client prediction and no dash packet.
- W+Q / neutral+Q = steerable Front Dash.
- S+Q = steerable Back Dash opposite camera-forward.
- A+Q = left Side Dash.
- D+Q = right Side Dash.
- Front Dash pose/steering/distance are protected reference behavior.
- Back Dash uses its own full-body reverse silhouette.
- Side Dash may bank modestly as one coherent body; it may not fold or dislocate the torso.
- Server hit direction must match the client steering vector.

## Flight invariants
- Flight begins automatically at the apex of ANY jump (normal or charged) if the player is 4+ blocks above ground; a water surface counts as ground for this check. Falling without a jump never starts flight.
- Auto-landing threshold stays at 3 blocks.
- Flight direction follows the camera/look vector: look up = fly up, look down = fly down.
- W/S travel along/opposite the full look vector. A/D strafe on the camera-relative horizontal right axis.
- Space/Shift are not the primary vertical flight axes.
- Ctrl changes flight speed only; it does not hand movement back to Super Run.
- Entering water immediately ends flight and transfers ownership to WATER.

## Tree / destruction invariants
- Client code must NEVER delete a world block as movement prediction.
- Super Run tree/foliage destruction is server-authoritative.
- The client may request a small swept path clear, but must wait for authoritative block updates before entering it.
- Tree detection must be bounded; no per-tick breadth-first traversal of an entire trunk/canopy.
- A tree base one block above the player is included in the swept clear volume.

## Animation lifecycle invariants
- Vanilla locomotion is the base pose.
- Temporary skill/dash pose exists only while its timer/state is active.
- When a timer reaches zero, active animation becomes `NONE` immediately.
- Renderer model swaps must be restored in Post and defensively before the next Pre.
- Purple/Blue/Front Dash reference visuals may not regress due to unrelated movement fixes.

## Red safety invariants
- The caster is excluded from normal Red custom victims.
- The caster cannot be hurt or knocked back by their own Red explosion path.
- Blocks supporting/intersecting the caster are restored/protected from normal Red.
