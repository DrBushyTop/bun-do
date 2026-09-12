#!/usr/bin/env python3
"""Local speech experiment. Audio, references and transcripts stay in ignored .cache."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
import unicodedata
import wave

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".cache/speech/comparison"
CORPUS = CACHE / "corpus"
RATE = 16000
CASES = {
    "fi-FI": [
        ("quantity", "Osta kaksi litraa maitoa ja kuusi kananmunaa."),
        ("negation", "Älä osta maitoa. Osta kauramaitoa."),
        ("correction", "Osta kolme, tai oikeastaan neljä sitruunaa."),
        ("date", "Varaa aika tiistaiksi kello neljätoista kolmekymmentä."),
        ("action", "Vie pahvit kierrätykseen ja kastele parvekkeen kasvit."),
        ("pause", "Siivoa eteisen kaappi. Laita talvikengät ylähyllylle. Älä heitä takkia pois."),
    ],
    "en-US": [
        ("quantity", "Buy two liters of milk and six eggs."),
        ("negation", "Do not buy milk. Buy oat milk."),
        ("correction", "Buy three, actually make that four lemons."),
        ("date", "Book an appointment for Tuesday at two thirty in the afternoon."),
        ("action", "Take the cardboard to recycling and water the balcony plants."),
        ("pause", "Clean the hallway cupboard. Put winter shoes on the top shelf. Do not throw the coat away."),
    ],
}
HUMAN = [
    "Osta kaksi litraa maitoa, ruisleipää ja kuusi kananmunaa.",
    "Vie pahvit ja lasipurkit kierrätykseen.",
    "Muista kastella parvekkeen kasvit huomenna illalla.",
    "Siivoa eteisen kaappi. Laita talvikengät ylähyllylle ja lahjoitettavat vaatteet erilliseen kassiin.",
    "Osta kolme, tai oikeastaan neljä sitruunaa.",
]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, **kwargs).stdout


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def words(text):
    return re.findall(r"[^\W_]+", unicodedata.normalize("NFKC", text).casefold())


def word_errors(reference, actual):
    expected, observed = words(reference), words(actual)
    prior = list(range(len(observed) + 1))
    for i, word in enumerate(expected):
        row = [i + 1]
        for j, value in enumerate(observed):
            row.append(min(row[-1] + 1, prior[j + 1] + 1, prior[j] + (word != value)))
        prior = row
    return prior[-1], len(expected)


def validate_cases(cases, directory=None):
    directory = directory or CORPUS
    if not 1 <= len(cases) <= 64:
        raise ValueError("Corpus must have 1..64 cases")
    ids = set()
    for case in cases:
        ident = case["id"]
        if not re.fullmatch("[a-z0-9-]+", ident) or ident in ids:
            raise ValueError("Case IDs must be unique safe filenames")
        ids.add(ident)
        path = directory / f"{ident}.pcm"
        size = path.stat().st_size
        if not 2 <= size <= RATE * 2 * 120 or size % 2:
            raise ValueError(f"Invalid bounded PCM for {ident}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != case["sha256"]:
            raise ValueError(f"Audio changed since corpus preparation: {ident}")
        if case["language"] not in CASES or not isinstance(case["private"], bool):
            raise ValueError(f"Invalid language/privacy metadata: {ident}")
    return cases


def load_cases():
    return validate_cases(json.loads((CORPUS / "cases.json").read_text()))


def select_corpus(path):
    global CORPUS
    path = path.resolve()
    if not path.is_relative_to(CACHE.resolve()):
        raise ValueError("Keep corpus audio and references under ignored .cache/speech/comparison")
    CORPUS = path


def import_manifest(args):
    if (CORPUS / "cases.json").exists():
        raise SystemExit("Corpus already exists; choose a new --corpus path rather than overwrite evidence")
    rows = [(json.loads(line), args.manifest.parent.resolve())
            for line in args.manifest.read_text().splitlines() if line.strip()]
    if args.human_dir:
        rows.extend((dict(id=f"original-fi-{i:02}", file=str((args.human_dir / f"fi-{i:02}.m4a").resolve()),
                          language="fi-FI", reference=reference), args.human_dir.resolve())
                    for i, reference in enumerate(HUMAN, 1))
    if not 1 <= len(rows) <= 64:
        raise ValueError("Corpus must have 1..64 cases")
    CORPUS.mkdir(parents=True, exist_ok=True)
    cases = []
    ids = set()
    for row, allowed_directory in rows:
        ident = row["id"]
        if not re.fullmatch("[a-z0-9-]+", ident) or ident in ids or row["language"] not in CASES:
            raise ValueError("Manifest needs unique safe IDs and fi-FI/en-US languages")
        ids.add(ident)
        source = Path(row["file"])
        if not source.is_absolute():
            source = allowed_directory / source
        source = source.resolve()
        if not source.is_relative_to(allowed_directory) or not source.is_file():
            raise ValueError("Audio must be a local file inside the manifest directory")
        pcm = CORPUS / f"{ident}.pcm"
        run("ffmpeg", "-v", "error", "-y", "-protocol_whitelist", "file,pipe",
            "-i", str(source), "-t", "121", "-ac", "1", "-ar", str(RATE), "-f", "s16le", str(pcm))
        case = dict(id=ident, language=row["language"], reference=row["reference"],
                    category="human", private=True, audioSeconds=pcm.stat().st_size / (RATE * 2),
                    sha256=hashlib.sha256(pcm.read_bytes()).hexdigest(),
                    sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest())
        for optional in ("speaker", "tags"):
            if optional in row:
                case[optional] = row[optional]
        cases.append(case)
        validate_cases(cases)
    write_json(CORPUS / "cases.json", cases)
    write_json(CORPUS / "provenance.json", dict(
        createdAt=datetime.now(timezone.utc).isoformat(),
        sourceManifestSha256=hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        ffmpeg=run("ffmpeg", "-version").decode().splitlines()[0],
        originalReferences="Five earlier requested scripts" if args.human_dir else None,
    ))
    print(f"Imported {len(cases)} private clips, {sum(c['audioSeconds'] for c in cases):.1f}s total. Originals unchanged.")


def prepare(args):
    if (CORPUS / "cases.json").exists():
        raise SystemExit("Corpus already exists. Preserve it for fair comparisons; use the existing files.")
    CORPUS.mkdir(parents=True, exist_ok=True)
    cases = []

    def add(ident, language, reference, category, private=False, source=None, speed=None):
        pcm = CORPUS / f"{ident}.pcm"
        if speed:
            source = CORPUS / f"{ident}-source.wav"
            run("espeak-ng", "-v", language[:2], "-s", str(speed), "-w", str(source), reference)
        if source:
            run("ffmpeg", "-v", "error", "-y", "-i", str(source), "-ac", "1", "-ar", str(RATE),
                "-f", "s16le", str(pcm))
        else:
            pcm.write_bytes(bytes(RATE * 2 * 3))
        with wave.open(str(CORPUS / f"{ident}.wav"), "wb") as output:
            output.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
            output.writeframes(pcm.read_bytes())
        cases.append(dict(id=ident, language=language, reference=reference, category=category,
                          private=private, speed=speed, audioSeconds=pcm.stat().st_size / (RATE * 2),
                          sha256=hashlib.sha256(pcm.read_bytes()).hexdigest()))

    for language, entries in CASES.items():
        for category, reference in entries:
            for speed in (140, 185):
                add(f"{language[:2]}-{category}-{speed}", language, reference, "synthetic", speed=speed)
    assets = ROOT / "src/BunDo.Android/app/src/androidTest/assets/speech"
    # Public test PCM is wrapped as WAV before passing through the same converter.
    for language, reference in [
        ("fi-FI", "Suurin osa valtiossa työskentelevistä puhuu italiaa myös arkikielenään, mutta uskonnollisissa toimituksissa käytetään usein latinaa."),
        ("en-US", "Remember to buy milk and bread tomorrow."),
    ]:
        source = CORPUS / f"public-{language[:2]}-source.wav"
        with wave.open(str(source), "wb") as output:
            output.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
            output.writeframes((assets / f"{language[:2]}.pcm").read_bytes())
        add(f"public-{language[:2]}", language, reference, "public", source=source)
    if args.human_dir:
        for i, reference in enumerate(HUMAN, 1):
            add(f"human-fi-{i:02}", "fi-FI", reference, "human", private=True,
                source=args.human_dir / f"fi-{i:02}.m4a")
    add("silence", "fi-FI", "", "silence")
    # Repeat selected identical waveforms, rather than averaging unrelated lengths.
    for ident in ["fi-quantity-140", "en-quantity-140"] + (["human-fi-01"] if args.human_dir else []):
        original = next(case for case in cases if case["id"] == ident)
        for repeat in (2, 3):
            clone = dict(original, id=f"{ident}-repeat{repeat}", repeatOf=ident)
            for suffix in ("pcm", "wav"):
                (CORPUS / f"{clone['id']}.{suffix}").write_bytes((CORPUS / f"{ident}.{suffix}").read_bytes())
            cases.append(clone)
    validate_cases(cases)
    write_json(CORPUS / "cases.json", cases)
    write_json(CORPUS / "provenance.json", dict(
        createdAt=datetime.now(timezone.utc).isoformat(),
        espeak=run("espeak-ng", "--version").decode().splitlines()[0],
        ffmpeg=run("ffmpeg", "-version").decode().splitlines()[0],
        referenceNote="Human references are requested reading scripts, not independently annotated recordings.",
    ))
    print(f"Prepared {len(cases)} clips, {sum(c['audioSeconds'] for c in cases):.1f}s total; all under ignored .cache.")


def device(args):
    cases = load_cases()
    adb = str(Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platform-tools/adb")

    def call(*command, **kwargs):
        return run(adb, "-s", args.serial, *command, **kwargs)

    if call("emu", "avd", "name").decode().splitlines()[0] not in {"bun-do-a", "bun-do-b"}:
        raise SystemExit("Only dedicated Bun Do emulator profiles are allowed.")
    output = CACHE / f"{args.engine}-{args.serial}-{time.time_ns()}.json"
    # Never enable networking. Leave the device offline after testing.
    call("shell", "cmd", "connectivity", "airplane-mode", "enable")
    call("shell", "svc", "wifi", "disable")
    call("shell", "svc", "data", "disable")
    call("emu", "avd", "hostmicon", "off")
    call("shell", "am", "force-stop", "fi.bundo")
    call("shell", "pm", "grant", "fi.bundo", "android.permission.RECORD_AUDIO")
    remote = "cache/speech-comparison"
    report = "cache/speech-comparison-results.json"
    try:
        call("shell", "run-as", "fi.bundo", "rm", "-rf", remote, report)
        call("shell", "run-as", "fi.bundo", "mkdir", "-p", remote)
        for case in cases:
            call("shell", f"run-as fi.bundo sh -c 'cat > {remote}/{case['id']}.pcm'",
                 input=(CORPUS / f"{case['id']}.pcm").read_bytes())
        # Reference answers never go to the recognizer.
        manifest = [dict(id=case["id"], language=case["language"]) for case in cases]
        call("shell", f"run-as fi.bundo sh -c 'cat > {remote}/manifest.json'",
             input=json.dumps(manifest).encode())
        log = call("shell", "am", "instrument", "-w", "-e", "class", "fi.bundo.SpeechComparisonDeviceTest",
                   "-e", "speechComparison", args.engine,
                   "-e", "speechCorpusSha256", hashlib.sha256((CORPUS / "cases.json").read_bytes()).hexdigest(),
                   "fi.bundo.test/androidx.test.runner.AndroidJUnitRunner",
                   timeout=3600).decode()
        raw = call("shell", "run-as", "fi.bundo", "cat", report)
        output.write_bytes(raw)
        if "OK (1 test)" not in log:
            raise RuntimeError(f"Instrumentation failed. Partial results: {output}. No transcript logs printed.")
        print(f"Experiment completed, not a quality pass: {output}")
    finally:
        call("shell", "am", "force-stop", "fi.bundo")
        call("shell", "run-as", "fi.bundo", "rm", "-rf", remote, report)


def summarize(cases, report, corpus_sha256=None):
    if corpus_sha256 is not None and report.get("corpusSha256") != corpus_sha256:
        raise ValueError("Report belongs to a different or unidentified corpus")
    by_id = {case["id"]: case for case in cases}
    seen = set()
    groups = {}
    for result in report["results"]:
        ident = result["id"]
        if ident not in by_id or ident in seen:
            raise ValueError("Unknown or duplicated result")
        seen.add(ident)
        case = by_id[ident]
        if result.get("audioSha256") != case["sha256"]:
            raise ValueError(f"Result audio does not match corpus: {ident}")
        key = f"{case['category']}/{case['language']}"
        group = groups.setdefault(key, dict(cases=0, recognized=0, errors=0, referenceWords=0,
                                           latencyMs=[], silenceHallucinations=0,
                                           latencyKind=None, sessionTotalMs=[], firstPartialMs=[]))
        group["cases"] += 1
        if result["status"] == "result":
            kind = result["latencyKind"]
            if group["latencyKind"] not in {None, kind}:
                raise ValueError("Mixed latency definitions cannot be combined")
            group["latencyKind"] = kind
            group["recognized"] += 1
            errors, count = word_errors(case["reference"], result["text"])
            group["errors"] += errors
            group["referenceWords"] += count
            group["latencyMs"].append(result["elapsedMs"])
            for metric in ("sessionTotalMs", "firstPartialMs"):
                if result.get(metric) is not None:
                    group[metric].append(result[metric])
            if case["category"] == "silence" and words(result["text"]):
                group["silenceHallucinations"] += 1
    for group in groups.values():
        times = group.pop("latencyMs")
        group["wer"] = group["errors"] / group["referenceWords"] if group["referenceWords"] else None
        group["medianMs"] = statistics.median(times) if times else None
        group["rangeMs"] = [min(times), max(times)] if times else None
        for metric in ("sessionTotalMs", "firstPartialMs"):
            values = group.pop(metric)
            if values:
                group[metric] = dict(median=statistics.median(values), range=[min(values), max(values)])
    return dict(engine=report["engine"], missingResults=sorted(set(by_id) - seen), groups=groups,
                note="WER is literal, not semantic accuracy. Number formatting counts as errors. Manually review negation, actions, dates and corrections. Repeated clips, if present, are included.")


def comparison_report(args):
    output = args.output.resolve()
    if not output.is_relative_to(CACHE.resolve()):
        raise ValueError("Reports with references/transcripts must stay under ignored .cache/speech/comparison")
    cases = load_cases()
    corpus_sha = hashlib.sha256((CORPUS / "cases.json").read_bytes()).hexdigest()
    report = dict(corpusSha256=corpus_sha, summaries=[], clips=[])
    runs = []
    for path in args.reports:
        run_report = json.loads(path.read_text())
        report["summaries"].append(dict(source=path.name, **summarize(cases, run_report, corpus_sha)))
        runs.append((path.name, run_report["engine"], {result["id"]: result for result in run_report["results"]}))
    for case in cases:
        row = dict(id=case["id"], language=case["language"], reference=case["reference"],
                   audioSeconds=case["audioSeconds"], results=[])
        for source, engine, results in runs:
            result = dict(results.get(case["id"], dict(status="missing")))
            if result["status"] == "result":
                errors, count = word_errors(case["reference"], result["text"])
                result.update(wordErrors=errors, referenceWords=count, wer=errors / count if count else None)
            row["results"].append(dict(source=source, engine=engine, **result))
        report["clips"].append(row)
    # Do not overwrite an earlier evidence artifact.
    with output.open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"Private per-clip comparison: {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=CORPUS)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("--human-dir", type=Path)
    preparation.set_defaults(function=prepare)
    imported = commands.add_parser("import-manifest")
    imported.add_argument("manifest", type=Path)
    imported.add_argument("--human-dir", type=Path, help="Include the five original fi-01.m4a..fi-05.m4a recordings.")
    imported.set_defaults(function=import_manifest)
    mobile = commands.add_parser("device")
    mobile.add_argument("serial")
    mobile.add_argument("engine", choices=["native", "parakeet"])
    mobile.set_defaults(function=device)
    score = commands.add_parser("score")
    score.add_argument("report", type=Path)
    score.set_defaults(function=lambda args: print(json.dumps(
        summarize(load_cases(), json.loads(args.report.read_text()),
                  hashlib.sha256((CORPUS / "cases.json").read_bytes()).hexdigest()), indent=2)))
    combined = commands.add_parser("report")
    combined.add_argument("reports", type=Path, nargs="+")
    combined.add_argument("--output", type=Path, required=True)
    combined.set_defaults(function=comparison_report)
    args = parser.parse_args()
    select_corpus(args.corpus)
    CACHE.mkdir(parents=True, exist_ok=True)
    # Private artifacts should not be readable by other users on the host.
    os.umask(0o077)
    args.function(args)


if __name__ == "__main__":
    main()
