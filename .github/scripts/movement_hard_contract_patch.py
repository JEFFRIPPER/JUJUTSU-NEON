from pathlib import Path
import re

root = Path('src/main/java/com/kira/jujutsuneon')

# -----------------------------------------------------------------------------
# Main movement controller
# -----------------------------------------------------------------------------
mod = root / 'JujutsuNeonMod.java'
s = mod.read_text(encoding='utf-8')

s = s.replace(
'''    private static final int DASH_SIDE = 2;
    private static final int DASH_AIR = 3;

    private static final long FRONT_DASH_TICKS = 14L;
    private static final long SIDE_DASH_TICKS = 4L;
    private static final long AIR_DASH_TICKS = 3L;
''',
'''    private static final int DASH_SIDE = 2;

    private static final long FRONT_DASH_TICKS = 14L;
    private static final long SIDE_DASH_TICKS = 4L;
''')

s, n = re.subn(
    r'    private static boolean isAirDashHeight\(ServerPlayer player\) \{.*?\n    \}\n\n    private static void startDash',
    '''    private static boolean dashHasGroundSupport(ServerPlayer player) {
        if (player.onGround()) return true;
        Vec3 start = player.position().add(0.0, 0.08, 0.0);
        Vec3 end = start.add(0.0, -0.36, 0.0);
        BlockHitResult hit = player.level().clip(new ClipContext(
                start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        return hit.getType() != HitResult.Type.MISS;
    }

    private static void startDash''',
    s, count=1, flags=re.S)
assert n == 1, 'server Air Dash helper replacement failed'

old_air_server = '''        // В воздухе выше 3 блоков любой Q-рывок становится свободным air dash по камере.
        if (isAirDashHeight(player)) {
            if (!consumeEnergy(player, 0.8)) return;

            Vec3 dir = player.getLookAngle().normalize();
            player.getPersistentData().putInt("jn_dash_mode", DASH_AIR);
            player.getPersistentData().putLong("jn_dash_started", now);
            player.getPersistentData().putDouble("jn_dash_dx", dir.x);
            player.getPersistentData().putDouble("jn_dash_dy", dir.y);
            player.getPersistentData().putDouble("jn_dash_dz", dir.z);

            playSfx(level, player, SFX_DASH, 0.82f, 1.22f);
            spawnVfx(level, VFX_DASH, player.position().add(0, 0.85, 0), 1);
            return;
        }

'''
assert old_air_server in s, 'server Air Dash branch not found'
s = s.replace(old_air_server,
'''        // Air Dash does not exist. Every dash requires ground support.
        if (!dashHasGroundSupport(player)) return;

''', 1)

s, n = re.subn(
    r'\n        if \(mode == DASH_AIR\) \{.*?\n        \}\n(?=    \}\n\n    private static void performChargedJump)',
    '\n', s, count=1, flags=re.S)
assert n == 1, 'server DASH_AIR tick block removal failed'

# Camera-right contract everywhere.
s = s.replace('Vec3 right = new Vec3(forward.z, 0.0, -forward.x);',
              'Vec3 right = new Vec3(-forward.z, 0.0, forward.x);')

# Replace expensive connected-tree traversal with bounded evidence scan.
s, n = re.subn(
    r'    private static boolean isNaturalTreeLog\(ServerLevel level, BlockPos start\) \{.*?\n    \}\n\n    private static boolean isSuperRunPlant',
    '''    private static boolean isNaturalTreeLog(ServerLevel level, BlockPos start) {
        if (!level.getBlockState(start).is(BlockTags.LOGS)) return false;

        BlockState below = level.getBlockState(start.below());
        if (below.is(Blocks.DIRT) || below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.PODZOL) ||
                below.is(Blocks.COARSE_DIRT) || below.is(Blocks.ROOTED_DIRT) ||
                below.is(Blocks.MUD) || below.is(Blocks.MYCELIUM)) {
            return true;
        }

        for (int dy = 0; dy <= 6; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 5) continue;
                    if (level.getBlockState(start.offset(dx, dy, dz)).is(BlockTags.LEAVES)) return true;
                }
            }
        }
        return false;
    }

    private static boolean isSuperRunPlant''',
    s, count=1, flags=re.S)
