# Télécharge les enregistrements d'analyse des pubs d'une page filebin et les décompresse.
# Usage : python3 -I tools/ad_fetch.py <adresse de la page filebin> <dossier>
# Un dossier par enregistrement (déjà présent : pas retéléchargé). Rien n'est écrit dans le dépôt.
import json, os, sys, urllib.request, zipfile

page, out = sys.argv[1].rstrip("/"), sys.argv[2]
if not page.startswith("http"):
    page = "https://" + page
os.makedirs(out, exist_ok=True)
request = urllib.request.Request(page, headers={"Accept": "application/json", "User-Agent": "curl/8.5.0"})
files = json.load(urllib.request.urlopen(request)).get("files") or []
for f in sorted(files, key=lambda f: f["filename"]):
    name = f["filename"]
    if not name.endswith(".zip"):
        continue
    folder = os.path.join(out, name[:-4])
    if os.path.isdir(folder):
        print("déjà là :", name)
        continue
    target = folder + ".zip"
    with urllib.request.urlopen(urllib.request.Request(page + "/" + name, headers={"User-Agent": "curl/8.5.0"})) as r, open(target, "wb") as w:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            w.write(chunk)
    if os.path.getsize(target) != f["bytes"]:
        print("incomplet :", name)
        continue
    zipfile.ZipFile(target).extractall(folder)
    os.remove(target)
    info = open(os.path.join(folder, "info.txt"), encoding="utf-8").read().replace("\n", " | ")
    print("reçu :", name, "|", info)
