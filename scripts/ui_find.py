#!/usr/bin/env python3
"""ui_find.py <uiautomator.xml> <text-fragment>  -> prints "x y" of the node centre, exit 1 if absent."""
import re, sys
import xml.etree.ElementTree as ET

tree = ET.parse(sys.argv[1])
for node in tree.iter("node"):
    text = (node.get("text") or "") + " " + (node.get("content-desc") or "")
    if sys.argv[2] in text:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
