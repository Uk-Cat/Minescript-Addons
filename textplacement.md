# Text Placement in Minecraft GUI (GuiGraphics)

## Overview

`GuiGraphics` is the primary class for rendering GUI elements in Minecraft. It provides methods for shapes, text, textures, and scissor clipping.

**Source**: https://docs.fabricmc.net/develop/rendering/gui-graphics

## Coordinate System

- **X**: Increases left to right
- **Y**: Increases top to bottom
- **Scale**: GUI coordinates are relative to scaled window size (affected by GUI Scale setting)
- Width and height fields of `Screen` provide the scaled dimensions

## Text Rendering

```java
// Font, text, x, y, color (ARGB), shadow
gui.drawString(font, component, x, y, color, dropShadow);
```

### ⚠️ Critical: ARGB Color Format (Since 1.21.6)

In Minecraft 1.21.6+, `drawString` requires **ARGB** format (8 hex digits). Using RGB (6 hex digits) results in fully transparent text.

| Format | Example | Result |
|---|---|---|
| ARGB | `0xFFFFFFFF` | Fully opaque white ✅ |
| RGB | `0xFFFFFF` | Fully transparent (alpha=0) ❌ |

**Helper methods**:
```java
// Convert RGB to opaque ARGB:
int white = 0xFFFFFF | 0xFF000000; // = 0xFFFFFFFF
```

### Common Vanilla Colors

| Color | ARGB | RGB (old) |
|---|---|---|
| White | `0xFFFFFFFF` | `0xFFFFFF` |
| Light Gray | `0xFFE0E0E0` | `0xE0E0E0` |
| Gray | `0xFFA0A0A0` | `0xA0A0A0` |
| Dark Gray | `0xFF808080` | `0x808080` |
| Very Dark Gray | `0xFF404040` | `0x404040` |
| Red | `0xFFFF5555` | `0xFF5555` |
| Green | `0xFF55FF55` | `0x55FF55` |
| Yellow | `0xFFFFFF55` | `0xFFFF55` |

## Shapes

```java
// Filled rectangle (x1, y1, x2, y2, color):
gui.fill(10, 10, 110, 60, 0xFF0000FF);

// Outline rectangle (x, y, width, height, color):
gui.outline(10, 10, 100, 50, 0xFFFF0000);

// Horizontal line (x1, x2, y, color):
gui.horizontalLine(10, 110, 30, 0xFF00FF00);

// Vertical line (x, y1, y2, color):
gui.verticalLine(60, 10, 60, 0xFF00FF00);
```

## Scissor (Clipping)

```java
// Enable scissor (x1, y1, x2, y2):
gui.enableScissor(left, top, right, bottom);

// Disable scissor:
gui.disableScissor();
```

Scissor regions can be nested, but must be disabled the same number of times they're enabled.

## Z-Layering

Rendering order determines visual depth:
1. Background fills (drawn first, appears behind)
2. Text and decorations (drawn middle)
3. Widgets via `super.render()` (drawn last, appears on top)

## Screen Lifecycle

| Method | When Called | Purpose |
|---|---|---|
| `init()` | Screen creation and resize | Create widgets, compute layout |
| `render()` | Every frame | Draw background, text, decorations |
| `tick()` | Every tick (20/s) | Update animations, timers |
| `onClose()` | When closed | Return to parent screen |
| `mouseScrolled()` | On scroll | Handle scrolling |
