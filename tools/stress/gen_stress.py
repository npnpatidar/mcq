#!/usr/bin/env python3
"""Generate large MCQ papers for stress-testing the app.

Usage:
    python3 gen_stress.py --questions 5000 --options 4 --out stress-5k.json
    python3 gen_stress.py --questions 500 --options 4 --image-every 50 --out stress-img.json

Notes:
    - Question/option ids are stable (q-stress-000001 ...), so re-importing
      the same file exercises the duplicate path instead of duplicating rows.
    - --image-every N embeds a small generated PNG data URI in every Nth
      question and one of its options. Images inflate the file fast; start
      text-only, then try images separately.
    - Transfer the file to the device (adb push / USB / Drive) and import it
      with Menu -> Import JSON. Watch logs/app.log for "Import complete"
      (total ms), "first load", and "mapped ... in ... ms" lines.
"""
import argparse
import base64
import json
import random
import struct
import zlib

SUBJECTS = [
    ("Science", [
        ("What is the chemical symbol for element {n}?", ["Opt A", "Opt B", "Opt C", "Opt D"], 2),
        ("Which planet is number {n} from the Sun?", ["Mercury", "Venus", "Earth", "Mars"], 0),
        ("Unit of {n} in SI?", ["Joule", "Newton", "Watt", "Pascal"], 1),
    ]),
    ("History", [
        ("In which year did event {n} occur?", ["1901", "1914", "1939", "1945"], 3),
        ("Who led expedition {n}?", ["Leader A", "Leader B", "Leader C", "Leader D"], 1),
    ]),
    ("Geography", [
        ("Capital of country {n}?", ["City A", "City B", "City C", "City D"], 0),
        ("River {n} flows through which continent?", ["Asia", "Africa", "Europe", "Americas"], 2),
    ]),
]


def tiny_png(seed, dim=96, jitter=12):
    rnd = random.Random(seed)
    w, h = dim, dim * 3 // 4
    base = (rnd.randrange(256), rnd.randrange(256), rnd.randrange(256))
    rows = []
    for y in range(h):
        row = bytearray(b"\x00")
        for x in range(w):
            j = rnd.randrange(-jitter, jitter + 1)
            row += struct.pack(
                "BBB",
                * [max(0, min(255, c + j + (x * 37 + y * 91) % 17 - 8)) for c in base]
            )
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        blob = tag + data
        return struct.pack(">I", len(data)) + blob + struct.pack(">I", zlib.crc32(blob) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    return "data:image/png;base64," + base64.b64encode(png).decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--questions", type=int, default=5000)
    ap.add_argument("--options", type=int, default=4)
    ap.add_argument("--image-every", type=int, default=0,
                    help="embed a PNG in every Nth question (0 = text only)")
    ap.add_argument("--img-dim", type=int, default=96,
                    help="generated image width in px (height = 3/4)")
    ap.add_argument("--img-jitter", type=int, default=12,
                    help="per-pixel noise; lower compresses smaller")
    ap.add_argument("--opt-img-every", type=int, default=0,
                    help="embed a PNG in one option of every Nth question (0 = off)")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default="stress.json")
    args = ap.parse_args()
    rnd = random.Random(args.seed)

    letters = [chr(ord("a") + i) for i in range(max(26, args.options))]
    per_subject = args.questions // len(SUBJECTS)
    categories = []
    qid = 0
    for ci, (title, templates) in enumerate(SUBJECTS):
        questions = []
        count = per_subject if ci < len(SUBJECTS) - 1 else args.questions - per_subject * (len(SUBJECTS) - 1)
        for _ in range(count):
            qid += 1
            text_t, opt_t, correct = rnd.choice(templates)
            n = rnd.randrange(2, 118)
            options = []
            for i in range(args.options):
                base = opt_t[i % len(opt_t)]
                suffix = "" if i < len(opt_t) else f" {i + 1}"
                options.append({"id": letters[i], "text": f"{base}{suffix}"})
            img = None
            if args.image_every and qid % args.image_every == 0:
                img = tiny_png(qid, args.img_dim, args.img_jitter)
            if args.opt_img_every and qid % args.opt_img_every == 0:
                options[0] = dict(options[0])
                options[0]["image"] = tiny_png(qid * 1000 + 1, args.img_dim, args.img_jitter)
            questions.append({
                "id": f"q-stress-{qid:06d}",
                "text": text_t.format(n=n) + f"  [#{qid}]",
                "image": img,
                "options": options,
                "correctOptionIds": [options[correct % args.options]["id"]],
                "explanation": f"Explanation for generated question #{qid}.",
                "difficulty": rnd.choice(["easy", "medium", "hard"]),
                "tags": ["stress", f"q{qid}"],
            })
        categories.append({"id": f"cat-stress-{ci}", "title": title, "questions": questions})

    doc = {"version": 1, "papers": [{
        "id": "paper-stress", "title": "Stress Paper",
        "description": f"Generated: {args.questions} questions",
        "durationMinutes": 0, "negativeMarking": 0.0,
        "categories": categories,
    }]}
    text = json.dumps(doc, ensure_ascii=False)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {args.out}: {len(text) / 1048576:.1f} MB, "
          f"{args.questions} questions, {args.options} options each")


if __name__ == "__main__":
    main()
