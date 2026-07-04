import re
from dataclasses import dataclass


@dataclass(frozen=True)
class TextChunk:
    chunk_index: int
    start_offset: int
    end_offset: int
    text: str


@dataclass(frozen=True)
class _UnitSpan:
    start: int
    end: int


def chunk_text(
    text: str,
    max_units: int,
    overlap_units: int,
    min_units: int,
) -> list[TextChunk]:
    units = _unit_spans(text)
    if not units:
        return []

    max_units = max(1, max_units)
    overlap_units = max(0, min(overlap_units, max_units - 1))
    min_units = max(0, min(min_units, max_units))

    base_ranges = _base_ranges(text, units, max_units)
    if len(base_ranges) > 1 and _range_units(units, base_ranges[-1]) < min_units:
        previous = base_ranges[-2]
        tail = base_ranges[-1]
        base_ranges[-2] = (previous[0], tail[1])
        base_ranges.pop()

    chunks = []
    for index, (start, end) in enumerate(base_ranges):
        if overlap_units > 0 and index > 0:
            start_unit = _first_unit_at_or_after(units, start)
            start = units[max(0, start_unit - overlap_units)].start
        chunk = _normalize_chunk_text(text[start:end])
        if chunk:
            chunks.append(
                TextChunk(
                    chunk_index=len(chunks),
                    start_offset=start,
                    end_offset=end,
                    text=chunk,
                )
            )
    return chunks


def _unit_spans(text: str) -> list[_UnitSpan]:
    return [_UnitSpan(match.start(), match.end()) for match in re.finditer(r"[A-Za-z0-9_]+|[^\s]", text)]


def _natural_segments(text: str) -> list[tuple[int, int]]:
    pattern = re.compile(r".+?(?:\n\s*\n|[。！？!?]\s*|\n+|$)", re.DOTALL)
    return [
        (match.start(), match.end())
        for match in pattern.finditer(text)
        if text[match.start() : match.end()].strip()
    ]


def _base_ranges(text: str, units: list[_UnitSpan], max_units: int) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    current_start: int | None = None
    current_end: int | None = None
    current_units = 0

    for segment_start, segment_end in _natural_segments(text):
        segment_units = _range_units(units, (segment_start, segment_end))
        if segment_units == 0:
            continue
        if segment_units > max_units:
            if current_start is not None and current_end is not None:
                ranges.append((current_start, current_end))
                current_start = None
                current_end = None
                current_units = 0
            ranges.extend(_hard_split_ranges(units, segment_start, segment_end, max_units))
            continue
        if current_start is not None and current_units + segment_units > max_units:
            ranges.append((current_start, current_end or segment_start))
            current_start = None
            current_end = None
            current_units = 0
        if current_start is None:
            current_start = segment_start
        current_end = segment_end
        current_units += segment_units

    if current_start is not None and current_end is not None:
        ranges.append((current_start, current_end))
    return ranges


def _hard_split_ranges(
    units: list[_UnitSpan],
    start_offset: int,
    end_offset: int,
    max_units: int,
) -> list[tuple[int, int]]:
    relevant = [unit for unit in units if unit.start >= start_offset and unit.end <= end_offset]
    ranges = []
    for start_index in range(0, len(relevant), max_units):
        group = relevant[start_index : start_index + max_units]
        if group:
            ranges.append((group[0].start, group[-1].end))
    return ranges


def _range_units(units: list[_UnitSpan], target: tuple[int, int]) -> int:
    start, end = target
    return sum(1 for unit in units if unit.start >= start and unit.end <= end)


def _first_unit_at_or_after(units: list[_UnitSpan], offset: int) -> int:
    for index, unit in enumerate(units):
        if unit.start >= offset:
            return index
    return max(len(units) - 1, 0)


def _normalize_chunk_text(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()
