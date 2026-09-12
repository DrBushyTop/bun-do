#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["websockets==15.0.1"]
# ///
"""Opt-in paid Azure speech comparison. Never invoked by tests, builds or hooks."""

import argparse
import asyncio
import base64
from datetime import datetime, timezone
import importlib.util
import io
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid
import wave

spec = importlib.util.spec_from_file_location("comparison", Path(__file__).with_name("speech-compare.py"))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


def az(*args):
    return json.loads(comparison.run("az", *args, "-o", "json"))


def multipart(fields, audio_field, audio):
    boundary = f"bundo-{uuid.uuid4().hex}"
    parts = []
    for name, value in fields:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{audio_field}"; filename="clip.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode())
    parts.extend([audio, f"\r\n--{boundary}--\r\n".encode()])
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def wav_bytes(pcm):
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setparams((1, 2, comparison.RATE, 0, "NONE", "not compressed"))
        wav.writeframes(pcm)
    return output.getvalue()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    # Audio and bearer credentials must not follow a server redirect elsewhere.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class RejectWebSocketRedirect:
    """Mixin before websockets.connect: do not resend credentials to a redirect."""
    def process_redirect(self, exc):
        return exc


def file_transcription(engine, endpoint, deployment, language, pcm, token):
    start = time.monotonic()
    if engine == "mai":
        fields = [("definition", json.dumps(dict(
            locales=[language], enhancedMode=dict(enabled=True, model="MAI-Transcribe-2",
                modelOptions=dict(transcribeStyle="verbatim", timestamps="none")),
        )))]
        path, audio_field = "/speechtotext/transcriptions:transcribe?api-version=2025-10-15", "audio"
    else:
        fields = [("model", deployment), ("languages[]", language)]
        path, audio_field = "/openai/v1/audio/transcriptions", "file"
    body, content_type = multipart(fields, audio_field, wav_bytes(pcm))
    request_id = str(uuid.uuid4())
    request = urllib.request.Request(endpoint + path, data=body, headers={
        "Authorization": f"Bearer {token}", "Content-Type": content_type,
        "x-ms-client-request-id": request_id,
    })
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=120) as response:
            payload = json.load(response)
            text = " ".join(item["text"] for item in payload.get("combinedPhrases", [])) if engine == "mai" else payload["text"]
            return dict(status="result", text=text, elapsedMs=(time.monotonic() - start) * 1000,
                        httpStatus=response.status, requestId=response.headers.get("apim-request-id", request_id),
                        usage=payload.get("usage"), latencyKind="request-to-final")
    except urllib.error.HTTPError as error:
        # Keep provider codes, not messages that might echo private content.
        try:
            payload = json.load(error)
            code = payload.get("error", {}).get("code", "unknown")
        except (ValueError, AttributeError):
            code = "unknown"
        return dict(status="error", code=code, httpStatus=error.code,
                    elapsedMs=(time.monotonic() - start) * 1000, requestId=request_id)


async def live_transcription(endpoint, deployment, language, pcm, token):
    from websockets.asyncio.client import connect

    class DirectConnection(RejectWebSocketRedirect, connect):
        pass

    # Same waveform, resampled for the realtime API's required 24 kHz PCM.
    pcm24 = comparison.run("ffmpeg", "-v", "error", "-f", "s16le", "-ar", "16000", "-ac", "1",
                           "-i", "pipe:0", "-ar", "24000", "-f", "s16le", "pipe:1", input=pcm)
    started = time.monotonic()
    url = endpoint.replace("https://", "wss://", 1) + "/openai/v1/realtime?intent=transcription"
    async with DirectConnection(url, additional_headers={"Authorization": f"Bearer {token}"},
                       open_timeout=30, close_timeout=5, max_size=2**20) as ws:
        connected = time.monotonic()
        await ws.send(json.dumps(dict(type="session.update", session=dict(
            type="transcription", audio=dict(input=dict(
                format=dict(type="audio/pcm", rate=24000),
                transcription=dict(model=deployment, languages=[language], delay="medium"),
                turn_detection=None,
            )),
        ))))
        async with asyncio.timeout(30):
            while True:
                event = json.loads(await ws.recv())
                if event["type"] == "error":
                    return dict(status="error", code=event.get("error", {}).get("code"),
                                elapsedMs=(time.monotonic() - started) * 1000)
                if event["type"] == "session.updated":
                    break
        audio_start = time.monotonic()
        committed = None
        first_delta = None

        async def send_audio():
            nonlocal committed
            chunk_size = 4800  # 100 ms, paced like a live microphone, not a fast file upload.
            for offset in range(0, len(pcm24), chunk_size):
                end = min(offset + chunk_size, len(pcm24))
                await asyncio.sleep(max(0, audio_start + end / 48000 - time.monotonic()))
                await ws.send(json.dumps(dict(type="input_audio_buffer.append",
                                             audio=base64.b64encode(pcm24[offset:end]).decode())))
            await ws.send(json.dumps(dict(type="input_audio_buffer.commit")))
            committed = time.monotonic()

        sender = asyncio.create_task(send_audio())
        try:
            async with asyncio.timeout(len(pcm24) / 48000 + 120):
                while True:
                    event = json.loads(await ws.recv())
                    kind = event["type"]
                    now = time.monotonic()
                    if kind == "conversation.item.input_audio_transcription.delta" and event.get("delta", "").strip() and first_delta is None:
                        first_delta = now
                    if kind in {"error", "conversation.item.input_audio_transcription.failed"}:
                        return dict(status="error", code=event.get("error", {}).get("code"),
                                    elapsedMs=(now - started) * 1000)
                    if kind == "conversation.item.input_audio_transcription.completed":
                        await sender
                        return dict(
                            status="result", text=event["transcript"], elapsedMs=(now - committed) * 1000,
                            latencyKind="audio-commit-to-final", sessionTotalMs=(now - started) * 1000,
                            connectionMs=(connected - started) * 1000,
                            readyMs=(audio_start - started) * 1000,
                            firstPartialMs=(first_delta - audio_start) * 1000 if first_delta else None,
                            streamedAudioMs=(committed - audio_start) * 1000,
                            usage=event.get("usage"),
                        )
        finally:
            sender.cancel()
            await asyncio.gather(sender, return_exceptions=True)


