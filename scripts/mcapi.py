#!/usr/bin/env python3
"""Look up real class/method/field names in the Minecraft 26.1 and Fabric API jars.

Minecraft 26.1 renamed many APIs (GuiGraphics -> GuiGraphicsExtractor, renderBg -> extractBackground,
DimensionDataStorage -> SavedDataStorage, ResourceLocation -> Identifier, ...). Guessing names from
older tutorials fails; check here first.

    ./scripts/dev.sh api net.minecraft.world.inventory.Slot                 # all members
    ./scripts/dev.sh api net.minecraft.world.inventory.Slot isActive,set    # only these names
    ./scripts/dev.sh api --find SavedData                                   # classes whose path contains text
    ./scripts/dev.sh api --find CreativeTab --fabric                        # search Fabric API jars too

Reads class files directly (no JDK tools needed). Jars searched:
  * .gradle/loom-cache/minecraftMaven/**/minecraft-merged-*.jar   (Minecraft, unobfuscated)
  * ~/.gradle/caches/**/net.fabricmc.fabric-api/**/*.jar           (Fabric API modules, with --fabric)
Run ./scripts/dev.sh build once first so Loom has downloaded everything.
"""
import glob
import os
import struct
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ACC = {0x1: "public", 0x2: "private", 0x4: "protected", 0x8: "static", 0x10: "final", 0x400: "abstract"}


def minecraft_jars():
    return glob.glob(os.path.join(ROOT, ".gradle/loom-cache/minecraftMaven/**/minecraft-merged-*.jar"), recursive=True)


def fabric_jars():
    home = os.path.expanduser("~/.gradle/caches")
    jars = glob.glob(os.path.join(home, "modules-2/files-2.1/net.fabricmc.fabric-api/**/*.jar"), recursive=True)
    return [j for j in jars if not j.endswith("-sources.jar")]


def parse(data):
    o = 10
    n = struct.unpack(">H", data[8:10])[0]
    cp = [None] * n
    i = 1
    while i < n:
        t = data[o]
        if t == 1:
            ln = struct.unpack(">H", data[o + 1:o + 3])[0]
            cp[i] = data[o + 3:o + 3 + ln].decode("utf8", "replace")
            o += 3 + ln
        elif t in (3, 4):
            o += 5
        elif t in (5, 6):
            o += 9
            i += 1
        elif t in (7, 8, 16, 19, 20):
            cp[i] = ("ref", struct.unpack(">H", data[o + 1:o + 3])[0])
            o += 3
        elif t in (9, 10, 11, 12, 17, 18):
            o += 5
        elif t == 15:
            o += 4
        else:
            raise ValueError("unknown constant tag %d" % t)
        i += 1
    acc, this, sup = struct.unpack(">HHH", data[o:o + 6])
    o += 6
    ic = struct.unpack(">H", data[o:o + 2])[0]
    interfaces = [cp[cp[struct.unpack(">H", data[o + 2 + 2 * k:o + 4 + 2 * k])[0]][1]] for k in range(ic)]
    o += 2 + 2 * ic
    out = {"super": cp[cp[sup][1]] if sup else None, "interfaces": interfaces, "fields": [], "methods": []}
    for kind in ("fields", "methods"):
        c = struct.unpack(">H", data[o:o + 2])[0]
        o += 2
        for _ in range(c):
            a, ni, di, ac = struct.unpack(">HHHH", data[o:o + 8])
            o += 8
            for _ in range(ac):
                ln = struct.unpack(">I", data[o + 2:o + 6])[0]
                o += 6 + ln
            out[kind].append((" ".join(v for k, v in ACC.items() if a & k), cp[ni], cp[di]))
    return out


def find_class(path, jars):
    for jar in jars:
        with zipfile.ZipFile(jar) as z:
            try:
                return jar, z.read(path)
            except KeyError:
                continue
    return None, None


def main(argv):
    use_fabric = "--fabric" in argv
    argv = [a for a in argv if a != "--fabric"]
    jars = minecraft_jars() + (fabric_jars() if use_fabric else [])
    if not jars:
        print("No Minecraft jar found. Run ./scripts/dev.sh build first.", file=sys.stderr)
        return 1
    if not argv:
        print(__doc__)
        return 2

    if argv[0] == "--find":
        needle = argv[1].lower()
        seen = set()
        for jar in jars:
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if name.endswith(".class") and needle in name.lower() and "$" not in name and name not in seen:
                        seen.add(name)
                        print(name[:-6].replace("/", "."))
        if not seen:
            print("no match" + ("" if use_fabric else " (add --fabric to search Fabric API jars)"))
        return 0

    cls = argv[0].replace(".", "/")
    # allow Outer$Inner written as Outer.Inner: try progressively turning dots into $
    candidates = [cls]
    parts = argv[0].split(".")
    for k in range(len(parts) - 1, 0, -1):
        if parts[k][:1].isupper() and parts[k - 1][:1].isupper():
            candidates.append("/".join(parts[:k]) + "$" + "$".join(parts[k:]))
    filt = set(argv[1].split(",")) if len(argv) > 1 else None
    for c in candidates:
        jar, data = find_class(c + ".class", jars)
        if data:
            r = parse(data)
            print("## %s  (extends %s%s)  [%s]" % (c.replace("/", "."), r["super"],
                  (", implements " + ", ".join(r["interfaces"])) if r["interfaces"] else "", os.path.basename(jar)))
            for kind in ("fields", "methods"):
                for a, nm, ds in r[kind]:
                    if "lambda$" in nm:
                        continue
                    if filt is None or nm in filt:
                        print("  %s %-9s %s %s" % (kind[0], a, nm, ds))
            return 0
    print("class not found: %s%s" % (argv[0], "" if use_fabric else " (add --fabric for Fabric API classes)"))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