assert n == 1, 'tree classifier replacement failed'

# Server and client agree on water-surface ownership.
needle = '''        vaporizeSuperRunPlants(player, level);
        spawnSuperRunEffects(player, level, now);'''
assert needle in s, 'super run tail not found'
s = s.replace(needle,
'''        vaporizeSuperRunPlants(player, level);
        supportSuperRunOnWater(player, level);
        spawnSuperRunEffects(player, level, now);''', 1)

# Client no longer contains any Air Dash height logic.
s, n = re.subn(
    r'        private static boolean clientIsAirDashHeight\(Minecraft mc\) \{.*?\n        \}\n\n        private static Vec3 clientHorizontalDirection',
    '        private static Vec3 clientHorizontalDirection',
    s, count=1, flags=re.S)
assert n == 1, 'client air height helper removal failed'

s, n = re.subn(
    r'        private static void startClientDash\(Minecraft mc, MovementAction requestedAction\) \{.*?\n        \}\n\n        private static void tickClientDash',
    '''        private static void startClientDash(Minecraft mc, MovementAction requestedAction) {
            if (!hudBlindfold || mc.player == null || mc.level == null) return;
            if (hudMaxBlueActive || hudPurpleCasting || clientDashMode != DASH_NONE) return;
            if (!clientHasGroundSupport(mc, 0.36)) return;

            Vec3 look = mc.player.getLookAngle();
            Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
            if (horizontal.lengthSqr() < 1.0E-4) {
                double yaw = Math.toRadians(mc.player.getYRot());
                horizontal = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
            }
            horizontal = horizontal.normalize();
            Vec3 right = new Vec3(-horizontal.z, 0.0, horizontal.x);

            if (requestedAction == MovementAction.LEFT_DASH || requestedAction == MovementAction.RIGHT_DASH) {
                clientDashDirection = requestedAction == MovementAction.LEFT_DASH
                        ? right.scale(-1.0) : right;
                clientDashMode = DASH_SIDE;
            } else {
                clientDashDirection = requestedAction == MovementAction.BACK_DASH
                        ? horizontal.scale(-1.0) : horizontal;
                clientDashMode = DASH_FRONT;
            }

            clientDashAge = 0;
            mc.player.setDeltaMovement(Vec3.ZERO);
        }

        private static void tickClientDash''',
    s, count=1, flags=re.S)
assert n == 1, 'client dash starter replacement failed'

s, n = re.subn(
    r'        private static void tickClientDash\(Minecraft mc\) \{.*?\n        \}\n\n        private static boolean clientWaterSurfaceRun',
    '''        private static void tickClientDash(Minecraft mc) {
            if (clientDashMode == DASH_NONE || mc.player == null) return;

            if (!hudBlindfold || hudMaxBlueActive || hudPurpleCasting) {
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                return;
            }

            Vec3 dir = clientDashDirection;
            if (dir.lengthSqr() < 1.0E-6) {
                clientDashMode = DASH_NONE;
                return;
            }

            double speed;
            int lifetime;
            if (clientDashMode == DASH_FRONT) {
                lifetime = (int) FRONT_DASH_TICKS;
                double t = Mth.clamp(clientDashAge / (double) FRONT_DASH_TICKS, 0.0, 1.0);
                speed = 1.68 - 1.30 * t;
            } else if (clientDashMode == DASH_SIDE) {
                lifetime = (int) SIDE_DASH_TICKS;
                speed = 1.28;
            } else {
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                return;
            }

            if (clientDashAge >= lifetime ||
                    !tryClientForcedMove(mc, dir.normalize().scale(speed), true)) {
                clientDashMode = DASH_NONE;
                clientDashAge = 0;
                clientDashDirection = Vec3.ZERO;
                mc.player.setDeltaMovement(Vec3.ZERO);
                return;
            }

            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.fallDistance = 0.0F;
            clientDashAge++;
        }

        private static boolean clientWaterSurfaceRun''',
    s, count=1, flags=re.S)
assert n == 1, 'client dash tick replacement failed'

