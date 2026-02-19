NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT

# Minecraft Entity Model OBJ Exporter

Exports every baked vanilla `ModelLayerLocation` from an unobfuscated client jar to `.obj` + `.mtl` with texture references.

## Files
- `EntityLayerObjExporter.java`: Java exporter that bakes model layers and writes OBJ/MTL.
- `export_entity_models.py`: Python runner that compiles and runs the Java exporter.

## Requirements
- Python 3.9+
- Java/JDK 21+ (or whatever your version json requires)
- Minecraft version files for the same build:
  - client jar (`*.jar`)
  - version manifest (`*.json`)
- Minecraft libraries in `%APPDATA%\.minecraft\libraries`

## Quick Start
Place your client jar + version json in the same folder as this repo, then run:

```powershell
python .\export_entity_models.py
```

## Explicit Run
```powershell
python .\export_entity_models.py `
  --client-jar .\26.1-snapshot-9.jar `
  --version-json .\26.1-snapshot-9.json `
  --output-dir .\exports
```

## Useful Flags
- `--no-runtime-orientation`: disables runtime orientation correction.
- `--no-lift-to-grid`: disables Y lift (default keeps lowest vertex at `Y=0`).
- `--no-flip-v`: disables OBJ UV V flip.
- `--flip-z`: mirrors Z axis.
- `--no-split-cubes`: keeps old behavior (merges cubes under each model part).
- `--no-clamp-uv`: keeps original UV values (can cause wrapping artifacts in some tools).
- `--scale <number>`: applies global scale.

## Render Isometric Previews (Blender)
Renders one PNG per exported OBJ using its MTL/textures, with simple Minecraft-style lighting and pixel texture filtering.

```powershell
blender -b -P .\render_entity_models_blender.py -- `
  --input-dir .\exports\entity-models `
  --output-dir .\renders\entity-models-isometric `
  --resolution 768
```

Useful render flags:
- `--skip-existing`: skip PNG files that already exist.
- `--margin <number>`: framing padding (default `1.12`; lower = tighter zoom).
- `--limit <N>`: render only first N models for quick tests.
- `--name-filter <text>`: render only model files whose name contains `<text>`.
- `--transparent-bg`: transparent background.
- `--iso-azimuth <deg>`: camera turn around Z (default `135`).

## Output
- One `.obj` + `.mtl` per model layer.
- Texture PNGs are extracted under `textures/...` and linked from MTL via `map_Kd`.
- Model parts remain separate (`o`/`g` groups) for tools like Blockbench.
