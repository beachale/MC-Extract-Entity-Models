#!/usr/bin/env python3
"""
Batch render Minecraft entity OBJ+MTL exports in Blender.

Run with Blender (background mode):
  blender -b -P render_entity_models_blender.py -- \
    --input-dir ./exports/entity-models \
    --output-dir ./renders/entity-models-isometric
"""

from __future__ import annotations

import argparse
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

def _find_blender_executable() -> str | None:
    env_path = os.environ.get("BLENDER_PATH")
    if env_path:
        env_candidate = Path(env_path).expanduser()
        if env_candidate.is_file():
            return str(env_candidate)

    path_hit = shutil.which("blender")
    if path_hit:
        return path_hit

    candidates: list[Path] = []
    program_files = [os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)")]
    for base in program_files:
        if not base:
            continue
        root = Path(base) / "Blender Foundation"
        if not root.exists():
            continue
        candidates.extend(root.glob("Blender*\\blender.exe"))

    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.extend((Path(local_app_data) / "Programs" / "Blender Foundation").glob("Blender*\\blender.exe"))

    if not candidates:
        return None

    candidates.sort(reverse=True)
    return str(candidates[0])


def _script_passthrough_args() -> list[str]:
    args = list(sys.argv[1:])
    while args and args[0] == "--":
        args.pop(0)
    return args


def _launch_in_blender_or_exit() -> None:
    blender_exe = _find_blender_executable()
    script_path = Path(__file__).resolve()
    script_name = script_path.name

    if blender_exe is None:
        print(
            "ERROR: Could not import 'bpy', and Blender was not found automatically.\n"
            "Install Blender from https://www.blender.org/download/ and re-run, or set BLENDER_PATH.\n"
            "Then run:\n"
            f"  blender -b -P {script_name} -- --input-dir ./exports/entity-models "
            "--output-dir ./renders/entity-models-isometric",
            file=sys.stderr,
            flush=True,
        )
        raise SystemExit(1)

    cmd = [blender_exe, "-b", "-P", str(script_path), "--", *_script_passthrough_args()]
    print(f"INFO: bpy not available in this Python; relaunching via Blender: {blender_exe}", flush=True)
    result = subprocess.run(cmd)
    raise SystemExit(result.returncode)


try:
    import bpy
    from mathutils import Vector
except ModuleNotFoundError as exc:
    if exc.name == "bpy":
        _launch_in_blender_or_exit()
    raise


def parse_args() -> argparse.Namespace:
    script_dir = Path(__file__).resolve().parent
    default_input = script_dir / "exports" / "entity-models"
    default_output = script_dir / "renders" / "entity-models-isometric"

    parser = argparse.ArgumentParser(description="Render all entity OBJ files as isometric PNG previews.")
    parser.add_argument("--input-dir", default=str(default_input), help="Directory containing exported .obj files.")
    parser.add_argument("--output-dir", default=str(default_output), help="Directory where PNG renders will be written.")
    parser.add_argument("--resolution", type=int, default=768, help="Output width/height in pixels.")
    parser.add_argument("--margin", type=float, default=1.12, help="Orthographic framing margin (1.0 = tight).")
    parser.add_argument("--limit", type=int, default=0, help="Render only first N models (0 = all).")
    parser.add_argument("--name-filter", default="", help="Case-insensitive substring filter for OBJ file stem.")
    parser.add_argument("--skip-existing", action="store_true", help="Skip renders that already exist.")
    parser.add_argument("--transparent-bg", action="store_true", help="Render with transparent background.")
    parser.add_argument("--world-strength", type=float, default=0.35, help="World ambient strength.")
    parser.add_argument("--key-light", type=float, default=2.1, help="Main sun light strength.")
    parser.add_argument("--fill-light", type=float, default=0.7, help="Secondary fill sun strength.")
    parser.add_argument("--iso-azimuth", type=float, default=135.0, help="Isometric camera azimuth in degrees.")
    parser.add_argument("--iso-elevation", type=float, default=35.26438968, help="Isometric camera elevation in degrees.")

    if "--" in sys.argv:
        return parser.parse_args(sys.argv[sys.argv.index("--") + 1 :])
    return parser.parse_args([])