# Replace the loose ground-only path with explicit state ownership.
s, n = re.subn(
    r'        private static void tickCustomGroundMovement\(Minecraft mc\) \{.*?\n        \}\n\n        private static void performClientChargedJump',
    '''        private enum ClientMovementState {
            LOCKED,
            DASH,
            FLIGHT,
            WATER_RUN,
            WATER,
            GROUND,
            AIR
        }

        private static ClientMovementState resolveMovementState(Minecraft mc, boolean superRun) {
            if (hudMaxBlueActive || hudPurpleCasting) return ClientMovementState.LOCKED;
            if (clientDashMode != DASH_NONE) return ClientMovementState.DASH;
            if (JujutsuNeonFlightClient.isCustomFlightActive()) return ClientMovementState.FLIGHT;
            if (mc.player != null && mc.player.isInWaterOrBubble()) {
                return superRun ? ClientMovementState.WATER_RUN : ClientMovementState.WATER;
            }
            if (clientHasGroundSupport(mc, 0.34)) return ClientMovementState.GROUND;
            return ClientMovementState.AIR;
        }

        private static void smoothGroundDescent(Minecraft mc) {
            if (mc.player == null || mc.level == null || mc.player.onGround()) return;

            Vec3 start = mc.player.position().add(0.0, 0.06, 0.0);
            Vec3 end = start.add(0.0, -1.35, 0.0);
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player
            ));
            if (hit.getType() == HitResult.Type.MISS) return;

            double drop = mc.player.getY() - hit.getLocation().y;
            if (drop <= 0.02 || drop > 1.25) return;

            double step = Math.min(drop, 0.34);
            Vec3 down = new Vec3(0.0, -step, 0.0);
            if (mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(down))) {
                mc.player.move(MoverType.SELF, down);
                mc.player.fallDistance = 0.0F;
            }
        }

        private static void tickCustomWaterMovement(Minecraft mc, Vec3 dir) {
            if (mc.player == null) return;

            boolean moving = dir.lengthSqr() >= 1.0E-6;
            Vec3 old = mc.player.getDeltaMovement();
            double speed = 0.12;
            double x = moving ? dir.x * speed : old.x * 0.72;
            double z = moving ? dir.z * speed : old.z * 0.72;
            double y = old.y * 0.72;

            if (mc.options.keyJump.isDown() && !mc.options.keyShift.isDown()) {
                y = Math.min(0.18, y + 0.075);
            } else if (mc.options.keyShift.isDown() && !mc.options.keyJump.isDown()) {
                y = Math.max(-0.18, y - 0.075);
            } else if (mc.player.isUnderWater()) {
                y += 0.012;
            }

            mc.player.setDeltaMovement(x, y, z);
            mc.player.setSwimming(mc.player.isUnderWater() && moving);
            mc.player.fallDistance = 0.0F;
        }

        private static void tickCustomMovement(Minecraft mc) {
            if (!hudBlindfold || mc.player == null || mc.level == null) return;

            Vec3 dir = clientHorizontalDirection(mc);
            boolean moving = dir.lengthSqr() >= 1.0E-6;
            boolean superRun = ClientModEvents.SUPER_SPEED_KEY.isDown();
            double speed = superRun ? CUSTOM_RUN_BLOCKS_PER_TICK : CUSTOM_WALK_BLOCKS_PER_TICK;
            ClientMovementState state = resolveMovementState(mc, superRun);

            switch (state) {
                case LOCKED -> updateCustomLocomotionAnimation(mc, false, false);
                case DASH, FLIGHT -> {
                    // Explicit ownership handoff: another state already owns this tick.
                }
                case WATER_RUN -> {
                    if (!moving || !clientWaterSurfaceRun(mc, dir, speed)) {
                        tickCustomWaterMovement(mc, dir);
                    }
                    updateCustomLocomotionAnimation(mc, moving, true);
                }
                case WATER -> {
                    tickCustomWaterMovement(mc, dir);
                    updateCustomLocomotionAnimation(mc, moving, false);
                }
                case GROUND -> {
                    mc.player.setSwimming(false);
                    if (moving) {
                        tryClientForcedMove(mc, dir.scale(speed), true);
                        smoothGroundDescent(mc);
                    } else {
                        Vec3 v = mc.player.getDeltaMovement();
                        mc.player.setDeltaMovement(0.0, v.y, 0.0);
                    }
                    updateCustomLocomotionAnimation(mc, moving, superRun);
                }
                case AIR -> {
                    mc.player.setSwimming(false);
                    if (moving) {
                        Vec3 velocity = mc.player.getDeltaMovement();
                        mc.player.setDeltaMovement(dir.x * speed, velocity.y, dir.z * speed);
                    }
                    updateCustomLocomotionAnimation(mc, moving, superRun);
                }
            }
        }

        private static void performClientChargedJump''',
    s, count=1, flags=re.S)
