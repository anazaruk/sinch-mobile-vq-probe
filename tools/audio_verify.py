#!/usr/bin/env python3
"""PCM16 WAV measurements and marker comparison; never a digital-route/MOS verdict."""
from __future__ import annotations

import argparse
import array
import csv
import hashlib
import json
import math
from pathlib import Path
import shutil
import sys
import wave

MAX_PCM_BYTES = 64 * 1024 * 1024


def dbfs(value):
    return None if value <= 0 else 20 * math.log10(value)


def goertzel_ratio(samples, frequency, rate):
    """Exact-frequency projection energy / total energy, clipped for finite windows."""
    energy = sum(x * x for x in samples)
    if not samples or energy == 0:
        return 0.0
    coefficient = 2 * math.cos(2 * math.pi * frequency / rate)
    first = second = 0.0
    for sample in samples:
        current = sample + coefficient * first - second
        second, first = first, current
    power = max(0.0, first * first + second * second - coefficient * first * second)
    return min(1.0, 2 * power / (len(samples) * energy))


def analyze_file(path, tones=(), silence_dbfs=-50.0, window_ms=20):
    path = Path(path)
    if not math.isfinite(silence_dbfs) or not -120 <= silence_dbfs <= 0:
        raise ValueError("silence_dbfs must be finite and between -120 and 0")
    if not 5 <= window_ms <= 1000:
        raise ValueError("window_ms must be between 5 and 1000")
    tones = tuple(dict.fromkeys(float(f) for f in tones))
    if len(tones) > 16:
        raise ValueError("at most 16 target frequencies")
    try:
        with wave.open(str(path), "rb") as reader:
            channels, width, rate, declared, compression, _ = reader.getparams()
            if width != 2 or channels not in (1, 2) or compression != "NONE":
                raise ValueError("only uncompressed PCM16 mono/stereo WAV is supported")
            if rate <= 0 or declared <= 0 or declared * channels * width > MAX_PCM_BYTES:
                raise ValueError("empty/oversized WAV or invalid sample rate")
            if any(not math.isfinite(f) or not 0 < f < rate / 2 for f in tones):
                raise ValueError("tone frequencies must be finite and below Nyquist")
            chunk = max(1, round(rate * window_ms / 1000))
            threshold = 10 ** (silence_dbfs / 20)
            totals = [{"energy": 0.0, "sum": 0, "peak": 0, "clips": 0, "silent_frames": 0,
                       "tones": {f: {"present_frames": 0, "weighted_ratio": 0.0} for f in tones}}
                      for _ in range(channels)]
            envelopes = [[] for _ in range(channels)]
            frames = 0
            while True:
                data = reader.readframes(chunk)
                if not data:
                    break
                if len(data) % (width * channels):
                    raise ValueError("truncated/incomplete PCM frame")
                pcm = array.array("h", data)
                if sys.byteorder != "little":
                    pcm.byteswap()
                count = len(pcm) // channels
                if frames + count > declared:
                    raise ValueError("PCM exceeds declared frame count")
                for channel in range(channels):
                    values = pcm[channel::channels]
                    stat = totals[channel]
                    energy = sum(v * v for v in values)
                    rms = math.sqrt(energy / count) / 32768
                    peak = max(abs(v) for v in values)
                    stat["energy"] += energy
                    stat["sum"] += sum(values)
                    stat["peak"] = max(stat["peak"], peak)
                    stat["clips"] += sum(v <= -32768 or v >= 32767 for v in values)
                    if rms < threshold:
                        stat["silent_frames"] += count
                    row = {"time_seconds": frames / rate, "duration_seconds": count / rate,
                           "rms": rms, "peak": peak / 32768}
                    for frequency in tones:
                        ratio = goertzel_ratio(values, frequency, rate)
                        stat["tones"][frequency]["weighted_ratio"] += ratio * count
                        present = rms >= threshold and ratio >= .20
                        if present:
                            stat["tones"][frequency]["present_frames"] += count
                        row[f"tone_{frequency:g}_energy_ratio"] = ratio
                    envelopes[channel].append(row)
                frames += count
            if frames != declared:
                raise ValueError(f"truncated WAV: header declares {declared} frames, read {frames}")
    except (wave.Error, EOFError) as exc:
        raise ValueError(f"invalid WAV: {exc}") from exc
    with path.open("rb") as handle:
        sha = hashlib.file_digest(handle, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else _sha(handle)
    result = {"path": str(path.resolve()), "sha256": sha, "size_bytes": path.stat().st_size,
              "sample_rate": rate, "channels": channels, "encoding": "PCM16_LE",
              "frames": frames, "duration_seconds": frames / rate,
              "window_ms": window_ms, "silence_threshold_dbfs": silence_dbfs,
              "silence_definition": "frame-weighted fraction of windows below RMS threshold",
              "measurements": []}
    for index, stat in enumerate(totals):
        rms = math.sqrt(stat["energy"] / frames) / 32768
        peak = stat["peak"] / 32768
        result["measurements"].append({
            "channel": index + 1, "rms": rms, "rms_dbfs": dbfs(rms), "peak": peak,
            "peak_dbfs": dbfs(peak), "dc_offset": stat["sum"] / frames / 32768,
            "clipped_sample_percent": stat["clips"] * 100 / frames,
            "silence_percent": stat["silent_frames"] * 100 / frames,
            "tones": [{"frequency_hz": f,
                       "mean_projection_energy_ratio": t["weighted_ratio"] / frames,
                       "present_window_time_percent": t["present_frames"] * 100 / frames,
                       "present": t["present_frames"] / rate >= .1,
                       "presence_rule": "ratio>=0.20 and RMS>=threshold for >=0.10 seconds total"}
                      for f, t in stat["tones"].items()],
            "envelope": envelopes[index]})
    return result


def _sha(handle):
    digest = hashlib.sha256()
    for data in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(data)
    return digest.hexdigest()


def compare_envelopes(reference, received, max_lag_seconds=5):
    """Search RMS envelopes, not raw speech; clock drift and transcoding are not corrected."""
    if not math.isfinite(max_lag_seconds) or not 0 <= max_lag_seconds <= 60:
        raise ValueError("max_lag_seconds must be between 0 and 60")
    if reference["window_ms"] != received["window_ms"]:
        raise ValueError("comparison requires equal envelope window size")
    a = [x["rms"] for x in reference["measurements"][0]["envelope"]]
    b = [x["rms"] for x in received["measurements"][0]["envelope"]]
    step = reference["window_ms"] / 1000
    maximum = round(max_lag_seconds / step)
    minimum = max(10, math.ceil(min(len(a), len(b)) * .6))
    best = None
    for lag in range(-maximum, maximum + 1):
        ai, bi = max(0, -lag), max(0, lag)
        length = min(len(a) - ai, len(b) - bi)
        if length < minimum:
            continue
        x, y = a[ai:ai + length], b[bi:bi + length]
        mx, my = sum(x) / length, sum(y) / length
        vx = sum((v - mx) ** 2 for v in x)
        vy = sum((v - my) ** 2 for v in y)
        if vx <= 1e-12 or vy <= 1e-12:
            continue
        correlation = sum((u - mx) * (v - my) for u, v in zip(x, y)) / math.sqrt(vx * vy)
        if best is None or correlation > best["correlation"]:
            best = {"correlation": max(-1.0, min(1.0, correlation)), "lag_seconds": lag * step,
                    "overlap_seconds": length * step}
    return {"method": "channel_1_RMS_envelope_Pearson_lag_search",
            "lag_convention": "positive means received marker starts later",
            "sample_rate_mismatch": reference["sample_rate"] != received["sample_rate"],
            "result": best, "unavailable_reason": "INSUFFICIENT_NONCONSTANT_OVERLAP" if best is None else None,
            "digital_path_verified": None,
            "limits": "Pattern evidence only; not waveform identity, codec/MOS, or proof of digital routing."}


def write_artifacts(report, out_dir, copy_wavs=False):
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    summary = json.loads(json.dumps(report))
    if copy_wavs:
        inputs = {name: Path(summary[name]["path"]).resolve()
                  for name in ("received", "reference") if name in summary}
        for name, original in inputs.items():
            target = (out_dir / (name + ".wav")).resolve()
            if target != original and target in inputs.values():
                raise ValueError("export destination would overwrite another input WAV")
    for label in ("received", "reference"):
        if label not in summary:
            continue
        if copy_wavs:
            source = Path(summary[label]["path"])
            target = out_dir / (label + ".wav")
            if source.resolve() != target.resolve():
                shutil.copyfile(source, target)
            summary[label]["exported_wav"] = target.name
        for channel in summary[label]["measurements"]:
            rows = channel.pop("envelope")
            path = out_dir / f"{label}-channel-{channel['channel']}-envelope.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
            channel["envelope_csv"] = path.name
    (out_dir / "audio-report.json").write_text(json.dumps(summary, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("received", help="Recorded WAV; never inferred from a reference file")
    parser.add_argument("--reference")
    parser.add_argument("--tone", type=float, action="append", default=[])
    parser.add_argument("--silence-dbfs", type=float, default=-50)
    parser.add_argument("--window-ms", type=int, default=20)
    parser.add_argument("--max-lag-seconds", type=float, default=5)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--copy-wavs", action="store_true", help="Copy original WAVs unchanged into evidence directory")
    parser.add_argument("--call-id")
    parser.add_argument("--device-id")
    args = parser.parse_args(argv)
    try:
        report = {"schema": "sinch-audio-evidence-v1", "hardware_validated": False,
                  "digital_path_verified": None,
                  "call_id": args.call_id, "device_id": args.device_id,
                  "received": analyze_file(args.received, args.tone, args.silence_dbfs, args.window_ms)}
        if args.reference:
            report["reference"] = analyze_file(args.reference, args.tone, args.silence_dbfs, args.window_ms)
            report["comparison"] = compare_envelopes(report["reference"], report["received"], args.max_lag_seconds)
        summary = write_artifacts(report, args.out_dir, args.copy_wavs)
    except (ValueError, OSError) as exc:
        parser.exit(2, f"audio verification failed: {exc}\n")
    print(json.dumps(summary, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
