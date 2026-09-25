#!/usr/bin/env python3
"""Generate deterministic, distinguishable TX/RX PCM16 markers; no hardware validation."""
from __future__ import annotations

import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import random
import sys
import wave

RATES = (8000, 16000, 48000)
FREQUENCIES = {"tx": (733.0, 1379.0), "rx": (1091.0, 1877.0)}


def generate_bundle(out_dir, sample_rate=16000, duration=12.0, level_dbfs=-12.0,
                    run_id="sinch-phase-b-001"):
    if sample_rate not in RATES:
        raise ValueError("sample_rate must be 8000, 16000 or 48000")
    if not math.isfinite(duration) or not 2.0 <= duration <= 120.0:
        raise ValueError("duration must be finite, between 2 and 120 seconds")
    if not math.isfinite(level_dbfs) or not -40 <= level_dbfs <= -3:
        raise ValueError("level_dbfs must be between -40 and -3 (peak dBFS)")
    if not run_id or len(run_id) > 128:
        raise ValueError("run_id must contain 1..128 characters")
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": "sinch-audio-markers-v1", "purpose": "GENERATED_TEST_INPUT_ONLY",
                "hardware_validated": False, "run_id": run_id, "files": {}}
    for direction, frequencies in FREQUENCIES.items():
        rng = random.Random(int.from_bytes(hashlib.sha256((run_id + direction).encode()).digest(), "big"))
        frames = round(duration * sample_rate)
        slot_frames = sample_rate // 4
        nslots = (frames + slot_frames - 1) // slot_frames
        # Leading/trailing silence aids alignment; interior on/off/frequency code varies by run.
        slots = [None if i < 2 or i >= nslots - 2 or rng.random() < .25 else rng.choice(frequencies)
                 for i in range(nslots)]
        slots[2] = frequencies[0]
        slots[3] = frequencies[1]
        pcm = array.array("h")
        amplitude = 32767 * 10 ** (level_dbfs / 20)
        fade = max(1, round(sample_rate * .005))
        for i in range(frames):
            slot, position = divmod(i, slot_frames)
            freq = slots[slot]
            gain = min(1.0, position / fade, (slot_frames - 1 - position) / fade)
            pcm.append(0 if freq is None else round(amplitude * gain * math.sin(2 * math.pi * freq * position / sample_rate)))
        if sys.byteorder != "little":
            pcm.byteswap()
        path = out_dir / (direction + "-reference.wav")
        with wave.open(str(path), "wb") as writer:
            writer.setparams((1, 2, sample_rate, frames, "NONE", "not compressed"))
            writer.writeframes(pcm.tobytes())
        manifest["files"][direction] = {
            "path": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "sample_rate": sample_rate, "channels": 1, "encoding": "PCM16_LE",
            "frames": frames, "duration_seconds": frames / sample_rate,
            "peak_level_dbfs": level_dbfs, "frequencies_hz": list(frequencies),
            "slot_seconds": .25, "slot_frequencies_hz": slots,
        }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--sample-rate", type=int, choices=RATES, default=16000)
    parser.add_argument("--duration", type=float, default=12)
    parser.add_argument("--level-dbfs", type=float, default=-12)
    parser.add_argument("--run-id", default="sinch-phase-b-001")
    args = parser.parse_args(argv)
    try:
        result = generate_bundle(args.out_dir, args.sample_rate, args.duration, args.level_dbfs, args.run_id)
    except (ValueError, OSError) as exc:
        parser.exit(2, f"audio generation failed: {exc}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
