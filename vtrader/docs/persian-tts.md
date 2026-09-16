# Persian speech: what was built, what was learned, where it stands

Written 16 September 2026. The work is **parked, not abandoned**: everything below exists and works,
but none of it is wired into the program. The app speaks exactly as it did before, through the system
voice. The decision still to make is whether to switch it on at all.

---

## The state of the app right now

`Main.speak()` goes to `Speaker` (the long-lived PowerShell host), and falls back to the TTS library
if that host is unavailable. That is all. Nothing Persian is reachable from the running program:

- `SpeechTokens.java` compiles but nothing in `src/trader/` references it.
- No key is bound to switch modes - the `f` key was discussed and never added.
- The clip cache is on disk and nothing reads it.

So there is nothing to undo before a release, and nothing to be careful about when changing other
parts of the program.

---

## The idea

Almost everything this program says is numbers, and numbers are a closed set once they are read three
digits at a time - every group is 0 to 999. So the audio for every price, profit and quantity the
program will ever report can be generated once, ahead of time, and played from disk. No engine, no
network, no latency.

```
12345.98765  ->  12 | 345 | point | 987 | 65
```

The integer part is grouped in threes from the right, the fractional part in threes from the left.
Leading zeros inside a group are spoken, because 12045 and 12450 must not sound alike.

---

## What exists

### Code

| File | What it does | Wired in? |
|---|---|---|
| `src/trader/SpeechTokens.java` | Turns an announcement into the ordered list of clips that say it. No I/O, no network - only the decision about what should be said. | **No** |
| `tools/build_tts_cache.py` | Builds the clip cache by calling xAI. Resumable, parallel, honours a pronunciation map. | n/a |
| `tools/check_phrases.py` | Checks the phrase dictionary against the fragments actually in the source, both ways. | n/a |

### Data

| File | What it is |
|---|---|
| `my_config/phrases_fa.json` | English fragment -> Persian. **Currently holds one entry**, `"%": "درصد"` - everything else was deliberately cut, so the system voice reads it. |
| `my_config/pronounce_fa.txt` | How to spell a word for the engine when the plain spelling comes out wrong. Currently one line: `صفر = سِفْرْ`. |
| `tts_cache/fa/` | 1249 Persian clips (1113 numbers + 136 phrases from before the dictionary was cut). |
| `tts_cache/en/` | 10 instrument names, in English. |
| `tts_cache/sys/` | 8 English fragments rendered by the Windows voice, from the mixing experiment. |
| `tts_cache/samples/` | Every listening test, wav and mp3. Kept on purpose - they are the evidence behind the decisions below. |
| `tts_cache/vocab_*.txt` | The clip lists the builder works from. `vocab_fa.txt` is written by a Java dumper so there is one source of truth for what a clip is called. |

`tts_cache/` is gitignored - 63 MB, and reproducible in under two minutes.

---

## What was measured

Numbers here are from actual runs, not estimates.

**Persian is not in Grok's language list** (auto, en, ar-EG/SA/AE, bn, zh, fr, de, hi, id, it, ja, ko,
pt-BR/PT, ru, es-MX/ES, tr, vi). With `language: "auto"` it produces Persian anyway, and the quality
was judged good. That is the whole feasibility story: the Persian vocabulary is static, so it never
needs the API at run time - it is generated once by whatever can do it.

**Speed.** The direct xAI endpoint has a real `speed` parameter, 0.7 to 1.5. On Replicate there is no
such parameter, only inline tags. Measured on the same sentence:

| | duration |
|---|---|
| Replicate, no tag | 5.67 s (mean of 4) |
| Replicate, `<fast>` tag | 5.01 s (mean of 4) |
| xAI direct, `speed: 1.5` | **3.66 s** |

`<very-fast>` and `<quick>` are not real tags - they landed inside run-to-run variance.

**Trimming silence matters more than the speed setting.** Every clip comes back with about 0.25 s of
trailing silence. Across five clips played back to back: 7.00 s raw, 5.47 s trimmed. The builder
trims on the way in.

**44.1 kHz is pure upsampling.** Asked for 44100 instead of 24000, the energy above 12 kHz is
`0.000%` - the model synthesises at 24 kHz and the larger file carries exactly the same information,
at twice the size. Verified with a band-by-band FFT, and the method was sanity-checked against the
natural rolloff in the lower bands. **Stay at 24 kHz.**

**Voice.** `eve` at `speed: 1.5`, `language: auto`, 24 kHz WAV. `carina` also works on the direct
endpoint even though it is not in Replicate's enum; it was compared and `eve` was preferred.

