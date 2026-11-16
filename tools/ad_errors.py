# Erreurs d'une notation (AdDeviceReplayTool) : pubs ratées et programme baissé à tort, pour l'appareil et le code d'ici,
# avec la raison donnée par l'appareil (events.tsv) autour de chaque erreur.
# Usage : python3 -I tools/ad_errors.py <dossier des enregistrements> <étiquette AD_TAG>
import glob, os, sys

root, tag = sys.argv[1], sys.argv[2]
def mmss(s):
    return "%d:%02d" % (s // 60, s % 60)
for timeline in sorted(glob.glob(os.path.join(root, "*_timeline_%s.tsv" % tag))):
    name = timeline[: -len("_timeline_%s.tsv" % tag)]
    truth = [tuple(map(float, l.split()[:2])) for l in open(name + ".truth.tsv") if l.strip()]
    rows = [l.split("\t") for l in open(timeline).read().splitlines()[1:]]
    events = []
    if os.path.exists(os.path.join(name, "events.tsv")):
        events = [l.split("\t") for l in open(os.path.join(name, "events.tsv"), encoding="utf-8").read().splitlines() if l.count("\t") >= 2]
    print("==", os.path.basename(name))
    for col, who in ((1, "ici"), (2, "appareil")):
        for wanted, label in ((True, "à tort"), (False, "ratée")):
            start = None
            for r in rows + [None]:
                if r is None:
                    r = [str(int(rows[-1][0]) + 1), "x", "x"]
                s = int(r[0])
                ad = any(a <= s + 0.5 <= b for a, b in truth)
                wrong = r[col] != "x" and ((r[col] == "1" and not ad) if wanted else (r[col] == "0" and ad))
                if wrong and start is None:
                    start = s
                if not wrong and start is not None:
                    if s - start >= 5:
                        why = [e[1] + " " + e[2] for e in events if start * 1000 - 30000 <= int(e[0]) <= s * 1000 and e[1] in ("ouverture", "fermeture")] if col == 2 else []
                        print("  %-8s %-6s %s (%d s) %s" % (who, label, mmss(start), s - start, " ; ".join(why[:3])))
                    start = None
