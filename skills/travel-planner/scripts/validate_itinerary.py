#!/usr/bin/env python3
"""Deterministic validator for travel-planner itinerary artifacts.

Usage:
  python3 validate_itinerary.py ARCHIVE_OR_WORKSPACE [ANOTHER...]

Each argument may be a run archive, a sub-agent workspace, or a directory that
contains ``subagents/<child>/data/itinerary.json``. The script exits 0 when all
discovered artifacts pass, and exits 1 after printing machine-readable defects.
"""

from __future__ import annotations

import json
import re
import sys
from datetime import date
from pathlib import Path
from typing import Any

MAX_GAP_MINUTES = 10
MAX_ROUNDING_MINUTES = 10
MAX_DEFECTS = 120
TIME_RE = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")
DISTANCE_RE = re.compile(r"^([0-9]+(?:\.[0-9]+)?)\s*(km|m)$")
DURATION_RE = re.compile(
    r"^P(?!$)(?:\d+(?:\.\d+)?Y)?(?:\d+(?:\.\d+)?M)?(?:\d+(?:\.\d+)?W)?"
    r"(?:\d+(?:\.\d+)?D)?(?:T(?!$)(?:\d+(?:\.\d+)?H)?"
    r"(?:\d+(?:\.\d+)?M)?(?:\d+(?:\.\d+)?S)?)?$",
    re.IGNORECASE,
)


def artifact_directories(root: Path) -> list[Path]:
    dirs = [root]
    subagents = root / "subagents"
    if subagents.is_dir():
        dirs.extend(sorted(p for p in subagents.iterdir() if p.is_dir()))
    return dirs


def artifacts(roots: list[Path]) -> list[Path]:
    found: list[Path] = []
    for root in roots:
        for directory in artifact_directories(root):
            candidate = directory / "data" / "itinerary.json"
            if candidate.is_file() and candidate not in found:
                found.append(candidate)
    return found


def parse_minutes(value: Any) -> int | None:
    if not isinstance(value, str):
        return None
    match = TIME_RE.fullmatch(value.strip())
    return int(match.group(1)) * 60 + int(match.group(2)) if match else None


def parse_duration(value: Any) -> int | None:
    if not isinstance(value, str) or not DURATION_RE.fullmatch(value.strip()):
        return None
    # Travel-planner artifacts use time-only durations in practice. Parse that
    # strict subset so P1M cannot be silently interpreted as 1 minute.
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", value.strip(), re.I)
    if not match:
        return None
    hours, minutes, seconds = (int(x) if x else 0 for x in match.groups())
    if seconds and seconds % 60:
        return None
    return hours * 60 + minutes + seconds // 60


def parse_date(value: Any) -> date | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError("date must be YYYY-MM-DD or null")
    return date.fromisoformat(value.strip())


def text(node: dict[str, Any], key: str) -> str:
    value = node.get(key)
    return value.strip() if isinstance(value, str) else ""


def check_nonnegative(value: Any, path: str, defects: list[str]) -> None:
    if isinstance(value, bool):
        return
    if isinstance(value, (int, float)) and value < 0:
        defects.append(f"{path} contains negative cost {value}")
    elif isinstance(value, dict):
        for key, child in value.items():
            check_nonnegative(child, f"{path}.{key}", defects)
    elif isinstance(value, list):
        for i, child in enumerate(value):
            check_nonnegative(child, f"{path}[{i}]", defects)


def validate_event(
    artifact: Path,
    day_id: str,
    event: dict[str, Any],
    event_id: str,
    defects: list[str],
) -> tuple[int | None, int | None]:
    label = f"{artifact}:{day_id}/{event_id}"
    start = parse_minutes(event.get("start"))
    end = parse_minutes(event.get("end"))
    if start is None:
        defects.append(f"{label}.start must be HH:mm")
    if end is None:
        defects.append(f"{label}.end must be HH:mm")
    if start is not None and end is not None and end <= start:
        defects.append(f"{label}: end must be later than start")

    duration = event.get("duration")
    if duration is not None and parse_duration(duration) is None:
        defects.append(f"{label}.duration is not strict ISO-8601: {duration!r}")

    check_nonnegative(event.get("cost"), f"{label}.cost", defects)
    route = event.get("route")
    if not isinstance(route, dict):
        return start, end

    route_duration = route.get("duration")
    route_minutes = parse_duration(route_duration)
    if route_duration is not None and route_minutes is None:
        defects.append(f"{label}.route.duration is not strict ISO-8601: {route_duration!r}")
    distance = text(route, "distance")
    if distance and not DISTANCE_RE.fullmatch(distance):
        defects.append(
            f"{label}.route.distance must be numeric value plus km/m; put approximation in measurement/note: {distance!r}"
        )

    if event.get("event_type") != "transport" or route_minutes is None:
        return start, end
    if start is None or end is None:
        return start, end
    wall = end - start
    if route_minutes > wall:
        defects.append(
            f"{label}: route.duration {route_minutes}min exceeds event wall time {wall}min"
        )
    elif wall - route_minutes > MAX_ROUNDING_MINUTES:
        expected = wall - route_minutes
        actual = event.get("non_travel_duration_minutes")
        if not isinstance(actual, int) or isinstance(actual, bool) or actual != expected:
            defects.append(
                f"{label}: wall time exceeds route.duration by {expected}min; set non_travel_duration_minutes={expected}"
            )
    return start, end


