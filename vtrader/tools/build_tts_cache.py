"""Build the offline speech cache by calling xAI's text-to-speech once per clip.

The program says almost nothing but numbers, and numbers are a closed set once they are read three
digits at a time. This fetches every clip in that set ahead of time, so at run time there is no
network call at all: the player only concatenates WAV that is already on disk.

The clip list comes from the Java side (trader.VocabDump writes it), so there is one source of truth
for what a clip is called rather than a second copy of the same arithmetic here.

Each clip is stored as <cache>/<lang>/<sha1 of the text>.wav, and the Java runtime derives the same
name the same way. Leading and trailing silence is trimmed before saving, which is worth about a
fifth of the total speaking time once the clips are played back to back.

Usage:
    python tools/build_tts_cache.py tts_cache/vocab_fa.txt
    python tools/build_tts_cache.py tts_cache/vocab_fa.txt --workers 8 --speed 1.5
    python tools/build_tts_cache.py tts_cache/vocab_fa.txt --dry-run

Needs XAI_API_KEY in the environment. Clips already on disk are left alone, so the run is
resumable: interrupt it and start it again.
"""
import argparse
import audioop
import concurrent.futures as futures
import hashlib
import io
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import wave

ENDPOINT = "https://api.x.ai/v1/tts"
DEFAULT_CACHE = "tts_cache"


def clip_path(cache, lang, text):
    digest = hashlib.sha1(text.encode("utf-8")).hexdigest()
    return os.path.join(cache, lang, digest + ".wav")


