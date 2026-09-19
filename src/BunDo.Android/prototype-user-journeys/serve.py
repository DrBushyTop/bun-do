#!/usr/bin/env python3
"""Serve the throwaway study only. Never serves the repository or backend."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path

if os.environ.get('NODE_ENV') == 'production':
    raise SystemExit('This study is development-only.')
parser = argparse.ArgumentParser()
parser.add_argument('--bind', default='127.0.0.1')
parser.add_argument('--port', default=8768, type=int)
args = parser.parse_args()
root = Path(__file__).resolve().parent
class StudyHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store')
        super().end_headers()
    def list_directory(self, path):
        self.send_error(404)
        return None
with ThreadingHTTPServer((args.bind, args.port), partial(StudyHandler, directory=str(root))) as server:
    print(f'Study at http://{args.bind}:{args.port}. Ctrl-C stops it.', flush=True)
    server.serve_forever()
