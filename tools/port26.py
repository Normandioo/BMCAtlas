#!/usr/bin/env python3
"""Turns this source tree (Minecraft 1.21.11) into the Minecraft 26.x one, in place.

The mod is written once, for 1.21.11. Minecraft 26.x renamed a few classes and methods; this script applies those
renames and swaps the version-specific files with the ones in port26/. CI runs it before building the 26.x jar.
Usage: python3 tools/port26.py [minecraft_version fabric_api_version]
"""
import pathlib, re, shutil, sys

root = pathlib.Path(__file__).resolve().parent.parent
src = root / "src/main/java/com/normandioo/bmcatlas"

RENAMES = [
    (r"\bGuiGraphics\b", "GuiGraphicsExtractor"),
    (r"\.drawString\(", ".text("),
    (r"public void render\(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta\)",
     "public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta)"),
    (r"\.render\(g, mouseX, mouseY, delta\)", ".extractRenderState(g, mouseX, mouseY, delta)"),
]

for f in sorted(src.glob("*.java")):
    if f.name == "Compat.java":
        continue
    text = f.read_text(encoding="utf-8")
    for a, b in RENAMES:
        text = re.sub(a, b, text)
    f.write_text(text, encoding="utf-8")

shutil.copy(root / "port26/Compat.java", src / "Compat.java")
shutil.copy(root / "port26/build.gradle", root / "build.gradle")

mc = sys.argv[1] if len(sys.argv) > 1 else "26.1.2"
api = sys.argv[2] if len(sys.argv) > 2 else "0.155.2+26.1.2"
props = (root / "gradle.properties").read_text(encoding="utf-8")
props = re.sub(r"(?m)^minecraft_version=.*$", "minecraft_version=" + mc, props)
props = re.sub(r"(?m)^loader_version=.*$", "loader_version=0.19.3\nloom_version=1.17-SNAPSHOT", props)
props = re.sub(r"(?m)^fabric_version=.*$", "fabric_version=" + api, props)
props = re.sub(r"(?m)^mod_version=(.*)$", r"mod_version=\1+mc26.x", props)
(root / "gradle.properties").write_text(props, encoding="utf-8")

meta = root / "src/main/resources/fabric.mod.json"
text = meta.read_text(encoding="utf-8")
text = text.replace('"minecraft": "~1.21.11"', '"minecraft": ">=26.1"').replace('"java": ">=21"', '"java": ">=25"')
assert '">=26.1"' in text and '">=25"' in text, "fabric.mod.json: dependencies not found"
meta.write_text(text, encoding="utf-8")
print("ported to Minecraft", mc)