def synthesize(text, language, voice, speed, sample_rate, api_key, timeout=120):
    """One clip, as raw WAV bytes."""
    body = json.dumps({
        "text": text,
        "voice_id": voice,
        "language": language,
        "speed": speed,
        "text_normalization": False,
        "output_format": {"codec": "wav", "sample_rate": sample_rate},
    }).encode("utf-8")
    req = urllib.request.Request(ENDPOINT, data=body, method="POST", headers={
        "Authorization": "Bearer " + api_key,
        "Content-Type": "application/json",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def trim_silence(raw, ratio=0.02, window=0.01):
    """Drop the near-silent head and tail. Returns WAV bytes in the same format."""
    with wave.open(io.BytesIO(raw), "rb") as w:
        channels, width, rate = w.getnchannels(), w.getsampwidth(), w.getframerate()
        data = w.readframes(w.getnframes())
    step = max(1, int(rate * window)) * width * channels
    windows = [data[i:i + step] for i in range(0, len(data), step)]
    levels = [audioop.rms(b, width) if len(b) >= width else 0 for b in windows]
    if not levels:
        return raw
    threshold = (max(levels) or 1) * ratio
    first = next((i for i, v in enumerate(levels) if v > threshold), 0)
    last = len(levels) - 1 - next((i for i, v in enumerate(reversed(levels)) if v > threshold), 0)
    kept = b"".join(windows[first:last + 1])
    if not kept:
        return raw
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(kept)
    return out.getvalue()


def load_pronunciations(path):
    """Optional 'plain text = how to spell it for the engine' map.

    The engine mispronounces the odd word, and the fix is to hand it a differently spelled version -
    Persian zero, for instance, reads better fully vowelled. The clip is still stored under the plain
    text, because that is what the tokenizer asks for; only what gets synthesized changes.
    """
    out = {}
    if not path or not os.path.exists(path):
        return out
    for line in io.open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        left, _, right = line.partition("=")
        key, val = left.strip(), right.strip()
        if key and val:
            out[key] = val
    return out


def load_vocabulary(path):
    entries = []
    with io.open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#"):
                continue
            lang, _, text = line.partition("\t")
            if text:
                entries.append((lang, text))
    return entries


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("vocabulary", help="tab separated <lang>\\t<text>, one clip per line")
    ap.add_argument("--cache", default=DEFAULT_CACHE)
    ap.add_argument("--voice", default="eve")
    ap.add_argument("--speed", type=float, default=1.5, help="0.7 to 1.5; 1.5 is the fastest allowed")
    ap.add_argument("--sample-rate", type=int, default=24000)
    ap.add_argument("--language", default=None,
                    help="override the language for every clip; by default it comes from the "
                         "lang column (en stays English, anything else is auto-detected)")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--retries", type=int, default=3)
    ap.add_argument("--no-trim", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--pronounce", default=os.path.join("my_config", "pronounce_fa.txt"),
                    help="optional 'text = how to spell it for the engine' map; the clip is still "
                         "stored under the plain text, so only the pronunciation changes")
    ap.add_argument("--force", action="store_true",
                    help="regenerate clips that are already cached")
    args = ap.parse_args()

    spelling = load_pronunciations(args.pronounce)
    if spelling:
        print(f"pronunciation overrides: {len(spelling)} (from {args.pronounce})")

    api_key = os.environ.get("XAI_API_KEY", "").strip()
    if not api_key and not args.dry_run:
        sys.exit("XAI_API_KEY is not set in the environment")

    entries = load_vocabulary(args.vocabulary)
    todo = [(lang, text) for lang, text in entries
            if args.force or not os.path.exists(clip_path(args.cache, lang, text))]
    print(f"vocabulary: {len(entries)} clips")
    print(f"already cached: {len(entries) - len(todo)}")
    print(f"to fetch: {len(todo)}  (voice={args.voice} speed={args.speed} rate={args.sample_rate})")
    if args.dry_run or not todo:
        return

    for lang in {lang for lang, _ in todo}:
        os.makedirs(os.path.join(args.cache, lang), exist_ok=True)

    done = {"ok": 0, "failed": 0, "bytes": 0}
    failures = []
    lock = threading.Lock()
    started = time.time()

    def work(item):
        lang, text = item
        # Persian is not in the model's language list, so it is left to auto-detection - which is
        # what the listening test was done with. English is asked for by name, because an instrument
        # like "US30" is short enough that auto-detection has little to go on.
        language = args.language or ("en" if lang == "en" else "auto")
        last = None
        for attempt in range(args.retries):
            try:
                raw = synthesize(spelling.get(text, text), language, args.voice, args.speed,
                                 args.sample_rate, api_key)
                if not raw.startswith(b"RIFF"):
                    raise ValueError("response was not WAV")
                if not args.no_trim:
                    raw = trim_silence(raw)
                path = clip_path(args.cache, lang, text)
                tmp = path + ".part"
                with open(tmp, "wb") as f:
                    f.write(raw)
                os.replace(tmp, path)
                with lock:
                    done["ok"] += 1
                    done["bytes"] += len(raw)
                    n = done["ok"] + done["failed"]
                    if n % 25 == 0 or n == len(todo):
                        rate = n / max(0.001, time.time() - started)
                        left = (len(todo) - n) / max(0.001, rate)
                        print(f"  {n}/{len(todo)}  {rate:.1f}/s  ~{left/60:.1f} min left"
                              f"  (failed {done['failed']})", flush=True)
                return
            except Exception as e:                        # noqa: BLE001 - report, never abort the batch
                last = e
                time.sleep(1.5 * (attempt + 1))
        with lock:
            done["failed"] += 1
            failures.append((lang, text, repr(last)))

    with futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(work, todo))

    elapsed = time.time() - started
    print(f"\ndone in {elapsed/60:.1f} min: {done['ok']} fetched, {done['failed']} failed, "
          f"{done['bytes']/1e6:.1f} MB")
    if failures:
        report = os.path.join(args.cache, "failed.tsv")
        with io.open(report, "w", encoding="utf-8") as f:
            for lang, text, err in failures:
                f.write(f"{lang}\t{text}\t{err}\n")
        print(f"failures listed in {report} - run again to retry only those")


if __name__ == "__main__":
    main()
