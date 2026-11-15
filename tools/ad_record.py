#!/usr/bin/env python3
"""Enregistre un direct HLS public pour rejouer la détection des pubs (AdReplayTool).

Usage : python3 -I ad_record.py <adresse .m3u8> <dossier> <heures> [indice de la qualité, -1 : la plus basse]
Demande ffmpeg. Sorties dans le dossier : audio.s16 (11025 Hz mono), frames.gray (480x270, 1/s), small.gray (96x54, 12,5/s),
tags.tsv (temps dans l'enregistrement, balise brute) : les repères de pub de la liste HLS, vérité de référence.
"""
import os, subprocess, sys, time, urllib.request, urllib.parse

master, out, hours = sys.argv[1], sys.argv[2], float(sys.argv[3])
variant_index = int(sys.argv[4]) if len(sys.argv) > 4 else -1
os.makedirs(out, exist_ok=True)
UA = {"User-Agent": "Mozilla/5.0"}


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20).read()


text = get(master).decode()
lines = text.splitlines()
if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):
    variants = [lines[i + 1] for i, l in enumerate(lines) if l.startswith("#EXT-X-STREAM-INF")]
    media = urllib.parse.urljoin(master, variants[variant_index])
else:
    media = master

RATE = 11025
audio = open(os.path.join(out, "audio.s16"), "ab")
frames = open(os.path.join(out, "frames.gray"), "ab")
small = open(os.path.join(out, "small.gray"), "ab")
tags = open(os.path.join(out, "tags.tsv"), "a")
log = open(os.path.join(out, "log.txt"), "a")
seen = set()
recorded = 0.0  # secondes enregistrées
next_frame = 0  # prochaine seconde entière à écrire dans frames.gray
next_small = 0  # prochain index (12,5/s) de small.gray
deadline = time.time() + hours * 3600
FW, FH, SW, SH = 480, 270, 96, 54


def decode(data, duration):
    global recorded, next_frame, next_small
    for n in ("a.tmp", "f.tmp", "s.tmp"):
        try:
            os.remove(os.path.join(out, n))
        except FileNotFoundError:
            pass
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", "pipe:0", "-map", "0:a:0?", "-ac", "1", "-ar", str(RATE), "-f", "s16le", "a.tmp",
         "-y"], input=data, cwd=out, capture_output=True)
    samples = round(duration * RATE)
    try:
        raw = open(os.path.join(out, "a.tmp"), "rb").read()
    except FileNotFoundError:
        raw = b""
    raw = raw[: samples * 2].ljust(samples * 2, b"\0")
    audio.write(raw)
    p = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", "pipe:0", "-filter_complex",
         f"[0:v:0]fps=12.5,split[a][b];[a]scale={FW}:{FH},format=gray[f];[b]scale={SW}:{SH}:flags=area,format=gray[s]",
         "-map", "[f]", "-f", "rawvideo", "f.tmp", "-map", "[s]", "-f", "rawvideo", "s.tmp", "-y"],
        input=data, cwd=out, capture_output=True)
    try:
        fbuf = open(os.path.join(out, "f.tmp"), "rb").read()
        sbuf = open(os.path.join(out, "s.tmp"), "rb").read()
    except FileNotFoundError:
        fbuf = sbuf = b""
    nf = len(fbuf) // (FW * FH)
    ns = len(sbuf) // (SW * SH)
    end = recorded + duration
    while next_frame < end:
        i = min(max(nf - 1, 0), round((next_frame - recorded) * 12.5))
        frames.write(fbuf[i * FW * FH:(i + 1) * FW * FH] if nf else bytes(FW * FH))
        next_frame += 1
    while next_small / 12.5 < end:
        i = min(max(ns - 1, 0), round((next_small / 12.5 - recorded) * 12.5))
        small.write(sbuf[i * SW * SH:(i + 1) * SW * SH] if ns else bytes(SW * SH))
        next_small += 1
    recorded = end


while time.time() < deadline:
    try:
        playlist = get(media).decode()
    except Exception as e:  # réseau : on réessaie
        log.write(f"{time.time():.0f} playlist {e}\n"); log.flush(); time.sleep(2); continue
    pending_tags = []
    seq = 0
    duration = 0.0
    for line in playlist.splitlines():
        if line.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            seq = int(line.split(":", 1)[1])
        elif line.startswith("#EXTINF:"):
            duration = float(line[8:].split(",")[0])
        elif line.startswith("#") and not line.startswith(("#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION",
                                                              "#EXT-X-MEDIA-SEQUENCE", "#EXT-X-INDEPENDENT")):
            pending_tags.append(line)
        elif line and not line.startswith("#"):
            key = seq
            seq += 1
            if key in seen:
                pending_tags = []
                continue
            seen.add(key)
            for t in pending_tags:
                tags.write(f"{recorded:.3f}\t{t}\n")
            tags.flush()
            pending_tags = []
            try:
                data = get(urllib.parse.urljoin(media, line))
            except Exception as e:
                log.write(f"{time.time():.0f} segment {e}\n"); log.flush()
                data = b""
            decode(data, duration)
            for f in (audio, frames, small):
                f.flush()
    time.sleep(2)
