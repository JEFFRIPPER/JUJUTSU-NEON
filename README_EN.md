# JUJUTSU NEON

[🇷🇺 Русская версия](README_RU.md) | [🇬🇧 English version](README_EN.md)

Minecraft Forge 1.20.1 combat mod featuring Jujutsu-inspired techniques and movement.

## Current features

- Gojo blindfold as the ability activator.
- Cursed Energy HUD and remappable keybinds.
- Ground and aerial movement: dashes, charged jumps, super-speed and long-range teleport.
- Blue and Maximum Blue.
- Red and Maximum Red.
- Hollow Purple with a 5-second cast sequence, 160-block flight, circular annihilation tunnel and residual VFX.
- Native 4096×4096 VFX for Blue, Maximum Blue, Red, Maximum Red and Hollow Purple.
- Generated 48 kHz stereo audio.
- Minecraft 1.20.1 + Forge 47.x + Java 17.

## Build

Large VFX and audio assets are generated during the build, so the repository does not need to store huge prebuilt PNG/OGG files.

```bash
python3 -m pip install pillow
python3 generate_resources.py
gradle build
```

GitHub Actions performs the same process automatically and uploads the compiled JAR as an artifact.

## Controls

Default keybinds are documented in `JujutsuNeonMod.java`. Main controls can be remapped through Minecraft's Controls menu.
