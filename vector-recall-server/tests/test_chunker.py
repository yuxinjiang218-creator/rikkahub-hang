from chunker import chunk_text


def test_chunk_text_splits_long_text_with_overlap_and_offsets():
    text = (
        "alpha beta gamma delta epsilon zeta eta theta. "
        "python archive details live here. "
        "iota kappa lambda mu nu xi omicron pi. "
        "kotlin vector recall implementation lives here."
    )

    chunks = chunk_text(text, max_units=8, overlap_units=2, min_units=2)

    assert len(chunks) > 1
    assert chunks[0].start_offset == 0
    assert all(chunk.start_offset < chunk.end_offset for chunk in chunks)
    assert all(text[chunk.start_offset : chunk.end_offset].strip() for chunk in chunks)
    assert chunks[1].start_offset < chunks[0].end_offset
    assert any("kotlin vector recall" in chunk.text for chunk in chunks)
