# Planches de vignettes d’un enregistrement d’analyse des pubs (format 3), marquées avec ce que l’appareil a fait.
# Usage : python3 -I tools/ad_sheets.py <dossier de l’enregistrement> <dossier des planches> [secondes entre vignettes, 20]
import io, os, struct, sys
from PIL import Image, ImageDraw
d, out, step = sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 20
os.makedirs(out, exist_ok=True)
ev = [l.split("\t") for l in open(os.path.join(d, "events.tsv")).read().splitlines() if l.count("\t") >= 2]
# « flux » : la détection repart de zéro, son non baissé (avant 0.2.173, l’appareil ne notait pas ce retour).
low = [(int(t), w == "baisse" and v == "1") for t, w, v in ev if w in ("baisse", "flux")]
logo = [(int(t), v) for t, w, v in ev if w == "logo"]
def at(lst, t, default):
    r = default
    for tt, v in lst:
        if tt <= t: r = v
        else: break
    return r
thumbs = []
b = open(os.path.join(d, "frames.bin"), "rb").read()
i = 0
while i + 13 <= len(b):
    typ = chr(b[i]); t = struct.unpack(">q", b[i+1:i+9])[0]; n = struct.unpack(">i", b[i+9:i+13])[0]
    if typ == "T": thumbs.append((t, b[i+13:i+13+n]))
    i += 13 + n
thumbs = [x for k, x in enumerate(thumbs) if k % (step // 5) == 0]
cols, rows = 8, 12
W, H = 160, 90
per = cols * rows
for s in range(0, len(thumbs), per):
    sheet = Image.new("RGB", (cols * (W + 6), rows * (H + 18)), "white")
    dr = ImageDraw.Draw(sheet)
    for k, (t, jpg) in enumerate(thumbs[s:s+per]):
        x, y = (k % cols) * (W + 6), (k // cols) * (H + 18)
        im = Image.open(io.BytesIO(jpg)).convert("RGB")
        lowered = at(low, t, False)
        lg = at(logo, t, "?")
        if lowered: dr.rectangle([x, y, x + W + 5, y + H + 17], fill=(230, 40, 40))
        sheet.paste(im, (x + 3, y + 3))
        dr.text((x + 4, y + H + 4), "%d:%02d %s%s" % (t // 60000, t // 1000 % 60, lg[0], " BAISSE" if lowered else ""), fill=(0, 0, 0) if not lowered else (255, 255, 255))
    sheet.save(os.path.join(out, "%s_%02d.png" % (os.path.basename(d.rstrip("/")), s // per)))
print(len(thumbs), "vignettes")
