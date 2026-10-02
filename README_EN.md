# JUJUTSU NEON — English version

A **Minecraft Forge 1.20.1** combat mod focused on cursed-technique style abilities, movement and destructive combat mechanics.

## Current features

- Gojo blindfold as the ability activator.
- Cursed Energy bar.
- Remappable keys through Minecraft Controls.
- Ground and aerial movement.
- Side dash and front dash.
- Charged jumps.
- Super-speed movement.
- Long-range teleport.
- Blue.
- Maximum Blue.
- Red.
- Maximum Red.
- Hollow Purple.
- Abilities can be used in the air.
- Owner-safe protection for destructive techniques.
- Native 4K VFX for major techniques.
- 48 kHz stereo SFX.
- Batched block destruction to reduce TPS spikes.

## Hollow Purple

- Cast time: about 5 seconds.
- Total range: 160 blocks.
- Full radius for the first 150 blocks.
- Smooth fade during the last 10 blocks.
- Tunnel radius: 6 blocks.
- Damage: 200 hearts.
- Can annihilate Bedrock.
- Critical technical blocks are protected to avoid breaking the world.
- Residual purple VFX remain for about 5 seconds after the projectile passes.

## 4K visuals

The main techniques use native 4096×4096 VFX:

- Blue
- Maximum Blue
- Red
- Maximum Red
- Hollow Purple

Heavy generated PNG/audio files are not stored directly in Git. They are generated with `generate_resources.py`.

## Build

Requirements:

- Java 17
- Forge 1.20.1
- Gradle
- Python 3
- Pillow
- FFmpeg

Local build:

```bash
python3 -m pip install pillow
python3 generate_resources.py
gradle build
```

The final JAR will be created in:

```text
build/libs/
```

## GitHub Actions

On every push to `main`, GitHub automatically:

1. sets up Java 17;
2. installs FFmpeg;
3. generates 4K VFX and audio;
4. builds the Forge mod;
5. uploads the final JAR as an Artifact.

## Controls

Default keybinds are documented in `JujutsuNeonMod.java` and can be changed through the standard Minecraft Controls menu.

## Project

**JUJUTSU NEON**

Repository: `JEFFRIPPER/JUJUTSU-NEON`
