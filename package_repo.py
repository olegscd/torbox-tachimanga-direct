import argparse
import json
import shutil
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description="Create a static Tachimanga extension repo.")
parser.add_argument("apk", type=Path)
parser.add_argument("--out", type=Path, default=Path("dist"))
args = parser.parse_args()

with zipfile.ZipFile(args.apk) as package:
    if not {"AndroidManifest.xml", "classes.dex"} <= set(package.namelist()):
        parser.error("Expected a built Android extension APK.")

filename = "torbox-direct-v1.4.1.apk"
(args.out / "apk").mkdir(parents=True, exist_ok=True)
shutil.copyfile(args.apk, args.out / "apk" / filename)
entry = {
    "name": "Tachiyomi: TorBox Direct",
    "pkg": "eu.kanade.tachiyomi.extension.en.torboxdirect",
    "apk": filename,
    "lang": "en",
    "code": 1,
    "version": "1.4.1",
    "nsfw": 0,
    "sources": [{
        "name": "TorBox Direct",
        "lang": "en",
        "id": "746850379145316052",
        "baseUrl": "https://api.torbox.app",
        "versionId": 1,
    }],
}
(args.out / "index.min.json").write_text(json.dumps([entry], separators=(",", ":")), encoding="utf-8")
