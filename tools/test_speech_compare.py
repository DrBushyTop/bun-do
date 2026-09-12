"""Protect honest comparison results, bounded inputs and explicit private-audio consent."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


compare = load("speech_compare", "speech-compare.py")
cloud = load("speech_cloud_compare", "speech-cloud-compare.py")


class SpeechComparisonTests(unittest.TestCase):
    def test_word_errors_ignore_case_punctuation_but_keep_negation_and_quantities(self):
        self.assertEqual((0, 5), compare.word_errors("Älä osta kahta litraa maitoa.", "älä osta kahta litraa maitoa"))
        self.assertEqual((2, 5), compare.word_errors("Älä osta kahta litraa maitoa.", "Osta kolme litraa maitoa"))

    def test_number_formatting_is_not_silently_semantic_equivalence(self):
        self.assertEqual((1, 3), compare.word_errors("Buy two lemons", "Buy 2 lemons"))

    def test_missing_and_unsupported_results_are_not_zero_error_passes(self):
        cases = [dict(id=name, category="human", language="fi-FI", reference="Osta maitoa", sha256="audio")
                 for name in ("first", "second")]
        summary = compare.summarize(cases, dict(engine="native", results=[dict(id="first", status="unavailable", audioSha256="audio")]))
        self.assertEqual(["second"], summary["missingResults"])
        group = summary["groups"]["human/fi-FI"]
        self.assertEqual(0, group["recognized"])
        self.assertIsNone(group["wer"])
        self.assertIsNone(group["medianMs"])

    def test_duplicate_results_cannot_inflate_sample_size(self):
        case = dict(id="one", category="synthetic", language="en-US", reference="hello", sha256="audio")
        result = dict(id="one", status="result", text="hello", elapsedMs=2, audioSha256="audio", latencyKind="test")
        with self.assertRaises(ValueError):
            compare.summarize([case], dict(engine="test", results=[result, result]))

    def test_silence_hallucination_is_separate_from_undefined_wer(self):
        case = dict(id="silence", category="silence", language="fi-FI", reference="", sha256="audio")
        result = dict(id="silence", status="result", text="Kiitos", elapsedMs=4, audioSha256="audio", latencyKind="test")
        group = compare.summarize([case], dict(engine="test", results=[result]))["groups"]["silence/fi-FI"]
        self.assertEqual(1, group["silenceHallucinations"])
        self.assertIsNone(group["wer"])

    def test_changed_audio_is_rejected_before_comparison(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "one.pcm").write_bytes(b"\0\0")
            case = dict(id="one", language="fi-FI", private=True,
                        sha256=compare.hashlib.sha256(b"\0\0").hexdigest())
            compare.validate_cases([case], path)
            (path / "one.pcm").write_bytes(b"\1\0")
            with self.assertRaisesRegex(ValueError, "Audio changed"):
                compare.validate_cases([case], path)

    def test_unsafe_filename_rejected_before_read(self):
        with self.assertRaises(ValueError):
            compare.validate_cases([dict(id="../voice")])

    def test_jsonl_import_marks_audio_private_and_keeps_reference_off_device(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = path / "manifest.jsonl"
            source = path / "voice.wav"
            source.write_bytes(b"source")
            manifest.write_text(json.dumps(dict(id="fi-001", file="voice.wav", language="fi-FI",
                                               reference="Älä osta maitoa.", speaker="one", tags=["negation"])) + "\n")

            def convert(*args):
                if args[-1] == "-version":
                    return b"ffmpeg test"
                Path(args[-1]).write_bytes(b"\0\0")
                return b""

            with patch.object(compare, "CORPUS", path / "corpus"), patch.object(compare, "run", side_effect=convert):
                compare.import_manifest(SimpleNamespace(manifest=manifest, human_dir=None))
                case = compare.load_cases()[0]
                self.assertTrue(case["private"])
                self.assertEqual(["negation"], case["tags"])
                self.assertEqual(compare.hashlib.sha256(b"source").hexdigest(), case["sourceSha256"])

    def test_jsonl_import_rejects_audio_outside_manifest_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            manifest = path / "manifest.jsonl"
            manifest.write_text(json.dumps(dict(id="fi-001", file="../outside.wav", language="fi-FI", reference="test")))
            with patch.object(compare, "CORPUS", path / "corpus"), patch.object(compare, "run") as subprocess_run:
                with self.assertRaisesRegex(ValueError, "inside the manifest directory"):
                    compare.import_manifest(SimpleNamespace(manifest=manifest, human_dir=None))
                subprocess_run.assert_not_called()

    def test_explicit_original_recordings_may_live_beside_new_manifest_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fresh, original = root / "new", root / "original"
            fresh.mkdir()
            original.mkdir()
            manifest = fresh / "manifest.jsonl"
            manifest.write_text(json.dumps(dict(id="new-one", file="one.wav", language="fi-FI", reference="hello")))
            (fresh / "one.wav").write_bytes(b"new")
            for i in range(1, 6):
                (original / f"fi-{i:02}.m4a").write_bytes(b"original")

            def convert(*args):
                if args[-1] == "-version":
                    return b"ffmpeg test"
                Path(args[-1]).write_bytes(b"\0\0")
                return b""

            with patch.object(compare, "CORPUS", root / "corpus"), patch.object(compare, "run", side_effect=convert):
                compare.import_manifest(SimpleNamespace(manifest=manifest, human_dir=original))
                self.assertEqual(6, len(compare.load_cases()))

    def test_private_audio_requires_explicit_cloud_permission(self):
        cloud.validate_consent([dict(private=False)], False)
        cloud.validate_consent([dict(private=True)], True)
        with self.assertRaises(ValueError):
            cloud.validate_consent([dict(private=True)], False)

    def test_stale_corpus_or_audio_cannot_produce_a_score(self):
        case = dict(id="one", category="synthetic", language="en-US", reference="hello", sha256="new")
        result = dict(id="one", status="result", text="hello", elapsedMs=2, audioSha256="old", latencyKind="test")
        report = dict(engine="test", corpusSha256="old-corpus", results=[result])
        with self.assertRaisesRegex(ValueError, "different or unidentified corpus"):
            compare.summarize([case], report, "new-corpus")
        with self.assertRaisesRegex(ValueError, "audio does not match"):
            compare.summarize([case], report, "old-corpus")

    def test_streaming_summary_retains_both_user_visible_latency_definitions(self):
        case = dict(id="one", category="synthetic", language="en-US", reference="hello", sha256="audio")
        result = dict(id="one", status="result", text="hello", elapsedMs=1, audioSha256="audio",
                      latencyKind="audio-commit-to-final", sessionTotalMs=12000, firstPartialMs=400)
        group = compare.summarize([case], dict(engine="live", results=[result]))["groups"]["synthetic/en-US"]
        self.assertEqual("audio-commit-to-final", group["latencyKind"])
        self.assertEqual(12000, group["sessionTotalMs"]["median"])
        self.assertEqual(400, group["firstPartialMs"]["median"])
        second_case = dict(case, id="two")
        second_result = dict(result, id="two", latencyKind="request-to-final")
        with self.assertRaisesRegex(ValueError, "Mixed latency definitions"):
            compare.summarize([case, second_case], dict(engine="test", results=[result, second_result]))

    def test_websocket_redirect_policy_rejects_instead_of_returning_a_new_url(self):
        # websockets accepts a redirected URI string or raises the returned exception.
        error = RuntimeError("redirect to unapproved host")
        self.assertIs(error, cloud.RejectWebSocketRedirect().process_redirect(error))

    def test_multipart_sends_only_supplied_fields_and_audio(self):
        data, content_type = cloud.multipart([("languages[]", "fi"), ("model", "test")], "file", b"voice")
        self.assertIn("multipart/form-data", content_type)
        self.assertIn(b'name="languages[]"', data)
        self.assertIn(b'filename="clip.wav"', data)
        self.assertNotIn(b"reference", data)


if __name__ == "__main__":
    unittest.main()
