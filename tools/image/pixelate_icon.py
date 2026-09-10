from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image, ImageEnhance, ImageFilter


def pixelate(source: Path, target: Path, *, size: int, grid: int, colors: int) -> None:
    image = Image.open(source).convert("RGBA")
    if image.width != image.height:
        side = min(image.width, image.height)
        left = (image.width - side) // 2
        top = (image.height - side) // 2
        image = image.crop((left, top, left + side, top + side))

    # Work on a small logical pixel canvas, then enlarge with nearest-neighbour.
    logical = max(1, size // grid)
    image = image.resize((logical, logical), Image.Resampling.BOX)
    image = ImageEnhance.Contrast(image).enhance(1.04)
    alpha = image.getchannel("A")
    image = image.convert("RGB").quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert("RGBA")
    image.putalpha(alpha)
    image = image.resize((size, size), Image.Resampling.NEAREST)
    image.save(target, format="PNG", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Deterministically redraw a reference image as pixel art.")
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--grid", type=int, default=4, help="Output pixels per logical pixel")
    parser.add_argument("--colors", type=int, default=64)
    args = parser.parse_args()
    args.target.parent.mkdir(parents=True, exist_ok=True)
    pixelate(args.source, args.target, size=args.size, grid=args.grid, colors=args.colors)
    print(f"wrote {args.target} ({args.size}x{args.size}, {args.colors} colors, {args.grid}px grid)")


if __name__ == "__main__":
    main()
