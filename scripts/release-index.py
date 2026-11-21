"""Validate release artifacts and publish the JetBrains repository index."""

import io
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def single(items, kind):
    candidates = list(items)
    if len(candidates) != 1:
        raise SystemExit(f"Expected one {kind}, found {len(candidates)}.")
    return candidates[0]


def plugin_descriptor(distribution):
    descriptors = []
    with ZipFile(distribution) as archive:
        for filename in archive.namelist():
            if not filename.endswith(".jar"):
                continue
            with ZipFile(io.BytesIO(archive.read(filename))) as jar:
                if "META-INF/plugin.xml" in jar.namelist():
                    descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
    return single(descriptors, "plugin descriptor")


def main(directory):
    version_tag = os.environ["GITHUB_REF_NAME"]
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", version_tag):
        raise SystemExit("Release tag must have the format vMAJOR.MINOR.PATCH.")

    version = version_tag[1:]
    plugin_zip = single(directory.glob("*.zip"), "JetBrains ZIP")
    vsix = single(directory.glob("*.vsix"), "VS Code VSIX")
    descriptor = plugin_descriptor(plugin_zip)
    with ZipFile(vsix) as archive:
        manifest = json.loads(archive.read("extension/package.json"))
    if descriptor.findtext("version") != version or manifest["version"] != version:
        raise SystemExit("Both package versions must match the release tag.")

    compatibility = descriptor.find("idea-version")
    plugin_id = descriptor.findtext("id")
    if not plugin_id or compatibility is None or "since-build" not in compatibility.attrib:
        raise SystemExit("The JetBrains plugin descriptor is missing compatibility metadata.")

    base = f'{os.environ["GITHUB_SERVER_URL"]}/{os.environ["GITHUB_REPOSITORY"]}'
    url = f"{base}/releases/download/{version_tag}/{plugin_zip.name}"
    root = ET.Element("plugins")
    plugin = ET.SubElement(root, "plugin", id=plugin_id, url=url, version=version)
    plugin.append(compatibility)
    ET.indent(root)
    ET.ElementTree(root).write(directory / "updatePlugins.xml", encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