def choose_render_engine(scene: bpy.types.Scene) -> None:
    engine_items = bpy.types.RenderSettings.bl_rna.properties["engine"].enum_items
    engine_names = {item.identifier for item in engine_items}

    if "BLENDER_EEVEE_NEXT" in engine_names:
        scene.render.engine = "BLENDER_EEVEE_NEXT"
    elif "BLENDER_EEVEE" in engine_names:
        scene.render.engine = "BLENDER_EEVEE"
    elif "CYCLES" in engine_names:
        scene.render.engine = "CYCLES"
    else:
        scene.render.engine = next(iter(engine_names))


def configure_render(scene: bpy.types.Scene, args: argparse.Namespace) -> None:
    choose_render_engine(scene)
    scene.render.resolution_x = args.resolution
    scene.render.resolution_y = args.resolution
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = args.transparent_bg

    if hasattr(scene, "view_settings"):
        scene.view_settings.view_transform = "Standard"

    if hasattr(scene, "eevee"):
        eevee = scene.eevee
        if hasattr(eevee, "taa_render_samples"):
            eevee.taa_render_samples = 16
        if hasattr(eevee, "use_bloom"):
            eevee.use_bloom = False
        if hasattr(eevee, "use_gtao"):
            eevee.use_gtao = False

    if scene.world is None:
        scene.world = bpy.data.worlds.new("RenderWorld")
    scene.world.use_nodes = True
    nodes = scene.world.node_tree.nodes
    links = scene.world.node_tree.links
    nodes.clear()

    world_out = nodes.new(type="ShaderNodeOutputWorld")
    bg = nodes.new(type="ShaderNodeBackground")
    bg.inputs["Color"].default_value = (0.74, 0.79, 0.87, 1.0)
    bg.inputs["Strength"].default_value = args.world_strength
    links.new(bg.outputs["Background"], world_out.inputs["Surface"])


def remove_all_scene_objects() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)


def add_sun(scene: bpy.types.Scene, name: str, rotation_deg: tuple[float, float, float], energy: float, casts_shadow: bool) -> bpy.types.Object:
    light_data = bpy.data.lights.new(name=name, type="SUN")
    light_data.energy = energy
    if hasattr(light_data, "angle"):
        light_data.angle = math.radians(1.0)
    if hasattr(light_data, "use_shadow"):
        light_data.use_shadow = casts_shadow

    light_obj = bpy.data.objects.new(name=name, object_data=light_data)
    scene.collection.objects.link(light_obj)
    light_obj.rotation_euler = tuple(math.radians(v) for v in rotation_deg)
    return light_obj


def setup_camera_and_lights(scene: bpy.types.Scene, args: argparse.Namespace) -> bpy.types.Object:
    camera_data = bpy.data.cameras.new("RenderCamera")
    camera_data.type = "ORTHO"
    camera_data.clip_start = 0.01
    camera_data.clip_end = 10000.0

    camera_obj = bpy.data.objects.new("RenderCamera", camera_data)
    scene.collection.objects.link(camera_obj)
    scene.camera = camera_obj

    add_sun(scene, "KeySun", rotation_deg=(58.0, 0.0, 42.0), energy=args.key_light, casts_shadow=True)
    add_sun(scene, "FillSun", rotation_deg=(35.0, 0.0, -140.0), energy=args.fill_light, casts_shadow=False)
    return camera_obj


def import_obj(filepath: Path) -> list[bpy.types.Object]:
    before = set(bpy.data.objects.keys())

    if hasattr(bpy.ops.wm, "obj_import"):
        bpy.ops.wm.obj_import(filepath=str(filepath))
    elif hasattr(bpy.ops.import_scene, "obj"):
        bpy.ops.import_scene.obj(filepath=str(filepath))
    else:
        raise RuntimeError("No OBJ importer found in this Blender build.")

    imported = [obj for name, obj in bpy.data.objects.items() if name not in before]
    return imported