assert n == 1, 'state machine replacement failed'

old_input = '''            // Когда creative flight уже активен — вообще не трогаем vanilla flight input.
            if (mc.player.getAbilities().flying) return;
'''
assert old_input in s, 'movement input flight guard not found'
s = s.replace(old_input,
'''            // Ordinary creative flight remains vanilla. Custom blindfold flight is still
            // owned by our controller and receives no vanilla motion impulses.
            if (mc.player.getAbilities().flying && !JujutsuNeonFlightClient.isCustomFlightActive()) return;
''', 1)

old_jump_cond = 'if (hudBlindfold && !mc.player.getAbilities().flying) {\n                boolean pressedNow'
assert old_jump_cond in s, 'jump state condition not found'
s = s.replace(old_jump_cond,
              'if (hudBlindfold && !mc.player.getAbilities().flying && !mc.player.isInWaterOrBubble()) {\n                boolean pressedNow', 1)

s, n = re.subn(
    r'\n            while \(ClientModEvents\.DASH_KEY\.consumeClick\(\)\) \{.*?\n            \}\n\n            boolean speedHeld',
    '\n            // Dash Q is consumed exclusively by JujutsuNeonMovementPatchClient.\n\n            boolean speedHeld',
    s, count=1, flags=re.S)
assert n == 1, 'duplicate Q handler removal failed'

assert '            tickCustomGroundMovement(mc);' in s
s = s.replace('            tickCustomGroundMovement(mc);', '            tickCustomMovement(mc);', 1)

mod.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# Camera-directed flight
# -----------------------------------------------------------------------------
flight = root / 'JujutsuNeonFlightClient.java'
f = flight.read_text(encoding='utf-8')

old_consts = '''    private static final float NORMAL_FLY_SPEED = 0.05F;
    private static final float BOOST_FLY_SPEED = 0.10F;
'''
assert old_consts in f
f = f.replace(old_consts,
'''    private static final float NORMAL_FLY_SPEED = 0.05F;
    private static final float BOOST_FLY_SPEED = 0.10F;
    private static final double NORMAL_VECTOR_SPEED = 0.42;
    private static final double BOOST_VECTOR_SPEED = 0.82;
''', 1)

f, n = re.subn(
    r'    private static boolean movementInputHeld\(Minecraft mc\) \{.*?\n    \}\n',
    '''    private static boolean movementInputHeld(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() ||
                mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    private static Vec3 flightInputDirection(Minecraft mc) {
        if (mc.player == null) return Vec3.ZERO;

        double forwardInput = (mc.options.keyUp.isDown() ? 1.0 : 0.0) -
                (mc.options.keyDown.isDown() ? 1.0 : 0.0);
        double strafeInput = (mc.options.keyRight.isDown() ? 1.0 : 0.0) -
                (mc.options.keyLeft.isDown() ? 1.0 : 0.0);
        if (Math.abs(forwardInput) < 1.0E-5 && Math.abs(strafeInput) < 1.0E-5) return Vec3.ZERO;

        Vec3 look = mc.player.getLookAngle().normalize();
        Vec3 horizontalForward = new Vec3(look.x, 0.0, look.z);
        if (horizontalForward.lengthSqr() < 1.0E-6) {
            double yaw = Math.toRadians(mc.player.getYRot());
            horizontalForward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
        }
        horizontalForward = horizontalForward.normalize();
        Vec3 right = new Vec3(-horizontalForward.z, 0.0, horizontalForward.x);

        Vec3 result = look.scale(forwardInput).add(right.scale(strafeInput));
        return result.lengthSqr() > 1.0E-8 ? result.normalize() : Vec3.ZERO;
    }
''',
    f, count=1, flags=re.S)
