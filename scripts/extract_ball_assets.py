from PIL import Image, ImageDraw, ImageFilter, ImageChops
import os

OUT = r"D:\Workspace\phone-type\android\app\src\main\res\drawable-nodpi"
os.makedirs(OUT, exist_ok=True)
SRC = r"D:\下载\ChatGPT Image 2026年9月12日 09_29_15.png"


def circular_extract(src, out, size=512, radius_frac=0.28, feather=24):
    """Center-crop a soft circle (ball is centered on a near-white canvas)."""
    im = Image.open(src).convert("RGBA")
    w, h = im.size
    cx, cy = w // 2, h // 2
    r = int(min(w, h) * radius_frac)
    # crop generous box
    box_r = r + feather + 8
    crop = im.crop((cx - box_r, cy - box_r, cx + box_r, cy + box_r))
    cw, ch = crop.size
    # alpha: opaque inside radius, feather to 0
    mask = Image.new("L", (cw, ch), 0)
    d = ImageDraw.Draw(mask)
    # hard core
    d.ellipse((cw / 2 - r, ch / 2 - r, cw / 2 + r, ch / 2 + r), fill=255)
    # glow ring: slightly larger, lower alpha via second draw then blur
    mask = mask.filter(ImageFilter.GaussianBlur(radius=feather * 0.55))
    # background is near-white; subtract by making very light pixels more transparent
    # so white canvas doesn't show as white square on dark apps — the circle mask already does this
    crop.putalpha(ImageChops.multiply(crop.split()[3], mask))

    # slight denoise of alpha
    a = crop.split()[3]
    crop.putalpha(a)

    crop.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(crop, ((size - crop.width) // 2, (size - crop.height) // 2), crop)
    canvas.save(out)
    print("saved", out, canvas.size, "r_frac", radius_frac)


def dock_from_full():
    full = Image.open(os.path.join(OUT, "ic_ball_s_photo.png")).convert("RGBA")
    bbox = full.getbbox()
    ball = full.crop(bbox)
    th = 192
    ball = ball.resize((int(ball.width * th / ball.height), th), Image.Resampling.LANCZOS)
    dock = ball.resize((max(1, int(ball.width * 0.36)), ball.height), Image.Resampling.LANCZOS)
    mask = Image.new("L", dock.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, dock.width - 1, dock.height - 1), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(2))
    dock.putalpha(ImageChops.darker(dock.split()[3], mask))
    path = os.path.join(OUT, "ic_ball_docked_photo.png")
    dock.save(path)
    print("dock", path, dock.size)


if __name__ == "__main__":
    # 球体实测直径约画布 42%，光晕再大一圈
    circular_extract(SRC, os.path.join(OUT, "ic_ball_s_photo.png"), 512, radius_frac=0.215, feather=14)
    dock_from_full()
    print("ok")
