import array
import json
import math
from pathlib import Path
import tempfile
import unittest
import wave

from tools.audio_verify import analyze_file, compare_envelopes, write_artifacts
from tools.generate_test_audio import generate_bundle


class AudioToolsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def wav(self, name, samples, rate=8000, channels=1):
        import sys
        data = array.array("h", samples)
        if sys.byteorder != "little":
            data.byteswap()
        path = self.root / name
        with wave.open(str(path), "wb") as output:
            output.setparams((channels, 2, rate, 0, "NONE", "not compressed"))
            output.writeframes(data.tobytes())
        return path

    def test_silence_is_not_tone_or_correlation(self):
        path = self.wav("silent.wav", [0] * 8000)
        result = analyze_file(path, [1000])
        self.assertEqual(result["duration_seconds"], 1)
        m = result["measurements"][0]
        self.assertEqual(m["silence_percent"], 100)
        self.assertIsNone(m["rms_dbfs"])
        self.assertFalse(m["tones"][0]["present"])
        self.assertIsNone(compare_envelopes(result, result)["result"])

    def test_tone_metrics_and_reject_other_frequency(self):
        path = self.wav("tone.wav", [round(8192 * math.sin(2 * math.pi * 1000 * n / 8000)) for n in range(8000)])
        m = analyze_file(path, [1000, 1700])["measurements"][0]
        self.assertAlmostEqual(m["rms"], .25 / math.sqrt(2), places=4)
        self.assertAlmostEqual(m["peak"], .25, places=4)
        self.assertTrue(m["tones"][0]["present"])
        self.assertFalse(m["tones"][1]["present"])

    def test_stereo_does_not_cancel_antiphase(self):
        samples = []
        for n in range(8000):
            x = round(8192 * math.sin(2 * math.pi * 1000 * n / 8000))
            samples.extend([x, -x])
        result = analyze_file(self.wav("stereo.wav", samples, channels=2), [1000])
        self.assertTrue(all(c["tones"][0]["present"] for c in result["measurements"]))

    def test_truncated_wav_rejected(self):
        path = self.wav("truncated.wav", [1] * 8000)
        path.write_bytes(path.read_bytes()[:-200])
        with self.assertRaisesRegex(ValueError, "truncated"):
            analyze_file(path)

    def test_generation_deterministic_and_distinct(self):
        first = generate_bundle(self.root / "a", duration=3, run_id="one")
        second = generate_bundle(self.root / "b", duration=3, run_id="one")
        third = generate_bundle(self.root / "c", duration=3, run_id="two")
        self.assertEqual(first, second)
        self.assertNotEqual(first["files"]["tx"]["sha256"], third["files"]["tx"]["sha256"])
        self.assertNotEqual(first["files"]["tx"]["sha256"], first["files"]["rx"]["sha256"])
        self.assertFalse(first["hardware_validated"])
        for direction in ("tx", "rx"):
            analysis = analyze_file(self.root / "a" / first["files"][direction]["path"], first["files"][direction]["frequencies_hz"])
            self.assertTrue(all(t["present"] for t in analysis["measurements"][0]["tones"]))

    def test_all_supported_generation_rates(self):
        for rate in (8000, 16000, 48000):
            generate_bundle(self.root / str(rate), sample_rate=rate, duration=2)
            self.assertEqual(analyze_file(self.root / str(rate) / "tx-reference.wav")["sample_rate"], rate)

    def test_alignment_and_artifacts(self):
        generate_bundle(self.root, sample_rate=8000, duration=4)
        with wave.open(str(self.root / "tx-reference.wav"), "rb") as reader:
            original = array.array("h", reader.readframes(reader.getnframes()))
        path = self.wav("received.wav", [0] * 2400 + list(original))
        reference, received = analyze_file(self.root / "tx-reference.wav"), analyze_file(path)
        comparison = compare_envelopes(reference, received, 1)
        self.assertAlmostEqual(comparison["result"]["lag_seconds"], .3, places=5)
        self.assertGreater(comparison["result"]["correlation"], .999)
        report = {"reference": reference, "received": received, "comparison": comparison}
        summary = write_artifacts(report, self.root / "evidence", copy_wavs=True)
        self.assertTrue((self.root / "evidence" / "received-channel-1-envelope.csv").exists())
        self.assertNotIn("envelope", summary["received"]["measurements"][0])
        self.assertEqual(path.read_bytes(), (self.root / "evidence" / "received.wav").read_bytes())
        self.assertEqual(json.loads((self.root / "evidence" / "audio-report.json").read_text()), summary)

    def test_invalid_inputs(self):
        with self.assertRaises(ValueError):
            generate_bundle(self.root, sample_rate=44100)
        with self.assertRaises(ValueError):
            generate_bundle(self.root, duration=float("nan"))
        path = self.wav("valid.wav", [0] * 8000)
        with self.assertRaises(ValueError):
            analyze_file(path, [4000])
        with self.assertRaises(ValueError):
            analyze_file(path, silence_dbfs=float("nan"))

    def test_export_does_not_overwrite_cross_named_inputs(self):
        a = self.wav("received.wav", [1000] * 8000)
        b = self.wav("reference.wav", [2000] * 8000)
        before = (a.read_bytes(), b.read_bytes())
        report = {"reference": analyze_file(a), "received": analyze_file(b)}
        with self.assertRaisesRegex(ValueError, "overwrite"):
            write_artifacts(report, self.root, copy_wavs=True)
        self.assertEqual(before, (a.read_bytes(), b.read_bytes()))


if __name__ == "__main__":
    unittest.main()
