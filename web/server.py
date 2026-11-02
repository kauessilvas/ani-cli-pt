#!/usr/bin/env python3
"""Local web interface for ani-cli-pt. Uses only Python's standard library."""
from __future__ import annotations

import html
import json
import os
import re
import shutil
import subprocess
import threading
import uuid
import urllib.parse
import urllib.request
from functools import lru_cache
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLI = ROOT / "ani-cli"
BASE = "https://hianime.at"
AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/126 Safari/537.36"
MEDIA = {}
MEDIA_LOCK = threading.Lock()


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": AGENT, "Referer": BASE + "/"})
    with urllib.request.urlopen(req, timeout=18) as response:
        return response.read().decode("utf-8", "replace")


def urlsplit_path(url: str) -> str:
    return urllib.parse.urlparse(url).path


def to_webvtt(body: bytes) -> bytes:
    """Browsers accept WebVTT as an HTML media track, but not SRT."""
    text = body.decode("utf-8-sig", "replace").replace("\r\n", "\n").replace("\r", "\n")
    if text.lstrip().startswith("WEBVTT"):
        return text.encode("utf-8")
    if re.search(r"\d{2}:\d{2}:\d{2},\d{3}\s+-->", text):
        text = re.sub(r"(\d{2}:\d{2}:\d{2}),(\d{3})", r"\1.\2", text)
        text = re.sub(r"(?m)^\s*\d+\s*$", "", text)
        return ("WEBVTT\n\n" + text.lstrip()).encode("utf-8")
    return body