assert n == 1, 'flight input helper replacement failed'

f, n = re.subn(
    r'    private static void tickFlight\(Minecraft mc\) \{.*?\n    \}\n\n    @SubscribeEvent\(priority = EventPriority\.LOWEST\)',
    '''    private static void tickFlight(Minecraft mc) {
        if (mc.player == null || mc.level == null || !customFlight) return;

        if (!mc.player.isAlive() || mc.player.isSpectator() || !hasBlindfold(mc)) {
            endFlight(mc, true);
            return;
        }

        if (mc.player.isInWaterOrBubble()) {
            endFlight(mc, true);
            return;
        }

        if (!mc.player.getAbilities().mayfly || !mc.player.getAbilities().flying) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
            mc.player.onUpdateAbilities();
        }

        boolean crash = boost && !landing &&
                (mc.player.verticalCollision || mc.player.onGround()) &&
                previousFlightVelocity.y < -0.10 &&
                previousFlightVelocity.lengthSqr() > 0.035;

        if (crash) {
            localImpactFlash(mc);
            JujutsuNeonFlightPatch.send(JujutsuNeonFlightPatch.Action.IMPACT);
            endFlight(mc, false);
            return;
        }

        Vec3 inputDirection = flightInputDirection(mc);
        boolean moving = inputDirection.lengthSqr() > 1.0E-8;

        if (!landing) {
            boolean wantsBoost = JujutsuNeonMod.ClientModEvents.SUPER_SPEED_KEY.isDown() && moving;
            setBoost(mc, wantsBoost);

            if (!boost && groundDistance(mc, AUTO_LAND_HEIGHT + 0.25) <= AUTO_LAND_HEIGHT &&
                    (!moving || inputDirection.y < -0.08)) {
                landing = true;
                setBoost(mc, false);
            }
        }

        if (landing) {
            double distance = groundDistance(mc, AUTO_LAND_HEIGHT + 0.25);
            if (mc.player.onGround() || distance <= 0.20) {
                mc.player.setDeltaMovement(Vec3.ZERO);
                endFlight(mc, true);
                return;
            }

            Vec3 velocity = mc.player.getDeltaMovement();
            mc.player.getAbilities().setFlyingSpeed(0.02F);
            mc.player.setDeltaMovement(
                    velocity.x * 0.38,
                    -Math.min(LANDING_SPEED, Math.max(0.08, distance * 0.12)),
                    velocity.z * 0.38
            );
            mc.player.fallDistance = 0.0F;
        } else {
            double speed = boost ? BOOST_VECTOR_SPEED : NORMAL_VECTOR_SPEED;
            Vec3 target = moving ? inputDirection.scale(speed) : Vec3.ZERO;
            Vec3 current = mc.player.getDeltaMovement();
            double blend = boost ? 0.46 : 0.34;
            Vec3 next = current.lerp(target, blend);
            if (!moving && next.lengthSqr() < 0.0004) next = Vec3.ZERO;

            mc.player.setDeltaMovement(next);
            mc.player.getAbilities().setFlyingSpeed(boost ? BOOST_FLY_SPEED : NORMAL_FLY_SPEED);
            mc.player.setSprinting(false);
            mc.player.fallDistance = 0.0F;
            spawnSprintFlightVfx(mc);
        }

        previousFlightVelocity = mc.player.getDeltaMovement();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)''',
    f, count=1, flags=re.S)
assert n == 1, 'flight tick replacement failed'
flight.write_text(f, encoding='utf-8')

# Server flight also yields immediately to water.
flight_server = root / 'JujutsuNeonFlightPatch.java'
fs = flight_server.read_text(encoding='utf-8')
old_guard = '''        if (!player.isAlive() || player.isSpectator() || !hasBlindfold(player)) {
            stopFlight(player);
            return;
        }
'''
assert old_guard in fs, 'flight server guard not found'
fs = fs.replace(old_guard,
'''        if (!player.isAlive() || player.isSpectator() || !hasBlindfold(player) || player.isInWaterOrBubble()) {
            stopFlight(player);
            return;
        }
''', 1)
flight_server.write_text(fs, encoding='utf-8')

