#!/usr/bin/env python3
"""
bsc5_converter.py — fetch the Yale Bright Star Catalogue (BSC5, public domain,
~9000 real stars) and write it as a simple CSV the game reads at startup.

Run ONCE:
    python3 bsc5_converter.py

It writes:   src/main/resources/data/bsc5.csv
Format:      RA_deg, Dec_deg, Vmag, BV     (one real star per line)

Once this file has the full catalogue, the game's procedural "faint star fill"
(in Astronomy.loadBSC5) automatically switches off and you get the real sky —
Orion, the Big Dipper, etc., all where they belong.

The BSC5 is a fixed-width ASCII catalogue; byte columns below are from the
official ADC/VizieR ReadMe for catalogue V/50.
"""

import gzip
import io
import os
import sys
import urllib.request

OUT_PATH = os.path.join("src", "main", "resources", "data", "bsc5.csv")

# Primary + fallback sources for the raw catalogue.
SOURCES = [
    "http://tdc-www.harvard.edu/catalogs/bsc5.dat.gz",
    "http://tdc-www.harvard.edu/catalogs/bsc5.dat",
]


def fetch_catalogue() -> list[str]:
    last_err = None
    for url in SOURCES:
        try:
            print(f"[bsc5] downloading {url} ...")
            req = urllib.request.Request(url, headers={"User-Agent": "bsc5-converter"})
            raw = urllib.request.urlopen(req, timeout=30).read()
            if url.endswith(".gz"):
                raw = gzip.decompress(raw)
            text = raw.decode("latin-1")
            lines = text.splitlines()
            print(f"[bsc5] got {len(lines)} catalogue lines")
            return lines
        except Exception as e:  # noqa: BLE001
            print(f"[bsc5]   failed: {e}")
            last_err = e
    raise SystemExit(f"[bsc5] could not download the catalogue: {last_err}")


def parse_line(line: str):
    """Return (ra_deg, dec_deg, vmag, bv) or None if the row has no coordinates."""
    try:
        rah = line[75:77].strip()
        if not rah:                       # novae / objects without coords
            return None
        ram = line[77:79].strip()
        ras = line[79:83].strip()
        sign = line[83:84].strip()
        ded = line[84:86].strip()
        dem = line[86:88].strip()
        des = line[88:90].strip()
        vmag = line[102:107].strip()
        bv = line[109:114].strip()

        ra_hours = float(rah) + float(ram) / 60.0 + float(ras) / 3600.0
        ra_deg = ra_hours * 15.0
        dec_deg = float(ded) + float(dem) / 60.0 + float(des) / 3600.0
        if sign == "-":
            dec_deg = -dec_deg

        vmag_f = float(vmag) if vmag else 6.5
        bv_f = float(bv) if bv else 0.0
        return ra_deg, dec_deg, vmag_f, bv_f
    except (ValueError, IndexError):
        return None


def main():
    lines = fetch_catalogue()
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    written = 0
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write("# Yale Bright Star Catalogue 5 - converted by bsc5_converter.py\n")
        f.write("# RA_deg, Dec_deg, Vmag, BV\n")
        for line in lines:
            rec = parse_line(line)
            if rec is None:
                continue
            f.write(f"{rec[0]:.6f},{rec[1]:.6f},{rec[2]:.3f},{rec[3]:.3f}\n")
            written += 1
    print(f"[bsc5] wrote {written} stars -> {OUT_PATH}")
    if written < 1000:
        print("[bsc5] WARNING: fewer stars than expected; the source format may have changed.")


if __name__ == "__main__":
    sys.exit(main())
