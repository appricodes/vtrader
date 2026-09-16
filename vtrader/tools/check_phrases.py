"""Check the phrase dictionary against the fragments actually present in the source, and write
the clip list for the ones that need audio.

Two things can go wrong with a hand-written map and both are silent at run time: a fragment in the
code with no translation (falls through to English) and a translation for a fragment that no longer
exists (dead weight, and usually a sign the wording in the code changed). This reports both.

Usage:
    python tools/check_phrases.py my_config/phrases_fa.json <extracted-fragments.txt> [vocab-out]
"""
import io, sys, os


def load_map(path):
    """The phrase dictionary: a flat JSON object of english -> persian."""
    import json
    raw = json.load(io.open(path, encoding="utf-8"))
    pairs, order = {}, []
    for key, val in raw.items():
        norm = " ".join(str(key).split()).lower()
        if norm in pairs:
            print(f"  ! duplicate key once normalised: {norm!r}")
        pairs[norm] = " ".join(str(val).split())
        order.append(norm)
    return pairs, order


def load_fragments(path):
    out = []
    for line in io.open(path, encoding="utf-8"):
        line = line.rstrip("\n").rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        # "<count>  <fragment>"
        parts = line.split(None, 1)
        if len(parts) == 2 and parts[0].isdigit():
            out.append((int(parts[0]), " ".join(parts[1].split())))
    return out


def main():
    map_path, frag_path = sys.argv[1], sys.argv[2]
    vocab_out = sys.argv[3] if len(sys.argv) > 3 else None

    pairs, order = load_map(map_path)
    frags = load_fragments(frag_path)

    # the regex pieces are not speech; formatPrice builds patterns with String.format
    ignore = {"^(\\d{", "})", "})(\\d{"}

    missing = [(n, f) for n, f in frags if f.lower() not in pairs and f not in ignore]
    used = {f.lower() for _, f in frags}
    unused = [k for k in order if k not in used]

    print(f"fragments in source : {len(frags)}")
    print(f"translated          : {len(frags) - len(missing) - len([1 for _, f in frags if f in ignore])}")
    print(f"deliberately ignored: {len([1 for _, f in frags if f in ignore])}")
    print()
    if missing:
        print(f"MISSING a translation ({len(missing)}) - these would be spoken in English:")
        for n, f in sorted(missing, key=lambda x: -x[0]):
            print(f"  {n:3}  {f}")
    else:
        print("every fragment in the source has a translation.")
    print()
    if unused:
        print(f"in the map but not found in the source ({len(unused)}):")
        for k in unused:
            print(f"       {k}")
    else:
        print("no dead entries in the map.")

    if vocab_out:
        spoken = sorted({v for v in pairs.values() if v})
        with io.open(vocab_out, "w", encoding="utf-8") as f:
            for text in spoken:
                f.write("fa\t" + text + "\n")
        print(f"\nwrote {len(spoken)} distinct phrase clips to {vocab_out}")


if __name__ == "__main__":
    main()
