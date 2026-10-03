# JUJUTSU NEON — Movement Contract

This file is a hard contract for movement code. A movement change is invalid if it violates any implemented invariant below.

## Coordinate invariants
- Camera-forward is the horizontal look vector.
- Camera-right is **(-forward.z, 0, forward.x)**.
- W = +forward.
- S = -forward.
- A = -right.
- D = +right.
- No downstream patch is allowed to flip A/D a second time.

## Locomotion invariants
- With the Gojo blindfold equipped, Jujutsu Neon owns movement physics.
- Vanilla locomotion animation remains visible; the player must never look like a rigid sliding doll during ordinary walking/running.
- Ground movement and airborne horizontal control must be continuous. Walking off a ledge or jumping must not cause a horizontal stop-frame.
- Custom locomotion must preserve vertical velocity while applying airborne horizontal control.
- Only one movement state may own horizontal motion at a time.

## Dash invariants
- Q may start a dash only when BOTH hands are empty.
- W+Q / neutral+Q = steerable Front Dash.
- S+Q = steerable Back Dash in the direction opposite the camera.
- A+Q = left Side Dash.
- D+Q = right Side Dash.
- Front Dash visual pose is a protected reference and must not be degraded by unrelated movement work.
- Back Dash uses its own full-body animation and the same longitudinal timing family as Front Dash.
- Server hit direction must follow the same steering direction as the client.

## Animation lifecycle invariant
- When an animation timer reaches zero, its active state must become NONE immediately.
- A completed dash/skill may not leave its final body pose stuck on ordinary locomotion.

## Known target invariants for subsequent passes
- Air Dash is to be removed entirely.
- Water without Ctrl must use normal swimming; Ctrl may own surface-running only.
- Flight direction must follow the camera/look vector rather than Space/Shift axes.
- Trees/collision and downhill descent must never cause rubber-band teleporting.
