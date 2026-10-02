# JUJUTSU NEON

Forge 1.20.1 combat mod with Gojo-inspired techniques and movement.

## Current systems

- Gojo blindfold as the ability activator.
- Cursed Energy HUD and remappable Minecraft keybinds.
- Ground and aerial movement: dashes, charged jumps, high-speed run and long-range teleport.
- Blue and Maximum Blue.
- Red and Maximum Red.
- Hollow Purple with a 5-second cast sequence, 160-block flight, circular annihilation tunnel and residual VFX.
- Native 4096×4096 primary VFX generation for Blue, Maximum Blue, Red, Maximum Red and Hollow Purple.
- Generated 48 kHz stereo SFX.
- Minecraft 1.20.1 + Forge 47.x + Java 17.

## Build

Generated VFX/audio assets are reproducible and are created during the build process.

```bash
python3 -m pip install pillow
python3 generate_resources.py
gradle build
```

GitHub Actions performs the same steps automatically and uploads the compiled JAR as an artifact.

## Controls

Default controls are documented in `JujutsuNeonMod.java` and can be remapped through Minecraft's Controls menu.