# -----------------------------------------------------------------------------
# Side dash visual redesign
# -----------------------------------------------------------------------------
anim = root / 'JujutsuNeonSkillAnimationClient.java'
a = anim.read_text(encoding='utf-8')
a, n = re.subn(
    r'        private void applySideDash\(float progress, float side\) \{.*?\n        \}\n\n        private void syncWearLayers',
    '''        private void applySideDash(float progress, float side) {
            float enter = smooth(clamp01(progress / 0.18f));
            float exit = 1.0f - smooth(clamp01((progress - 0.66f) / 0.34f));
            float w = Math.min(enter, exit);
            if (w <= 0.001f) return;

            rotate(body,
                    lerp(body.xRot, rad(10.0f), w),
                    lerp(body.yRot, rad(-6.0f * side), w),
                    lerp(body.zRot, rad(-11.0f * side), w));

            head.xRot = lerp(head.xRot, head.xRot - rad(4.0f), w);
            head.yRot = lerp(head.yRot, head.yRot + rad(5.0f * side), w);
            head.zRot = lerp(head.zRot, rad(6.0f * side), w);

            if (side > 0.0f) {
                rotate(rightArm,
                        lerp(rightArm.xRot, rad(30.0f), w),
                        lerp(rightArm.yRot, rad(-10.0f), w),
                        lerp(rightArm.zRot, rad(16.0f), w));
                rotate(leftArm,
                        lerp(leftArm.xRot, rad(-18.0f), w),
                        lerp(leftArm.yRot, rad(-18.0f), w),
                        lerp(leftArm.zRot, rad(-20.0f), w));
            } else {
                rotate(rightArm,
                        lerp(rightArm.xRot, rad(-18.0f), w),
                        lerp(rightArm.yRot, rad(18.0f), w),
                        lerp(rightArm.zRot, rad(20.0f), w));
                rotate(leftArm,
                        lerp(leftArm.xRot, rad(30.0f), w),
                        lerp(leftArm.yRot, rad(10.0f), w),
                        lerp(leftArm.zRot, rad(-16.0f), w));
            }

            rotate(rightLeg,
                    lerp(rightLeg.xRot, rad(side > 0.0f ? 18.0f : -10.0f), w),
                    lerp(rightLeg.yRot, rad(-4.0f * side), w),
                    lerp(rightLeg.zRot, rad(5.0f * side), w));
            rotate(leftLeg,
                    lerp(leftLeg.xRot, rad(side > 0.0f ? -10.0f : 18.0f), w),
                    lerp(leftLeg.yRot, rad(4.0f * side), w),
                    lerp(leftLeg.zRot, rad(-5.0f * side), w));
        }

        private void syncWearLayers''',
    a, count=1, flags=re.S)
assert n == 1, 'side dash pose replacement failed'
anim.write_text(a, encoding='utf-8')

# -----------------------------------------------------------------------------
# Strict source assertions
# -----------------------------------------------------------------------------
all_java = '\n'.join(p.read_text(encoding='utf-8') for p in root.glob('*.java'))
assert 'DashKind.AIR' not in all_java
assert 'DASH_AIR' not in all_java
assert 'AIR_DASH' not in all_java
assert 'preclearSuperRunPath' not in all_java
assert 'enum ClientMovementState' in s
assert 'case WATER_RUN' in s and 'case WATER' in s and 'case AIR' in s
assert 'JujutsuNeonFlightClient.isCustomFlightActive()' in s
assert 'look.scale(forwardInput)' in f
assert 'player.isInWaterOrBubble()' in f
assert 'sendSuperRunClear' in (root / 'JujutsuNeonMovementPatch.java').read_text(encoding='utf-8')

print('STRICT MOVEMENT CONTRACT SOURCE CHECKS: PASS')
for p in [mod, flight, flight_server, anim]:
    print(p, sum(1 for _ in p.open(encoding='utf-8')))