def set_socket_value(node: bpy.types.Node, names: tuple[str, ...], value: float) -> None:
    for name in names:
        socket = node.inputs.get(name)
        if socket is not None:
            socket.default_value = value
            return


def link_socket(node_tree: bpy.types.NodeTree, out_socket: bpy.types.NodeSocket, in_socket: bpy.types.NodeSocket) -> None:
    for link in node_tree.links:
        if link.from_socket == out_socket and link.to_socket == in_socket:
            return
    node_tree.links.new(out_socket, in_socket)


def stylize_material(material: bpy.types.Material) -> None:
    material.use_nodes = True
    if hasattr(material, "use_backface_culling"):
        material.use_backface_culling = False

    node_tree = material.node_tree
    if node_tree is None:
        return

    principled = None
    image_nodes: list[bpy.types.Node] = []
    for node in node_tree.nodes:
        if node.type == "TEX_IMAGE":
            image_nodes.append(node)
            if hasattr(node, "interpolation"):
                node.interpolation = "Closest"
            if getattr(node, "image", None) is not None and hasattr(node.image, "alpha_mode"):
                node.image.alpha_mode = "STRAIGHT"
        if node.type == "BSDF_PRINCIPLED" and principled is None:
            principled = node

    if principled is not None:
        set_socket_value(principled, ("Specular", "Specular IOR Level"), 0.0)
        set_socket_value(principled, ("Roughness",), 1.0)
        set_socket_value(principled, ("Metallic",), 0.0)

    if principled is not None and image_nodes:
        alpha_socket = principled.inputs.get("Alpha")
        if alpha_socket is not None:
            alpha_connected = False
            for tex in image_nodes:
                tex_alpha = tex.outputs.get("Alpha")
                if tex_alpha is None:
                    continue
                link_socket(node_tree, tex_alpha, alpha_socket)
                alpha_connected = True
                break

            if alpha_connected:
                if hasattr(material, "blend_method"):
                    material.blend_method = "CLIP"
                if hasattr(material, "alpha_threshold"):
                    material.alpha_threshold = 0.5
                if hasattr(material, "surface_render_method"):
                    material.surface_render_method = "DITHERED"


def stylize_mesh_objects(objects: list[bpy.types.Object]) -> None:
    touched_materials: set[str] = set()
    for obj in objects:
        if obj.type != "MESH" or obj.data is None:
            continue

        for polygon in obj.data.polygons:
            polygon.use_smooth = False

        for slot in obj.material_slots:
            material = slot.material
            if material is None:
                continue
            if material.name in touched_materials:
                continue
            touched_materials.add(material.name)
            stylize_material(material)


def world_bbox_corners(objects: list[bpy.types.Object]) -> list[Vector]:
    corners: list[Vector] = []
    for obj in objects:
        if obj.type != "MESH":
            continue
        for corner in obj.bound_box:
            corners.append(obj.matrix_world @ Vector(corner))
    return corners