@lru_cache(maxsize=512)
def translate_text_ptbr(text: str) -> tuple[str, bool]:
    """Translate short English provider text, keeping its original on failure."""
    text = text.strip()[:500]
    if not text:
        return "", False
    query = urllib.parse.urlencode({"q": text, "langpair": "en|pt-BR"})
    request = urllib.request.Request(
        "https://api.mymemory.translated.net/get?" + query,
        headers={"User-Agent": "ani-cli-pt-local/1.0", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
        translated = html.unescape(str(payload.get("responseData", {}).get("translatedText", ""))).strip()
        if payload.get("responseStatus") == 200 and translated:
            return translated, True
    except Exception:
        pass
    return text, False


translate_synopsis_ptbr = translate_text_ptbr


class CatalogParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.items = []; self.current = None; self.depth = 0; self.capture = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "div" and "flw-item" in a.get("class", "").split():
            self.current = {"title": "", "title_attr": "", "title_text": "", "jtitle": "", "slug": "", "poster": "", "synopsis": "", "meta": []}; self.depth = 1
        elif self.current is not None:
            if tag == "div": self.depth += 1
            if tag == "a" and "dynamic-name" in a.get("class", ""):
                self.current["slug"] = urllib.parse.urlparse(a.get("href", "")).path.strip("/")
                self.current["title_attr"] = a.get("title", ""); self.current["jtitle"] = a.get("data-jname", "")
                self.capture = "title_text"
            elif tag == "img" and "film-poster-img" in a.get("class", ""):
                self.current["poster"] = a.get("data-src") or a.get("src", "")
            elif tag == "div" and "description" in a.get("class", ""):
                self.capture = "synopsis"
            elif tag == "span" and "fdi-item" in a.get("class", ""):
                self.capture = "meta"

    def handle_data(self, data):
        if self.current is not None and self.capture:
            value = " ".join(data.split())
            if value:
                key = self.capture
                if key == "meta": self.current["meta"].append(value)
                else: self.current[key] = (self.current[key] + " " + value).strip()

    def handle_endtag(self, tag):
        if self.current is None: return
        if tag == "a" and self.capture == "title_text":
            self.current["title"] = self.current["title_text"] or self.current["title_attr"]
        if tag in ("a", "div", "span"): self.capture = None
        if tag == "div":
            self.depth -= 1
            if self.depth <= 0:
                if not self.current["title"]: self.current["title"] = self.current["title_attr"]
                self.current.pop("title_attr", None); self.current.pop("title_text", None)
                if self.current["slug"] and self.current["title"]: self.items.append(self.current)
                self.current = None


def catalog_page(path):
    parser = CatalogParser(); parser.feed(fetch(BASE + path))
    # The site may contain duplicate card fragments; preserve first occurrence.
    seen = set(); result = []
    for item in parser.items:
        if item["slug"] not in seen:
            seen.add(item["slug"]); item["synopsis"] = item["synopsis"][:360]
            item["meta"] = item["meta"][:4]; result.append(item)
    return result[:36]


def catalog(query=""):
    path = "/search?keyword=" + urllib.parse.quote_plus(query) if query else "/most-popular"
    return catalog_page(path)


def seasonal_catalog():
    """Return the provider's current top-airing list for seasonal discovery."""
    return catalog_page("/top-airing")


class EpisodeParser(HTMLParser):
    def __init__(self): super().__init__(); self.items = []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "a" and "ep-item" in a.get("class", "").split() and a.get("data-number"):
            self.items.append({"number": a["data-number"], "id": a.get("data-id", ""), "title": a.get("title", "")})


class ArtworkParser(HTMLParser):
    def __init__(self):
        super().__init__(); self.banner = ""; self.poster = ""
    def handle_starttag(self, tag, attrs):
        a = dict(attrs); classes = a.get("class", "").split()
        if tag == "div" and "anis-cover" in classes:
            match = re.search(r"url\(['\"]?(.*?)['\"]?\)", a.get("style", ""))
            if match: self.banner = urllib.parse.urljoin(BASE + "/", match.group(1))
        if tag == "img" and "film-poster-img" in classes and not self.poster:
            self.poster = urllib.parse.urljoin(BASE + "/", a.get("src", ""))


def json_response(handler, status, data):
    body = json.dumps(data, ensure_ascii=False).encode()
    try:
        handler.send_response(status); handler.send_header("Content-Type", "application/json; charset=utf-8")
        handler.send_header("Cache-Control", "no-store"); handler.send_header("Content-Length", str(len(body)))
        handler.end_headers(); handler.wfile.write(body)
    except (BrokenPipeError, ConnectionResetError):
        # Browsers commonly cancel an in-flight translation when the user closes the sheet.
        return


class Handler(BaseHTTPRequestHandler):
    server_version = "ani-cli-pt-local/1.0"
    def log_message(self, fmt, *args): print("[web] " + fmt % args)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/":
            body = (ROOT / "web" / "index.html").read_bytes()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
        if parsed.path == "/api/catalog":
            q = urllib.parse.parse_qs(parsed.query).get("q", [""])[0][:120]
            try: return json_response(self, 200, {"items": catalog(q)})
            except Exception as e: return json_response(self, 502, {"error": "Não foi possível buscar o catálogo agora.", "detail": str(e)[:200]})
        if parsed.path == "/api/seasonal":
            try: return json_response(self, 200, {"items": seasonal_catalog()})
            except Exception as e: return json_response(self, 502, {"error": "Não foi possível carregar os animes em exibição agora.", "detail": str(e)[:200]})
        if parsed.path == "/api/artwork":
            slug = urllib.parse.parse_qs(parsed.query).get("slug", [""])[0]
            if not re.fullmatch(r"[a-zA-Z0-9-]+-\d+", slug): return json_response(self, 400, {"error": "Identificador de anime inválido."})
            try:
                parser = ArtworkParser(); parser.feed(fetch(f"{BASE}/{slug}"))
                return json_response(self, 200, {"banner": parser.banner, "poster": parser.poster})
            except Exception as e: return json_response(self, 502, {"error": "Não foi possível carregar a arte em alta resolução.", "detail": str(e)[:200]})
        if parsed.path == "/api/episodes":
            slug = urllib.parse.parse_qs(parsed.query).get("slug", [""])[0]
            match = re.fullmatch(r"[a-zA-Z0-9-]+-(\d+)", slug)
            if not match: return json_response(self, 400, {"error": "Identificador de anime inválido."})
            try:
                payload = json.loads(fetch(f"{BASE}/api/theme/episode/list/{match.group(1)}"))
                parser = EpisodeParser(); parser.feed(payload.get("html", ""))
                return json_response(self, 200, {"episodes": parser.items})
            except Exception as e: return json_response(self, 502, {"error": "Não foi possível carregar os episódios.", "detail": str(e)[:200]})
        if parsed.path.startswith("/media/"):
            token = parsed.path.rsplit("/", 1)[-1]
            with MEDIA_LOCK: source = MEDIA.get(token)
            if not source: return json_response(self, 404, {"error": "Mídia expirada. Abra o episódio novamente."})
            url, referer = source
            try:
                req = urllib.request.Request(url, headers={"User-Agent": AGENT, "Referer": referer, "Accept": "*/*"})
                with urllib.request.urlopen(req, timeout=25) as response:
                    body = response.read()
                    ctype = response.headers.get("Content-Type", "application/octet-stream")
                looks_like_text_track = body.lstrip().startswith(b"WEBVTT") or re.search(rb"\d{2}:\d{2}:\d{2}[,.]\d{3}\s+-->", body[:2048]) is not None
                if urlsplit_path(url).lower().endswith((".vtt", ".srt")) or "text/vtt" in ctype or "subrip" in ctype or looks_like_text_track:
                    body = to_webvtt(body)
                    ctype = "text/vtt; charset=utf-8"
                if urlsplit_path(url).lower().endswith((".m3u8", ".m3u")) or "mpegurl" in ctype:
                    text = body.decode("utf-8", "replace")
                    def register(remote):
                        remote = urllib.parse.urljoin(url, remote)
                        key = str(uuid.uuid4())
                        with MEDIA_LOCK: MEDIA[key] = (remote, referer)
                        return "/media/" + key
                    text = re.sub(r'URI="([^"]+)"', lambda m: 'URI="' + register(m.group(1)) + '"', text)
                    text = "\n".join(register(line.strip()) if line.strip() and not line.startswith("#") else line for line in text.splitlines())
                    body = text.encode(); ctype = "application/vnd.apple.mpegurl"
                self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "no-store"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body); return
            except Exception as e: return json_response(self, 502, {"error": "Falha ao carregar a mídia.", "detail": str(e)[:180]})
        return json_response(self, 404, {"error": "Não encontrado."})

    def do_POST(self):
        route = urllib.parse.urlparse(self.path).path
        if route not in ("/api/play", "/api/resolve", "/api/translate", "/api/translate-episodes"): return json_response(self, 404, {"error": "Não encontrado."})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > 8192: return json_response(self, 413, {"error": "Requisição grande demais."})
            data = json.loads(self.rfile.read(length))
            if route == "/api/translate":
                synopsis = str(data.get("text", "")).strip()
                if not synopsis or len(synopsis) > 500:
                    return json_response(self, 400, {"error": "A sinopse precisa ter até 500 caracteres."})
                translated, available = translate_synopsis_ptbr(synopsis)
                return json_response(self, 200, {"synopsis": translated, "translated": available, "provider": "MyMemory" if available else None})
            if route == "/api/translate-episodes":
                titles = data.get("titles")
                if not isinstance(titles, list) or len(titles) > 40 or any(not isinstance(title, str) or len(title) > 200 for title in titles) or sum(len(title) for title in titles) > 5000:
                    return json_response(self, 400, {"error": "Lista de títulos inválida ou grande demais."})
                from concurrent.futures import ThreadPoolExecutor
                with ThreadPoolExecutor(max_workers=4) as pool:
                    translations = list(pool.map(translate_text_ptbr, titles))
                return json_response(self, 200, {"titles": [{"original": title, "title": translated, "translated": available} for title, (translated, available) in zip(titles, translations)]})
            slug = str(data.get("slug", "")); title = str(data.get("title", ""))[:160]
            episode = str(data.get("episode", "")); mode = data.get("mode", "sub"); quality = data.get("quality", "best")
            if not re.fullmatch(r"[a-zA-Z0-9-]+-\d+", slug) or not re.fullmatch(r"\d+(?:-\d+)?", episode):
                return json_response(self, 400, {"error": "Anime ou episódio inválido."})
            if mode not in ("sub", "dub") or quality not in ("best", "1080p", "720p", "480p", "360p"):
                return json_response(self, 400, {"error": "Opção de reprodução inválida."})
            if urllib.parse.urlparse(self.path).path == "/api/resolve":
                matches = catalog(title)
                index = next((i + 1 for i, item in enumerate(matches) if item["slug"] == slug), None)
                if index is None: return json_response(self, 409, {"error": "Esse título não apareceu na busca do provedor. Faça uma nova busca e tente novamente."})
                args = [str(CLI), "-S", str(index), "-e", episode, "--quality", quality]
                if mode == "dub": args.append("--dub")
                args.append(title)
                env = {**os.environ, "ANI_CLI_PLAYER": "debug", "ANI_CLI_MENU": "fzf", "TERM": "xterm-256color"}
                result = subprocess.run(args, cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=100, check=False)
                clean = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", result.stdout)
                found = re.search(r"Selected link:\s*\n([^\n]+)\s*\nAudio:\s*\n([^\n]*)\s*\nSubtitles:\s*\n([^\n]*)\s*\nReferer:\s*\n([^\n]*)", clean)
                if result.returncode or not found: return json_response(self, 502, {"error": "Não foi possível preparar este episódio. A fonte pode estar indisponível."})
                video = found.group(1).strip().split(">")[-1].strip(); audio = found.group(2).strip(); subtitle = found.group(3).strip(); referer = found.group(4).strip()
                if not referer.startswith(("https://", "http://")): referer = BASE + "/"
                def register(remote):
                    if not remote: return ""
                    if remote.startswith("//"): remote = "https:" + remote
                    elif not remote.startswith(("https://", "http://")): remote = urllib.parse.urljoin(referer, remote)
                    key = str(uuid.uuid4())
                    with MEDIA_LOCK: MEDIA[key] = (remote, referer)
                    return "/media/" + key
                return json_response(self, 200, {"video": register(video), "audio": register(audio), "subtitle": register(subtitle), "title": title, "episode": episode})
            if not shutil.which("mpv"): return json_response(self, 400, {"error": "Instale o mpv para reproduzir episódios."})
            # Match the selected card back to the provider's search results so the CLI plays that title.
            matches = catalog(title)
            index = next((i + 1 for i, item in enumerate(matches) if item["slug"] == slug), None)
            if index is None: return json_response(self, 409, {"error": "Esse título não apareceu na busca do provedor. Faça uma nova busca e tente novamente."})
            args = [str(CLI), "-S", str(index), "-e", episode, "--quality", quality, "--exit-after-play"]
            if mode == "dub": args.append("--dub")
            args.append(title)
            subprocess.Popen(args, cwd=ROOT,
                             env={**os.environ, "ANI_CLI_PLAYER": "mpv", "ANI_CLI_MENU": "fzf"},
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
            return json_response(self, 202, {"message": "O ani-cli está preparando o episódio. O mpv abrirá em instantes."})
        except Exception as e: return json_response(self, 500, {"error": "Falha ao iniciar a reprodução.", "detail": str(e)[:200]})

if __name__ == "__main__":
    host = os.environ.get("ANI_CLI_WEB_HOST", "127.0.0.1"); port = int(os.environ.get("ANI_CLI_WEB_PORT", "8765"))
    print(f"ani-cli-pt web local: http://{host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()
