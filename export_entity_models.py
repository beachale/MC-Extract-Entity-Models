#!/usr/bin/env python3
"""
Automates exporting all baked Minecraft entity model layers to OBJ/MTL.

This script compiles and runs EntityLayerObjExporter.java.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Iterable, Optional


def eprint(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def oprint(message: str) -> None:
    print(message, flush=True)


def run(cmd: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    if capture:
        return subprocess.run(cmd, check=False, text=True, capture_output=True)
    return subprocess.run(cmd, check=False, text=True)


def parse_java_major(version_text: str) -> Optional[int]:
    match = re.search(r'version "([0-9]+)(?:\.([0-9]+))?', version_text)
    if not match:
        return None
    major = int(match.group(1))
    if major == 1 and match.group(2):
        return int(match.group(2))
    return major


def java_major(java_bin: Path) -> int:
    result = run([str(java_bin), "-version"], capture=True)
    combined = (result.stdout or "") + "\n" + (result.stderr or "")
    major = parse_java_major(combined)
    if major is None:
        raise RuntimeError(f"Unable to parse Java version from: {java_bin}")
    return major


def existing_path(path_like: str | Path) -> Path:
    path = Path(path_like).expanduser().resolve()
    if not path.exists():
        raise FileNotFoundError(str(path))
    return path


def unique_existing(paths: Iterable[Path]) -> list[Path]:
    out: list[Path] = []
    seen: set[str] = set()
    for path in paths:
        try:
            resolved = path.resolve()
        except OSError:
            continue
        key = os.path.normcase(str(resolved))
        if key in seen:
            continue
        if resolved.exists():
            seen.add(key)
            out.append(resolved)
    return out


def find_java_candidates(explicit_bin: Optional[str], binary_name: str) -> list[Path]:
    if explicit_bin:
        return [existing_path(explicit_bin)]

    suffix = ".exe" if os.name == "nt" else ""
    candidates: list[Path] = []

    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / f"{binary_name}{suffix}")

    which_path = shutil.which(binary_name)
    if which_path:
        candidates.append(Path(which_path))

    if os.name == "nt":
        globs = [
            Path("C:/Program Files/Eclipse Adoptium").glob(f"jdk*/bin/{binary_name}.exe"),
            Path("C:/Program Files/Java").glob(f"jdk*/bin/{binary_name}.exe"),
            Path("C:/Program Files/Microsoft").glob(f"jdk*/bin/{binary_name}.exe"),
        ]
        for items in globs:
            candidates.extend(items)

    return unique_existing(candidates)


def choose_java(required_major: int, explicit_java: Optional[str]) -> tuple[Path, int]:
    candidates = find_java_candidates(explicit_java, "java")
    if not candidates:
        raise RuntimeError("No Java executable found. Pass --java-bin.")

    best_path: Optional[Path] = None
    best_major = -1
    for candidate in candidates:
        try:
            major = java_major(candidate)
        except Exception:
            continue
        if major > best_major:
            best_path = candidate
            best_major = major

    if best_path is None:
        raise RuntimeError("No usable Java executable found. Pass --java-bin.")

    if best_major < required_major:
        raise RuntimeError(
            f"This Minecraft version requires Java {required_major}+; selected Java is {best_major} at {best_path}"
        )
    return best_path, best_major


def choose_javac(explicit_javac: Optional[str], selected_java: Path) -> Path:
    if explicit_javac:
        return existing_path(explicit_javac)

    suffix = ".exe" if os.name == "nt" else ""
    sibling = selected_java.with_name(f"javac{suffix}")
    if sibling.exists():
        return sibling.resolve()

    candidates = find_java_candidates(None, "javac")
    if not candidates:
        raise RuntimeError("No javac executable found. Pass --javac-bin.")
    return candidates[0]


def pick_latest(paths: Iterable[Path]) -> Optional[Path]:
    paths_list = [p for p in paths if p.exists()]
    if not paths_list:
        return None
    return max(paths_list, key=lambda p: p.stat().st_mtime)


def detect_inputs(
    project_root: Path, client_jar_arg: Optional[str], version_json_arg: Optional[str]
) -> tuple[Path, Path]:
    client_jar = existing_path(client_jar_arg) if client_jar_arg else None
    version_json = existing_path(version_json_arg) if version_json_arg else None

    jars = sorted(project_root.glob("*.jar"), key=lambda p: p.stat().st_mtime, reverse=True)
    jsons = sorted(project_root.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)
    json_by_stem = {p.stem: p for p in jsons}
    jar_by_stem = {p.stem: p for p in jars}

    if client_jar is None:
        if version_json is not None and version_json.stem in jar_by_stem:
            client_jar = jar_by_stem[version_json.stem]
        else:
            paired_jars = [j for j in jars if j.stem in json_by_stem]
            client_jar = pick_latest(paired_jars) or pick_latest(jars)

    if version_json is None:
        if client_jar is not None and client_jar.stem in json_by_stem:
            version_json = json_by_stem[client_jar.stem]
        else:
            paired_jsons = [j for j in jsons if j.stem in jar_by_stem]
            version_json = pick_latest(paired_jsons) or pick_latest(jsons)

    if client_jar is None:
        raise RuntimeError("No client jar found. Put it in project root or pass --client-jar.")
    if version_json is None:
        raise RuntimeError("No version json found. Put it in project root or pass --version-json.")
    return client_jar.resolve(), version_json.resolve()


def library_allowed_for_current_os(library: dict) -> bool:
    rules = library.get("rules")
    if rules is None:
        return True

    current_os_name = "windows" if os.name == "nt" else "unknown"
    current_arch = platform.machine().lower()
    current_version = platform.version()

    allowed = False
    for rule in rules:
        matches = True
        os_rule = rule.get("os")
        if os_rule is not None:
            name = os_rule.get("name")
            if name is not None and name != current_os_name:
                matches = False

            if matches:
                rule_arch = os_rule.get("arch")
                if rule_arch is not None:
                    rule_arch_str = str(rule_arch).lower()
                    if rule_arch_str == "x86_64":
                        matches = "64" in current_arch
                    elif rule_arch_str == "x86":
                        matches = "64" not in current_arch
                    else:
                        matches = rule_arch_str in current_arch

            if matches:
                version_rule = os_rule.get("version")
                if version_rule is not None:
                    matches = re.search(str(version_rule), current_version) is not None

        if matches:
            action = rule.get("action")
            if action == "allow":
                allowed = True
            elif action == "disallow":
                allowed = False
    return allowed


def resolve_classpath_entries(
    metadata: dict, libraries_dir: Path, client_jar: Path
) -> tuple[list[Path], int]:
    entries: list[Path] = [client_jar]
    missing_count = 0

    for library in metadata.get("libraries", []):
        if not library_allowed_for_current_os(library):
            continue

        artifact_path = (
            library.get("downloads", {})
            .get("artifact", {})
            .get("path")
        )
        if not artifact_path:
            continue

        artifact_path = str(artifact_path)
        if "-natives-" in artifact_path and "-natives-windows" not in artifact_path:
            continue

        jar_path = libraries_dir / Path(*artifact_path.split("/"))
        if jar_path.exists():
            entries.append(jar_path.resolve())
        else:
            missing_count += 1
            eprint(f"WARNING: Missing library jar: {jar_path}")

    return entries, missing_count


def build_parser(default_output_dir: Path, default_libraries_dir: Path) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Compile and run EntityLayerObjExporter.java with auto-detected inputs."
    )
    parser.add_argument("--project-root", default=".", help="Folder to auto-scan for client jar/json.")
    parser.add_argument("--client-jar", help="Path to client .jar. If omitted, auto-detected.")
    parser.add_argument("--version-json", help="Path to version .json. If omitted, auto-detected.")
    parser.add_argument("--libraries-dir", default=str(default_libraries_dir), help="Minecraft libraries root.")
    parser.add_argument("--output-dir", default=str(default_output_dir), help="Export output directory.")
    parser.add_argument("--java-bin", help="Path to java executable.")
    parser.add_argument("--javac-bin", help="Path to javac executable.")

    parser.add_argument("--no-runtime-orientation", action="store_true", help="Disable runtime orientation fix.")
    parser.add_argument("--no-lift-to-grid", action="store_true", help="Disable Y-lift to grid (minY=0).")
    parser.add_argument("--no-flip-v", action="store_true", help="Disable V flip for OBJ UVs.")
    parser.add_argument("--flip-z", action="store_true", help="Mirror Z axis.")
    parser.add_argument("--scale", type=float, default=1.0, help="Global scale multiplier.")

    return parser


def main() -> int:
    script_dir = Path(__file__).resolve().parent
    exporter_source = script_dir / "EntityLayerObjExporter.java"
    build_dir = script_dir / "build" / "entity-exporter"
    default_output = script_dir / "exports" / "entity-models"

    appdata = os.environ.get("APPDATA")
    if appdata:
        default_libraries = Path(appdata) / ".minecraft" / "libraries"
    else:
        default_libraries = Path.home() / ".minecraft" / "libraries"

    parser = build_parser(default_output, default_libraries)
    args = parser.parse_args()

    if not exporter_source.exists():
        eprint(f"ERROR: Exporter source not found: {exporter_source}")
        return 1

    project_root = Path(args.project_root).expanduser().resolve()
    if not project_root.exists():
        eprint(f"ERROR: Project root not found: {project_root}")
        return 1

    try:
        client_jar, version_json = detect_inputs(project_root, args.client_jar, args.version_json)
    except Exception as exc:
        eprint(f"ERROR: {exc}")
        return 1

    try:
        metadata = json.loads(version_json.read_text(encoding="utf-8"))
    except Exception as exc:
        eprint(f"ERROR: Failed to read version json {version_json}: {exc}")
        return 1

    required_java_major = 21
    java_version_obj = metadata.get("javaVersion")
    if isinstance(java_version_obj, dict):
        raw_major = java_version_obj.get("majorVersion")
        if raw_major is not None:
            required_java_major = int(raw_major)

    try:
        java_bin, java_major_value = choose_java(required_java_major, args.java_bin)
        javac_bin = choose_javac(args.javac_bin, java_bin)
    except Exception as exc:
        eprint(f"ERROR: {exc}")
        return 1

    libraries_dir = Path(args.libraries_dir).expanduser().resolve()
    if not libraries_dir.exists():
        eprint(f"ERROR: Libraries directory not found: {libraries_dir}")
        return 1

    classpath_entries, missing_count = resolve_classpath_entries(metadata, libraries_dir, client_jar)
    if len(classpath_entries) <= 1:
        eprint("ERROR: No libraries were resolved from version json. Check --version-json and --libraries-dir.")
        return 1

    output_dir = Path(args.output_dir).expanduser().resolve()
    build_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    oprint(f"Using client jar: {client_jar}")
    oprint(f"Using version json: {version_json}")
    oprint(f"Using java ({java_major_value}): {java_bin}")
    oprint(f"Using javac: {javac_bin}")
    oprint("Compiling exporter...")

    compile_result = run(
        [str(javac_bin), "-encoding", "UTF-8", "-d", str(build_dir), str(exporter_source)]
    )
    if compile_result.returncode != 0:
        eprint(f"ERROR: javac failed with exit code {compile_result.returncode}.")
        return compile_result.returncode

    classpath = os.pathsep.join([str(build_dir)] + [str(p) for p in classpath_entries])
    run_args = [
        str(java_bin),
        "-cp",
        classpath,
        "EntityLayerObjExporter",
        "--client-jar",
        str(client_jar),
        "--out",
        str(output_dir),
        "--runtime-orientation",
        "false" if args.no_runtime_orientation else "true",
        "--lift-to-grid",
        "false" if args.no_lift_to_grid else "true",
        "--flip-v",
        "false" if args.no_flip_v else "true",
        "--flip-z",
        "true" if args.flip_z else "false",
        "--scale",
        f"{args.scale:.8g}",
    ]

    oprint("Running exporter...")
    run_result = run(run_args)
    if run_result.returncode != 0:
        eprint(f"ERROR: Exporter failed with exit code {run_result.returncode}.")
        return run_result.returncode

    if missing_count > 0:
        eprint(
            f"WARNING: {missing_count} library jars were missing. Some models may have failed to export."
        )

    oprint(f"Export complete: {output_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