def validate_day(
    artifact: Path,
    day: dict[str, Any],
    global_event_ids: set[str],
    defects: list[str],
) -> None:
    day_id = text(day, "day_id") or "<missing-day-id>"
    events = day.get("events")
    if not isinstance(events, list) or not events:
        defects.append(f"{artifact}:{day_id}: events must be a non-empty array")
        return

    previous_end: int | None = None
    previous_id = ""
    for index, raw in enumerate(events):
        if not isinstance(raw, dict):
            defects.append(f"{artifact}:{day_id}: event[{index}] must be an object")
            continue
        event_id = text(raw, "event_id") or f"event[{index}]"
        canonical = f"{day_id}/{event_id}"
        if canonical in global_event_ids:
            defects.append(f"{artifact}:{day_id}: duplicate event_id {event_id}")
        global_event_ids.add(canonical)
        start, end = validate_event(artifact, day_id, raw, event_id, defects)
        if start is not None and previous_end is not None:
            if start < previous_end:
                defects.append(
                    f"{artifact}:{day_id}/{event_id}: overlaps {previous_id} ({previous_end}->{start})"
                )
            elif start - previous_end > MAX_GAP_MINUTES:
                defects.append(
                    f"{artifact}:{day_id}/{event_id}: {start - previous_end}min unmodeled gap after {previous_id}"
                )
        if end is not None:
            previous_end = end
        previous_id = event_id


def validate_dates(artifact: Path, root: dict[str, Any], defects: list[str]) -> None:
    trip = root.get("trip")
    if not isinstance(trip, dict):
        defects.append(f"{artifact}: trip must be an object")
        return
    try:
        start = parse_date(trip.get("start_date"))
        end = parse_date(trip.get("end_date"))
    except ValueError as exc:
        defects.append(f"{artifact}.trip: {exc}")
        return
    if start and end and end < start:
        defects.append(f"{artifact}.trip: end_date precedes start_date")
    days = root.get("days")
    if not isinstance(days, list) or not days:
        return
    try:
        first = parse_date(days[0].get("date")) if isinstance(days[0], dict) else None
        last = parse_date(days[-1].get("date")) if isinstance(days[-1], dict) else None
    except ValueError as exc:
        defects.append(f"{artifact}: {exc}")
        return
    if start and first and start != first:
        defects.append(f"{artifact}: D1.date differs from trip.start_date")
    if end and last and end != last:
        defects.append(f"{artifact}: last day date differs from trip.end_date")


def validate_artifact(artifact: Path, defects: list[str]) -> None:
    try:
        root = json.loads(artifact.read_text(encoding="utf-8"))
    except Exception as exc:
        defects.append(f"{artifact}: JSON parse failed: {exc}")
        return
    if not isinstance(root, dict):
        defects.append(f"{artifact}: root must be an object")
        return
    if root.get("schema_version") != 1:
        defects.append(f"{artifact}: schema_version must be 1")
    validate_dates(artifact, root, defects)
    days = root.get("days")
    if not isinstance(days, list) or not days:
        defects.append(f"{artifact}: days must be a non-empty array")
        return
    day_ids: set[str] = set()
    event_ids: set[str] = set()
    previous_date: date | None = None
    for raw in days:
        if not isinstance(raw, dict):
            defects.append(f"{artifact}: each day must be an object")
            continue
        day_id = text(raw, "day_id")
        if not day_id:
            defects.append(f"{artifact}: day_id cannot be blank")
        elif day_id in day_ids:
            defects.append(f"{artifact}: duplicate day_id {day_id}")
        else:
            day_ids.add(day_id)
        try:
            current = parse_date(raw.get("date"))
        except ValueError as exc:
            defects.append(f"{artifact}:{day_id}: {exc}")
            current = None
        if previous_date and current and current <= previous_date:
            defects.append(f"{artifact}:{day_id}.date must strictly increase")
        if current:
            previous_date = current
        validate_day(artifact, raw, event_ids, defects)


def main(argv: list[str]) -> int:
    roots = [Path(arg).expanduser().resolve() for arg in argv]
    paths = artifacts(roots)
    if not paths:
        print("FAIL: no itinerary.json found under data/ or subagents/*/data/")
        return 1
    defects: list[str] = []
    for artifact in paths:
        validate_artifact(artifact, defects)
    if not defects:
        print(f"PASS: {len(paths)} itinerary artifact(s) validated")
        return 0
    print(f"FAIL: {len(defects)} itinerary artifact defect(s)")
    for index, defect in enumerate(defects[:MAX_DEFECTS], 1):
        print(f"{index}. {defect}")
    if len(defects) > MAX_DEFECTS:
        print(f"... {len(defects) - MAX_DEFECTS} more defect(s) omitted")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