---

## The mixing problem, and how it was solved

Once the dictionary was cut to a single entry, almost every announcement became a mix - Persian
numbers from the cache, English words with no translation:

```
"Open positions: 3"  ->  [English] + [cached Persian]
```

Sending the whole line to the system voice whenever one English word appears would mean the 1249
Persian clips are never used. So the two have to interleave inside one sentence.

They can, cleanly: **the Windows voice can render to a WAV file in exactly the format the xAI clips
use** - 1 channel, 16-bit, 24 kHz. So the PCM from both engines is simply butted together, with
nothing to convert.

Measured: 79 ms for the first fragment, then about 5 ms each. Those renders cache too, so a fragment
costs 5 ms once and nothing ever again.

| Announcement | Clips | Duration |
|---|---|---|
| `Open positions: 3` | 1 Persian + 1 system | 0.91 s |
| `Risk 25%, quantity 1400` | 4 Persian + 2 system | 2.54 s |
| a full open-position line | 12 Persian + 6 system | 10.63 s |

Samples are in `tts_cache/samples/mix01..mix03`.

**Consequence worth noting:** with this design there is no API call at run time at all - not xAI, not
Replicate. The Replicate token is unused by anything that would ship.

---

## Problems found and fixed in the tokenizer

These were all found by assembling real announcements and listening, not by reading code.

1. **Instrument names were being shredded.** `US30`, `USA500.IDX/USD` and `S and P 500` carry digits,
   and those digits were read as Persian numbers. Names are now matched first, longest first, and
   emitted whole in English.
2. **Unrelated fragments merged.** Boundaries were only at numbers, so `x, EUR/USD, open price:`
   became one lookup that could never match. Text runs are now split on punctuation.
3. **The percent sign was silently dropped.** `5%` read as "five". A percent is now always spoken,
   mapped or not - five and five percent are not the same number.
4. **Order labels broke the phrase before them.** `B166` left `reverse guard set on B` as the
   fragment to look up. Labels are now recognised as a unit and split into letter and number.
5. **Phrases containing digits were torn apart.** `by pressing F2` was looked up as `by pressing f`.
   Dictionary keys with digits in them are now matched whole, before numbers.

---

## To pick this up again

```bash
# rebuild the whole cache (about 1.5 minutes, needs XAI_API_KEY in the environment)
python tools/build_tts_cache.py tts_cache/vocab_fa.txt --workers 8

# after editing a translation, rebuild only what changed
python tools/check_phrases.py my_config/phrases_fa.json <fragments> tts_cache/vocab_phrases.txt
python tools/build_tts_cache.py tts_cache/vocab_phrases.txt

# after editing a pronunciation
python tools/build_tts_cache.py tts_cache/vocab_fa.txt --force
```

### What is still unbuilt

- **The Java player.** Load the clips, concatenate the PCM, write it to one `SourceDataLine`. A new
  utterance cancels the line and starts the next, which is how every announcement in this program
  already behaves.
- **Rendering missing fragments through the existing `Speaker` host.** The host would need a command
  that renders to a file instead of the speaker; the protocol already has room for it.
- **A key to turn it on.** `f` is free and was the plan. It should default to off.

### Open questions

- Does the system voice at `Rate = 3` sit well beside Persian at `speed: 1.5`, or does one drag?
- `صفر` alone now says `سِفْرْ`, but the same word inside a group (`صفر چهل و پنج` for 045, and about
  a thousand others) still uses the plain spelling. Whether those need the same treatment is a
  listening question that was never settled.
- 135 clips in `tts_cache/fa/` are orphaned - translations that were removed from the dictionary.
  6 MB, kept deliberately so that restoring a translation needs no rebuild.

---

## Also finished today, and shipped

Two unrelated pieces of work, both wired in and working:

- **F10 and F11 merged into one dialog.** F10 opens a spoken menu; enter opens a screen-reader
  accessible window with a focusable description, the parameters as combo boxes, and a button that
  arms it. Every percentage the two guards used to hard-code is now a setting, snapshotted onto the
  guard when it is armed so the tick thread never reads a mutable field. F11 stays as a shortcut
  straight to the reverse guard. See `src/trader/ActionDialog.java`.
- **Speech latency fixed.** The TTS library launched a fresh PowerShell per utterance - measured at
  3.0 s on this machine, of which only 0.2 s was the speech work. `src/trader/Speaker.java` keeps one
  host alive and writes a line down a pipe: **0 ms per utterance**, with a restart on death and a
  fallback to the old path so the program can never go mute.
