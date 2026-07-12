# Fabric Loom Documentation

## Overview

Fabric Loom is a Gradle plugin for developing Fabric mods. It handles Minecraft dependency management, mappings, remapping, and run configuration setup.

## Installation

**build.gradle**:
```groovy
plugins {
    id 'net.fabricmc.fabric-loom-remap' version '1.17.14'
}
```

## Plugin IDs

| Plugin ID | Minecraft Version |
|---|---|
| `net.fabricmc.fabric-loom` | 26.1 and newer (non-obfuscated) |
| `net.fabricmc.fabric-loom-remap` | 1.21.11 and older (obfuscated) |

## Key Tasks

| Task | Description |
|---|---|
| `runClient` | Launch Minecraft client |
| `runServer` | Launch Minecraft server |
| `build` | Build and remap mod JAR |
| `remapJar` | Remap compiled output to intermediary names |
| `genSources` | Decompile Minecraft with CFR |
| `migrateMappings` | Migrate source to different mappings |

## Dependency Configurations

| Configuration | Description |
|---|---|
| `minecraft` | Minecraft version |
| `mappings` | Mappings to use (e.g., `loom.officialMojangMappings()`) |
| `modImplementation` | Mod dependency (remapped) |
| `modApi` | Mod API dependency (remapped) |
| `modRuntimeOnly` | Mod runtime-only dependency |
| `include` | Jar-in-jar dependency |
| `clientImplementation` | Client-only source set dependency |

## Split Source Sets

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

## Cache Locations

- **User cache**: `${GRADLE_HOME}/caches/fabric-loom`
- **Project cache**: `.gradle/loom-cache`
- **Build cache**: `**/build/loom-cache`

## Development Environment

Loom handles:
- Downloading client/server jars from official channels
- Merging client/server jars with `@Environment` annotations
- Downloading and applying mappings
- Decompiling mapped sources (optional)
- Downloading Minecraft assets
- Configuring run configurations

## Troubleshooting

Run `./gradlew build --refresh-dependencies` to clear and recreate all cached files.

## Resources

- **Source Code**: https://github.com/FabricMC/fabric-loom
- **Documentation**: https://docs.fabricmc.net/develop/loom/
