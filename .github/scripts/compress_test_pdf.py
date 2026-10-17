"""PDF de test pour la compression : photo JPEG, gris, palette, ton direct, image transparente
et graphique vectoriel. Chaque image est en haute résolution mais affichée petite."""
import io
import math
import sys
import zlib

from PIL import Image, ImageDraw

objs = []


def add(body):
    objs.append(body)
    return len(objs)


def stream(dict_entries, data):
    return add((("<< %s /Length %d >>\nstream\n" % (dict_entries, len(data))).encode("latin-1"), data, b"\nendstream"))


def photo(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = (int(127 + 127 * math.sin(x / 37.0)), int(127 + 127 * math.sin(y / 23.0)), int((x * y) % 255))
    return img


def chart(w, h, mode):
    img = Image.new(mode, (w, h), "white")
    d = ImageDraw.Draw(img)
    colors = [(211, 67, 31), (31, 78, 140), (47, 107, 79), (242, 194, 48)]
    for i in range(8):
        top = int(h * (0.85 - 0.08 * ((i * 37) % 9)))
        d.rectangle([40 + i * (w - 80) // 8, top, 40 + (i + 1) * (w - 80) // 8 - 20, int(h * 0.9)], fill=colors[i % 4])
    d.line([30, int(h * 0.9), w - 30, int(h * 0.9)], fill=(0, 0, 0), width=6)
    return img


# Images
jpg = io.BytesIO()
photo(2400, 1600).save(jpg, "JPEG", quality=95)
img_photo = stream("/Type /XObject /Subtype /Image /Width 2400 /Height 1600 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpg.getvalue())

gray = Image.linear_gradient("L").resize((1500, 1000))
img_gray = stream("/Type /XObject /Subtype /Image /Width 1500 /Height 1000 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", zlib.compress(gray.tobytes()))

pal = chart(1600, 1000, "RGB").quantize(colors=16)
palette = bytes((pal.getpalette() + [0] * 48)[:48])
img_indexed = stream(
    "/Type /XObject /Subtype /Image /Width 1600 /Height 1000 /ColorSpace [/Indexed /DeviceRGB 15 <%s>] /BitsPerComponent 8 /Filter /FlateDecode" % palette.hex(),
    zlib.compress(pal.tobytes()),
)

tint_fn = add(b"<< /FunctionType 2 /Domain [0 1] /C0 [0 0 0 0] /C1 [0 0.8 0.9 0.1] /N 1 >>")
spot = Image.linear_gradient("L").resize((1200, 800))
img_spot = stream(
    "/Type /XObject /Subtype /Image /Width 1200 /Height 800 /ColorSpace [/Separation /PantoneRouge /DeviceCMYK %d 0 R] /BitsPerComponent 8 /Filter /FlateDecode" % tint_fn,
    zlib.compress(spot.tobytes()),
)

rgba = chart(1400, 900, "RGBA")
alpha = Image.new("L", rgba.size, 0)
ImageDraw.Draw(alpha).ellipse([0, 0, 1400, 900], fill=255)
smask = stream("/Type /XObject /Subtype /Image /Width 1400 /Height 900 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", zlib.compress(alpha.tobytes()))
img_alpha = stream(
    "/Type /XObject /Subtype /Image /Width 1400 /Height 900 /ColorSpace /DeviceRGB /BitsPerComponent 8 /SMask %d 0 R /Filter /FlateDecode" % smask,
    zlib.compress(rgba.convert("RGB").tobytes()),
)

flat = chart(2400, 1500, "RGB")
img_flat = stream("/Type /XObject /Subtype /Image /Width 2400 /Height 1500 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode", zlib.compress(flat.tobytes()))

font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

vector_chart = b"0.83 0.26 0.12 rg 70 120 40 120 re f 0.12 0.31 0.55 rg 130 120 40 80 re f 0.18 0.42 0.31 rg 190 120 40 150 re f 0 0 0 RG 2 w 60 118 m 260 118 l S"
pages_content = [
    b"BT /F1 18 Tf 50 790 Td (1. Photo JPEG + graphique vectoriel) Tj ET q 300 0 0 200 50 560 cm /Ph Do Q " + vector_chart,
    b"BT /F1 18 Tf 50 790 Td (2. Niveaux de gris + palette) Tj ET q 400 0 0 267 50 480 cm /Gr Do Q q 400 0 0 250 50 180 cm /Ix Do Q",
    b"BT /F1 18 Tf 50 790 Td (3. Ton direct + transparence + graphique Flate) Tj ET q 300 0 0 200 50 560 cm /Sp Do Q "
    b"0.95 0.9 0.8 rg 40 300 520 220 re f q 330 0 0 212 60 305 cm /Al Do Q q 250 0 0 156 50 100 cm /Fl Do Q",
]
xobjects = "/Ph %d 0 R /Gr %d 0 R /Ix %d 0 R /Sp %d 0 R /Al %d 0 R /Fl %d 0 R" % (img_photo, img_gray, img_indexed, img_spot, img_alpha, img_flat)
contents = [stream("", c) for c in pages_content]
pages_id = len(objs) + len(contents) + 1
kids = [add(("<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 %d 0 R >> /XObject << %s >> >> /Contents %d 0 R >>"
             % (pages_id, font, xobjects, c)).encode()) for c in contents]
assert add(("<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % k for k in kids), len(kids))).encode()) == pages_id
catalog = add(("<< /Type /Catalog /Pages %d 0 R >>" % pages_id).encode())

out = bytearray(b"%PDF-1.5\n%\xe2\xe3\xcf\xd3\n")
offsets = []
for i, o in enumerate(objs, 1):
    offsets.append(len(out))
    out += b"%d 0 obj\n" % i
    out += b"".join(o) if isinstance(o, tuple) else o
    out += b"\nendobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
for off in offsets:
    out += b"%010d 00000 n \n" % off
out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, catalog, xref)
open(sys.argv[1], "wb").write(out)
