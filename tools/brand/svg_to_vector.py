#!/usr/bin/env python3
"""Convert Tuikio's hand-authored SVGs into Android VectorDrawable XML.

Supports exactly the SVG subset our brand sources use: <path>, <circle>, <ellipse>, <rect>
(with rx), <g transform="translate(..) rotate(a cx cy) scale(s)">, fill/stroke attributes and
opacity. Anything else fails loudly instead of being dropped.

    python3 tools/brand/svg_to_vector.py in.svg out.xml [--size 108] [--viewbox "x y w h"] [--tint #RRGGBB]
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

SVG = "{http://www.w3.org/2000/svg}"
A = "android:"


def fnum(v):
    s = f"{float(v):.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def circle_path(cx, cy, rx, ry):
    return (f"M{fnum(cx - rx)},{fnum(cy)} a{fnum(rx)},{fnum(ry)} 0 1,0 {fnum(2 * rx)},0 "
            f"a{fnum(rx)},{fnum(ry)} 0 1,0 {fnum(-2 * rx)},0 Z")


def rect_path(x, y, w, h, rx):
    rx = min(rx, w / 2, h / 2)
    if rx <= 0:
        return f"M{fnum(x)},{fnum(y)} h{fnum(w)} v{fnum(h)} h{fnum(-w)} Z"
    return (f"M{fnum(x + rx)},{fnum(y)} h{fnum(w - 2 * rx)} a{fnum(rx)},{fnum(rx)} 0 0,1 {fnum(rx)},{fnum(rx)} "
            f"v{fnum(h - 2 * rx)} a{fnum(rx)},{fnum(rx)} 0 0,1 {fnum(-rx)},{fnum(rx)} h{fnum(-(w - 2 * rx))} "
            f"a{fnum(rx)},{fnum(rx)} 0 0,1 {fnum(-rx)},{fnum(-rx)} v{fnum(-(h - 2 * rx))} "
            f"a{fnum(rx)},{fnum(rx)} 0 0,1 {fnum(rx)},{fnum(-rx)} Z")


def color(v, opacity=None, tint=None):
    if v is None or v == "none":
        return None
    if tint:
        # Monochrome (themed icon) layers keep only the darker shapes; light fills become holes.
        r, g, b = (int(v[i:i + 2], 16) / 255 for i in (1, 3, 5))
        if 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.8:
            return None
        v = tint
    if not re.match(r"^#[0-9A-Fa-f]{6}$", v):
        sys.exit(f"unsupported color {v}")
    if opacity is not None and float(opacity) < 1:
        return "#%02X%s" % (round(float(opacity) * 255), v[1:].upper())
    return v.upper()


def path_attrs(el, d, tint, inherited=None):
    inherited = inherited or {}
    attrs = {f"{A}pathData": d}
    op = el.get("opacity")
    fill = color(el.get("fill", inherited.get("fill", "#000000")), op, tint)
    stroke = color(el.get("stroke", inherited.get("stroke")), op, tint)
    if fill:
        attrs[f"{A}fillColor"] = fill
    if stroke:
        attrs[f"{A}strokeColor"] = stroke
        attrs[f"{A}strokeWidth"] = fnum(el.get("stroke-width", "1"))
        if el.get("stroke-linecap"):
            attrs[f"{A}strokeLineCap"] = el.get("stroke-linecap")
        if el.get("stroke-linejoin"):
            attrs[f"{A}strokeLineJoin"] = el.get("stroke-linejoin")
    return attrs


def convert(el, out, tint, inherited=None):
    inherited = dict(inherited or {})
    tag = el.tag.replace(SVG, "")
    if tag in ("title", "desc"):
        return
    if tag == "g":
        parent = out
        for kind, args in re.findall(r"(translate|rotate|scale)\(([^)]*)\)", el.get("transform", "")):
            nums = [float(x) for x in re.split(r"[\s,]+", args.strip())]
            g = ET.SubElement(parent, "group")
            if kind == "translate":
                g.set(f"{A}translateX", fnum(nums[0]))
                g.set(f"{A}translateY", fnum(nums[1] if len(nums) > 1 else 0))
            elif kind == "rotate":
                g.set(f"{A}rotation", fnum(nums[0]))
                if len(nums) == 3:
                    g.set(f"{A}pivotX", fnum(nums[1]))
                    g.set(f"{A}pivotY", fnum(nums[2]))
            else:
                g.set(f"{A}scaleX", fnum(nums[0]))
                g.set(f"{A}scaleY", fnum(nums[1] if len(nums) > 1 else nums[0]))
            parent = g
        for k in ("fill", "stroke"):
            if el.get(k):
                inherited[k] = el.get(k)
        for child in el:
            convert(child, parent, tint, inherited)
        return
    if tag == "path":
        d = el.get("d")
    elif tag == "circle":
        r = float(el.get("r"))
        d = circle_path(float(el.get("cx")), float(el.get("cy")), r, r)
    elif tag == "ellipse":
        d = circle_path(float(el.get("cx")), float(el.get("cy")), float(el.get("rx")), float(el.get("ry")))
    elif tag == "rect":
        x, y = float(el.get("x", 0)), float(el.get("y", 0))
        w, h = float(el.get("width")), float(el.get("height"))
        if el.get("transform"):
            m = re.match(r"rotate\(([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\)", el.get("transform"))
            if not m:
                sys.exit("unsupported rect transform")
            g = ET.SubElement(out, "group", {f"{A}rotation": fnum(m.group(1)), f"{A}pivotX": fnum(m.group(2)), f"{A}pivotY": fnum(m.group(3))})
            ET.SubElement(g, "path", path_attrs(el, rect_path(x, y, w, h, float(el.get("rx", 0))), tint, inherited))
            return
        d = rect_path(x, y, w, h, float(el.get("rx", 0)))
    else:
        sys.exit(f"unsupported element <{tag}>")
    if el.get("transform") and tag != "rect":
        sys.exit(f"transform on <{tag}> is not supported")
    attrs = path_attrs(el, d, tint, inherited)
    if f"{A}fillColor" in attrs or f"{A}strokeColor" in attrs:
        ET.SubElement(out, "path", attrs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--size", type=float, default=None, help="dp size of the drawable (default: viewBox size)")
    ap.add_argument("--tint", default=None, help="paint every fill/stroke with this color (monochrome layers)")
    args = ap.parse_args()
    root = ET.parse(args.src).getroot()
    vb = [float(v) for v in re.split(r"[\s,]+", root.get("viewBox").strip())]
    vx, vy, vw, vh = vb
    ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
    vec = ET.Element("vector", {
        "xmlns:android": "http://schemas.android.com/apk/res/android",
        f"{A}width": fnum(args.size or vw) + "dp",
        f"{A}height": fnum((args.size or vw) * vh / vw) + "dp",
        f"{A}viewportWidth": fnum(vw),
        f"{A}viewportHeight": fnum(vh),
    })
    target = vec
    if vx != 0 or vy != 0:
        target = ET.SubElement(vec, "group", {f"{A}translateX": fnum(-vx), f"{A}translateY": fnum(-vy)})
    for child in root:
        convert(child, target, args.tint)
    ET.indent(vec)
    xml = ET.tostring(vec, encoding="unicode")
    with open(args.dst, "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated from ' + args.src.split("design/")[-1] +
                 ' by tools/brand/svg_to_vector.py -->\n' + xml + "\n")


if __name__ == "__main__":
    main()