def validate_consent(cases, allow_private):
    if any(case["private"] for case in cases) and not allow_private:
        raise ValueError("Private audio requires --allow-private-audio for this run")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("engine", choices=["gpt", "live", "mai"])
    parser.add_argument("--corpus", type=Path, default=comparison.CORPUS)
    parser.add_argument("--subscription", required=True)
    parser.add_argument("--account", required=True)
    parser.add_argument("--deployment")
    parser.add_argument("--case", action="append", dest="ids", help="Optional exact corpus ID, repeatable.")
    parser.add_argument("--allow-private-audio", action="store_true")
    parser.add_argument("--execute-paid-test", action="store_true", required=True)
    args = parser.parse_args()
    comparison.select_corpus(args.corpus)
    os.umask(0o077)
    cases = comparison.load_cases()
    if args.ids:
        if set(args.ids) - {case["id"] for case in cases}:
            raise SystemExit("Unknown case ID")
        cases = [case for case in cases if case["id"] in args.ids]
    validate_consent(cases, args.allow_private_audio)
    account = az("cognitiveservices", "account", "show", "--subscription", args.subscription,
                 "-g", "rg-bun-do-dev-swc", "-n", args.account)
    expected_id = f"/subscriptions/{args.subscription}/resourceGroups/rg-bun-do-dev-swc/providers/Microsoft.CognitiveServices/accounts/{args.account}"
    if account["id"].lower() != expected_id.lower() or account["location"] not in {"swedencentral", "northeurope", "westeurope"}:
        raise SystemExit("Account is outside the authorized development group/European test regions")
    if not account["properties"].get("disableLocalAuth"):
        raise SystemExit("The experiment expects key authentication to remain disabled")
    if args.engine == "mai":
        if account["kind"] != "AIServices":
            raise SystemExit("MAI requires a Speech-enabled Foundry account")
        host_suffix, model = ".cognitiveservices.azure.com", "MAI-Transcribe-2"
    else:
        if not args.deployment or account["kind"] != "OpenAI":
            raise SystemExit("Specify an existing OpenAI test deployment")
        deployment = az("cognitiveservices", "account", "deployment", "show",
                        "--subscription", args.subscription, "-g", "rg-bun-do-dev-swc",
                        "-n", args.account, "--deployment-name", args.deployment)
        model = deployment["properties"]["model"]
        expected_model = "gpt-transcribe" if args.engine == "gpt" else "gpt-live-transcribe"
        if model["name"] != expected_model:
            raise SystemExit(f"Deployment is not the requested {expected_model}")
        host_suffix = ".openai.azure.com"
    subdomain = account["properties"]["customSubDomainName"]
    if not subdomain or not all(c.isalnum() or c == "-" for c in subdomain):
        raise SystemExit("Invalid account custom subdomain")
    endpoint = f"https://{subdomain}{host_suffix}"
    # Token stays in memory, never CLI arguments, files or diagnostics.
    token = az("account", "get-access-token", "--subscription", args.subscription,
               "--resource", "https://cognitiveservices.azure.com/")["accessToken"]
    report = dict(engine=args.engine, model=model, region=account["location"],
                  createdAt=datetime.now(timezone.utc).isoformat(),
                  corpusSha256=comparison.hashlib.sha256((comparison.CORPUS / "cases.json").read_bytes()).hexdigest(),
                  results=[], plannedCases=len(cases), complete=False)
    path = comparison.CACHE / f"{args.engine}-{time.time_ns()}.json"
    comparison.write_json(path, report)
    for case in cases:
        pcm = (comparison.CORPUS / f"{case['id']}.pcm").read_bytes()
        if comparison.hashlib.sha256(pcm).hexdigest() != case["sha256"]:
            raise SystemExit("Audio changed after corpus validation. Stop before sending it.")
        start = time.monotonic()
        try:
            if args.engine == "live":
                result = asyncio.run(live_transcription(endpoint, args.deployment, case["language"][:2], pcm, token))
            else:
                result = file_transcription(args.engine, endpoint, args.deployment, case["language"][:2], pcm, token)
        except Exception as error:
            # No retry: a network failure may follow a billed/processed request.
            result = dict(status="exception", exceptionType=type(error).__name__,
                          elapsedMs=(time.monotonic() - start) * 1000)
        result.update(id=case["id"], audioSeconds=case["audioSeconds"], audioSha256=case["sha256"])
        report["results"].append(result)
        comparison.write_json(path, report)
        print(f"{args.engine} {case['id']}: {result['status']}, {result['elapsedMs']:.0f}ms", flush=True)
        if result["status"] != "result":
            raise SystemExit(f"Stopped on failure without retry. Inspect private report: {path}")
        # Capacity 10 permits 10 requests/minute; no aggressive burst/retry loop.
        time.sleep(max(0, 6.1 - (time.monotonic() - start)))
    report["complete"] = True
    comparison.write_json(path, report)
    print(f"Completed {len(cases)} requests. Private report: {path}")


if __name__ == "__main__":
    main()
