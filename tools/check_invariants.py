#!/usr/bin/env python3
"""Small repository boundary checks. No dependencies, network, or generated code changes."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAIN = Path("src/BunDo.Android/app/src/main")


@dataclass(frozen=True)
class Finding:
    rule: str
    path: Path
    message: str

    def __str__(self) -> str:
        return f"{self.path}: {self.rule}: {self.message}"


def code_only(text: str) -> str:
    """Blank Kotlin/C# comments and strings, keeping newlines and token boundaries.

    This is a lexical check, not a compiler or security scanner. It catches ordinary
    references and escape hatches, including fully qualified names and import aliases.
    """
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda match: re.sub(r"[^\n]", " ", match.group()), text)


def check_domain(root: Path) -> list[Finding]:
    result = []
    project = Path("src/BunDo.Domain/BunDo.Domain.csproj")
    # Root build props/targets can inject references into the domain project too.
    for path in (project, Path("Directory.Build.props"), Path("Directory.Build.targets")):
        if not (root / path).exists():
            continue
        tree = ET.parse(root / path)
        for element in tree.iter():
            name = element.tag.rsplit("}", 1)[-1]
            if name in {"PackageReference", "ProjectReference", "FrameworkReference", "Reference", "Import"}:
                result.append(Finding(
                    "ARCH001", path,
                    f"{name} can couple the domain to infrastructure. Keep the domain BCL-only; "
                    "put adapters and their references in Functions or another outer project.",
                ))
    return result


def check_android_code(root: Path) -> list[Finding]:
    result = []
    for source in sorted((root / MAIN / "java").rglob("*.kt")):
        path = source.relative_to(root)
        code = code_only(source.read_text())
        if "ui" in path.parts:
            if re.search(r"\b(?:InboxDatabase|InboxDao)\b|\bandroidx\s*\.\s*(?:room|sqlite)\b", code):
                result.append(Finding(
                    "ARCH002", path,
                    "UI must use InboxRepository and data values, not Room/SQLite or its DAO. "
                    "Keep database transactions in data/.",
                ))
        if "data" in path.parts and re.search(
            r"\bandroidx\s*\.\s*(?:compose|activity|fragment|navigation)\b|\bfi\s*\.\s*bundo\s*\.\s*ui\b",
            code,
        ):
            result.append(Finding(
                "ARCH003", path,
                "Persistence must not depend on screens or UI frameworks. Return data values or Flow.",
            ))
        if re.search(r"\b(?:fallbackToDestructiveMigration\w*|allowMainThreadQueries)\s*\(", code):
            result.append(Finding(
                "DATA001", path,
                "Do not discard offline work or allow main-thread database access. "
                "Add an explicit Room migration and test the old schema with pending drafts.",
            ))
    return result


def check_server_storage(root: Path) -> list[Finding]:
    result = []
    for source in sorted((root / "src").rglob("*.cs")):
        path = source.relative_to(root)
        if {"obj", "bin"} & set(path.parts):
            continue
        if path.is_relative_to("src/BunDo.Functions/Storage/Cosmos"):
            continue
        code = code_only(source.read_text(encoding="utf-8-sig"))
        if re.search(r"\bMicrosoft\s*\.\s*Azure\s*\.\s*(?:Cosmos|Documents)\b", code):
            result.append(Finding(
                "ARCH004", path,
                "Keep Cosmos SDK references in BunDo.Functions/Storage/Cosmos. "
                "Call the workspace storage interface; do not expose provider types to task rules or HTTP handlers.",
            ))
    return result


def check_backup(root: Path) -> list[Finding]:
    path = MAIN / "AndroidManifest.xml"
    if not (root / path).exists():
        return []
    application = ET.parse(root / path).find("application")
    if application is None:
        return [Finding("PRIV001", path, "Manifest needs an application with explicit backup exclusions.")]
    result = []
    if application.get(ANDROID + "allowBackup") != "false":
        result.append(Finding("PRIV001", path, "Set allowBackup=false; local drafts and future registrations must not transfer."))
    policies = (
        ("fullBackupContent", "full-backup-content", ("",), {"root", "database", "sharedpref", "file", "external"}),
        ("dataExtractionRules", "data-extraction-rules", ("cloud-backup", "device-transfer"), {
            "root", "database", "sharedpref", "file", "external",
            "device_root", "device_database", "device_sharedpref", "device_file",
        }),
    )
    for attribute, expected_tag, sections, required in policies:
        value = application.get(ANDROID + attribute, "")
        if not re.fullmatch(r"@xml/[a-z0-9_]+", value):
            result.append(Finding("PRIV001", path, f"Set {attribute} to a checked-in XML exclusion policy."))
            continue
        policy = MAIN / "res/xml" / f"{value.removeprefix('@xml/')}.xml"
        if not (root / policy).is_file():
            result.append(Finding("PRIV001", policy, "Backup policy is missing."))
            continue
        tree = ET.parse(root / policy).getroot()
        if tree.tag != expected_tag:
            result.append(Finding("PRIV001", policy, f"Expected {expected_tag} policy."))
            continue
        for section in sections:
            parent = tree.find(section) if section else tree
            excluded = set() if parent is None else {
                item.get("domain") for item in parent.findall("exclude") if item.get("path") == "."
            }
            missing = required - excluded
            if missing:
                result.append(Finding(
                    "PRIV001", policy,
                    f"{section or expected_tag} must exclude path='.' for {', '.join(sorted(missing))}.",
                ))
    return result


def placeholders(text: str) -> Counter:
    # Ignore %% and %n. Preserve argument positions and conversion kinds.
    tokens = re.finditer(r"%(?:([1-9]\d*)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])", text)
    result: Counter = Counter()
    implicit_index = 0
    for token in tokens:
        position, kind = token.groups()
        if kind in {"%", "n"}:
            continue
        if position is None:
            implicit_index += 1
            position = str(implicit_index)
        result[(int(position), kind)] += 1
    return result


def resources(root: Path, directory: Path) -> tuple[dict, list[Finding]]:
    entries = {}
    result = []
    for file in sorted((root / directory).glob("*.xml")):
        for element in ET.parse(file).getroot():
            if element.tag not in {"string", "plurals", "string-array"}:
                continue
            name = element.get("name")
            if name in entries:
                result.append(Finding("I18N001", file.relative_to(root), f"Duplicate string resource {name}."))
            entries[name] = (element, file.relative_to(root))
    return entries, result


def check_locales(root: Path) -> list[Finding]:
    base, result = resources(root, MAIN / "res/values")
    english, duplicates = resources(root, MAIN / "res/values-en")
    result += duplicates
    translated = {key for key, (element, _) in base.items() if element.get("translatable") != "false"}
    for key in sorted(translated - english.keys()):
        result.append(Finding("I18N001", base[key][1], f"Add an English resource for {key}; Finnish is the default."))
    for key in sorted(english.keys() - base.keys()):
        result.append(Finding("I18N001", english[key][1], f"Add the Finnish default for {key}."))
    for key in sorted(translated & english.keys()):
        source, _ = base[key]
        target, path = english[key]
        if source.tag != target.tag:
            result.append(Finding("I18N001", path, f"{key} must have the same resource type in both languages."))
            continue
        if source.tag == "string":
            pairs = [(source, target)]
        elif source.tag == "plurals":
            sources = {item.get("quantity"): item for item in source}
            targets = {item.get("quantity"): item for item in target}
            if sources.keys() != targets.keys() or not {"one", "other"} <= sources.keys():
                result.append(Finding("I18N001", path, f"{key} needs matching Finnish/English plural quantities, including one and other."))
            pairs = [(sources[q], targets[q]) for q in sources.keys() & targets.keys()]
        else:
            if len(source) != len(target):
                result.append(Finding("I18N001", path, f"{key} needs the same array entries in both languages."))
            pairs = list(zip(source, target))
        for left, right in pairs:
            if left.get("formatted") == "false" and right.get("formatted") == "false":
                continue
            if placeholders("".join(left.itertext())) != placeholders("".join(right.itertext())):
                result.append(Finding("I18N001", path, f"{key} changed formatting arguments. Preserve their positions and types."))
    return result


def check_schemas(
    root: Path, *, compare_git: bool, git_root: Path | None = None, git_base: str = "HEAD",
) -> list[Finding]:
    result = []
    directory = Path("src/BunDo.Android/app/schemas")
    for file in sorted((root / directory).rglob("*.json")):
        path = file.relative_to(root)
        schema = json.loads(file.read_text())
        if str(schema.get("database", {}).get("version")) != file.stem:
            result.append(Finding("DATA002", path, "Room schema version must match its filename. Regenerate through KSP."))
    if compare_git and git_base:
        # Compare committed snapshots, including deleted ones. New versions are allowed.
        repository = git_root or root
        baseline = subprocess.run(
            ["git", "rev-parse", "--verify", f"{git_base}^{{tree}}"],
            cwd=repository, text=True, capture_output=True, check=True,
        ).stdout.strip()
        tracked = subprocess.run(
            ["git", "ls-tree", "-r", "--name-only", baseline, "--", str(directory)],
            cwd=repository, text=True, capture_output=True, check=True,
        ).stdout.splitlines()
        for name in tracked:
            if not name.endswith(".json"):
                continue
            before = subprocess.run(["git", "show", f"{baseline}:{name}"], cwd=repository, capture_output=True, check=True).stdout
            if not (root / name).exists() or (root / name).read_bytes() != before:
                result.append(Finding(
                    "DATA002", Path(name),
                    "Committed Room snapshots are immutable. Restore this file, bump the database version, "
                    "generate the next schema, and add a migration test.",
                ))
    return result


def check(
    root: Path, *, compare_git: bool = True, git_root: Path | None = None, git_base: str = "HEAD",
) -> list[Finding]:
    return (
        check_domain(root) + check_android_code(root) + check_server_storage(root) + check_backup(root)
        + check_locales(root) + check_schemas(root, compare_git=compare_git, git_root=git_root, git_base=git_base)
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--git-root", type=Path, help="Original repository when --root is a staged snapshot.")
    parser.add_argument("--git-base", default="HEAD", help="Immutable schema baseline; empty disables it only for a new history.")
    args = parser.parse_args()
    try:
        findings = check(args.root.resolve(), git_root=args.git_root, git_base=args.git_base)
    except (OSError, ET.ParseError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        print(f"Invariant check could not complete: {error}", file=sys.stderr)
        return 2
    for finding in findings:
        print(finding)
    if findings:
        print(f"{len(findings)} violation(s). See docs/agents/invariants.md.")
        return 1
    print("Repository invariants passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
