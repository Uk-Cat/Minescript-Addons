# Fabric Documentation

## Overview

Fabric is a lightweight, modular modding toolchain for Minecraft: Java Edition.

## Key Resources

- **Main Site**: https://fabricmc.net/
- **Fabric API**: https://github.com/FabricMC/fabric
- **Fabric Loader**: https://github.com/FabricMC/fabric-loader
- **Fabric Loom**: https://github.com/FabricMC/fabric-loom
- **Documentation**: https://docs.fabricmc.net/
- **Wiki**: https://wiki.fabricmc.net/

## Mappings

Fabric uses **Intermediary** mappings for versions up to 1.21.11, providing consistent names across game versions. Developers can use either:

- **Yarn** — community-contributed mappings
- **Official Mojang Mappings** — official names from Mojang

In `build.gradle`:
```groovy
mappings loom.officialMojangMappings()
```

## fabric.mod.json

Located at `src/main/resources/fabric.mod.json`, this file defines mod metadata:

- `id`: unique mod identifier
- `version`: mod version
- `name`: display name
- `entrypoints`: mod initialization entrypoints (main, client, modmenu, etc.)
- `depends`: dependency requirements
- `environment`: `client` for client-only mods

## Entrypoints

| Entrypoint | When Loaded | Purpose |
|---|---|---|
| `main` | Always | Common code initialization (ModInitializer) |
| `client` | Client only | Client-specific initialization (ClientModInitializer) |
| `modmenu` | Client only | ModMenu API integration |

## GuiGraphics Text Colors

**Important**: Since Minecraft 1.21.6, `drawString` color parameter uses **ARGB** format (8 hex digits), not RGB (6 hex digits). Passing RGB values makes text fully transparent.

Use `ARGB.opaque(color)` or prepend `0xFF` to convert:
```java
// Correct (ARGB with full alpha):
gui.drawString(font, text, x, y, 0xFFFFFFFF, false);

// Wrong (transparent in 1.21.6+):
gui.drawString(font, text, x, y, 0xFFFFFF, false);
```

## Split Environment Source Sets

Required for client-only code:
```groovy
loom {
    splitEnvironmentSourceSets()
    mods {
        "mod-id" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}
```
