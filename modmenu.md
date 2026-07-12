# Mod Menu Documentation

## Overview

Mod Menu displays installed mods on the mods screen and provides access to mod configuration screens. Available for Fabric and Quilt.

- **Repository**: https://github.com/TerraformersMC/ModMenu
- **Modrinth**: https://modrinth.com/mod/modmenu
- **Wiki**: https://github.com/TerraformersMC/ModMenu/wiki

## Adding Dependency

**build.gradle**:
```groovy
repositories {
    maven {
        name = "Terraformers"
        url = "https://maven.terraformersmc.com/"
    }
}

dependencies {
    modImplementation("com.terraformersmc:modmenu:${project.modmenu_version}")
}
```

**gradle.properties**:
```properties
modmenu_version=17.0.0
```

## Integration

### 1. Create Entrypoint Class

```java
package com.example.mod;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ExampleModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new MyConfigScreen(parent);
    }
}
```

### 2. Register in fabric.mod.json

```json
{
    "entrypoints": {
        "modmenu": [
            "com.example.mod.ExampleModMenuIntegration"
        ]
    }
}
```

## Java API Methods

| Method | Purpose |
|---|---|
| `getModConfigScreenFactory()` | Provide a config screen for your mod |
| `getProvidedConfigScreenFactories()` | Provide config screens for OTHER mods |
| `attachModpackBadges(Consumer<String>)` | Mark mods as modpack-internal |

## Metadata API (fabric.mod.json)

```json
{
    "custom": {
        "modmenu": {
            "links": {
                "modmenu.discord": "https://discord.gg/example"
            },
            "badges": ["library"],
            "parent": {
                "id": "parent-mod",
                "name": "Parent Mod",
                "description": "Description",
                "icon": "assets/mod/parent_icon.png",
                "badges": ["library"]
            },
            "update_checker": false
        }
    }
}
```

## Mod Badges

| Badge | Description |
|---|---|
| `library` | Dependency-only mod, hidden by default |
| `deprecated` | Legacy mod, marked as deprecated |
| `client` | Auto-assigned for client-only mods |
| `modpack` | Assigned via `attachModpackBadges` |

## Translation API

Use language keys to translate mod metadata:
```
modmenu.nameTranslation.<mod_id> = "Translated Name"
modmenu.summaryTranslation.<mod_id> = "Brief summary"
modmenu.descriptionTranslation.<mod_id> = "Full description"
```

## Version Compatibility

| ModMenu Version | Minecraft |
|---|---|
| 17.0.x | 1.21.11 |
| 16.0.x | 1.21.9/1.21.10 |
| 15.0.x | 1.21.4/1.21.5 |
| 14.0.x | 1.21.1/1.21.2/1.21.3 |