def frame_isometric(
    camera_obj: bpy.types.Object,
    corners: list[Vector],
    resolution_x: int,
    resolution_y: int,
    margin: float,
    azimuth_deg: float,
    elevation_deg: float,
) -> None:
    min_corner = Vector((corners[0].x, corners[0].y, corners[0].z))
    max_corner = Vector((corners[0].x, corners[0].y, corners[0].z))

    for p in corners[1:]:
        min_corner.x = min(min_corner.x, p.x)
        min_corner.y = min(min_corner.y, p.y)
        min_corner.z = min(min_corner.z, p.z)
        max_corner.x = max(max_corner.x, p.x)
        max_corner.y = max(max_corner.y, p.y)
        max_corner.z = max(max_corner.z, p.z)

    center = (min_corner + max_corner) * 0.5
    diagonal = (max_corner - min_corner).length

    az = math.radians(azimuth_deg)
    el = math.radians(elevation_deg)
    view_dir = Vector(
        (
            math.cos(el) * math.cos(az),
            math.cos(el) * math.sin(az),
            math.sin(el),
        )
    ).normalized()
    distance = max(diagonal * 2.2, 2.0)
    camera_obj.location = center + view_dir * distance
    camera_obj.rotation_euler = (center - camera_obj.location).to_track_quat("-Z", "Y").to_euler()

    bpy.context.view_layer.update()

    inv_cam = camera_obj.matrix_world.inverted()
    cam_space = [inv_cam @ p for p in corners]
    min_x = min(p.x for p in cam_space)
    max_x = max(p.x for p in cam_space)
    min_y = min(p.y for p in cam_space)
    max_y = max(p.y for p in cam_space)

    width = max_x - min_x
    height = max_y - min_y
    aspect = float(resolution_x) / float(resolution_y)
    ortho_scale = max(width, height * aspect) * margin

    camera_data = camera_obj.data
    camera_data.type = "ORTHO"
    camera_data.ortho_scale = max(0.1, ortho_scale)
    camera_data.clip_start = 0.01
    camera_data.clip_end = max(1000.0, distance + diagonal * 8.0)


def delete_objects(objects: list[bpy.types.Object]) -> None:
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        if obj.name in bpy.data.objects:
            obj.select_set(True)
    bpy.ops.object.delete(use_global=False)


def main() -> int:
    args = parse_args()
    input_dir = Path(args.input_dir).expanduser().resolve()
    output_dir = Path(args.output_dir).expanduser().resolve()

    if not input_dir.exists():
        print(f"ERROR: Input dir not found: {input_dir}", flush=True)
        return 1

    output_dir.mkdir(parents=True, exist_ok=True)
    obj_files = sorted(input_dir.glob("*.obj"))
    if args.name_filter:
        needle = args.name_filter.lower()
        obj_files = [p for p in obj_files if needle in p.stem.lower()]
    if args.limit > 0:
        obj_files = obj_files[: args.limit]

    if not obj_files:
        print(f"ERROR: No OBJ files found in {input_dir}", flush=True)
        return 1

    scene = bpy.context.scene
    configure_render(scene, args)
    remove_all_scene_objects()
    camera_obj = setup_camera_and_lights(scene, args)

    total = len(obj_files)
    rendered = 0
    skipped = 0
    failed = 0

    for index, obj_path in enumerate(obj_files, start=1):
        output_path = output_dir / f"{obj_path.stem}.png"
        if args.skip_existing and output_path.exists():
            skipped += 1
            print(f"[{index:4d}/{total:4d}] skipped {obj_path.name}", flush=True)
            continue

        imported_objects: list[bpy.types.Object] = []
        try:
            imported_objects = import_obj(obj_path)
            mesh_objects = [obj for obj in imported_objects if obj.type == "MESH"]
            if not mesh_objects:
                raise RuntimeError("No mesh objects were imported.")

            stylize_mesh_objects(mesh_objects)
            corners = world_bbox_corners(mesh_objects)
            if not corners:
                raise RuntimeError("Imported mesh had no geometry.")

            frame_isometric(
                camera_obj,
                corners,
                scene.render.resolution_x,
                scene.render.resolution_y,
                args.margin,
                args.iso_azimuth,
                args.iso_elevation,
            )
            scene.render.filepath = str(output_path)
            bpy.ops.render.render(write_still=True)

            rendered += 1
            print(f"[{index:4d}/{total:4d}] rendered {output_path.name}", flush=True)
        except Exception as exc:
            failed += 1
            print(f"[{index:4d}/{total:4d}] failed {obj_path.name} ({exc})", flush=True)
        finally:
            if imported_objects:
                delete_objects(imported_objects)

    print(
        f"Done. Rendered: {rendered}, Skipped: {skipped}, Failed: {failed}, Output: {output_dir}",
        flush=True,
    )
    return 0 if failed == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
