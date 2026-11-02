import json
import sys
import threading
import unittest
import urllib.error
import urllib.request
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


class ParserTests(unittest.TestCase):
    def test_catalog_parser_extracts_details(self):
        source = '''<div class="flw-item"><div class="film-detail"><h3><a class="dynamic-name" href="/sample-show-42" title="Sample Show" data-jname="サンプル">Sample Show</a></h3><div class="description">A short synopsis.</div><span class="fdi-item">TV</span></div></div>'''
        parser = server.CatalogParser()
        parser.feed(source)
        self.assertEqual(len(parser.items), 1)
        self.assertEqual(parser.items[0]["slug"], "sample-show-42")
        self.assertEqual(parser.items[0]["title"], "Sample Show")
        self.assertEqual(parser.items[0]["synopsis"], "A short synopsis.")

    def test_episode_parser_extracts_number_and_title(self):
        parser = server.EpisodeParser()
        parser.feed('<a class="ssl-item ep-item" data-number="2" data-id="900" title="The Second Step"></a>')
        self.assertEqual(parser.items, [{"number": "2", "id": "900", "title": "The Second Step"}])

    def test_artwork_parser_extracts_high_resolution_banner_and_poster(self):
        parser = server.ArtworkParser()
        parser.feed('<div class="anis-cover" style="background-image: url(https://example.test/banner.webp)"></div><img class="film-poster-img" src="https://example.test/poster.webp">')
        self.assertEqual(parser.banner, "https://example.test/banner.webp")
        self.assertEqual(parser.poster, "https://example.test/poster.webp")

    def test_srt_is_converted_to_webvtt(self):
        result = server.to_webvtt("1\r\n00:00:01,200 --> 00:00:02,400\r\nOlá\r\n".encode())
        self.assertTrue(result.startswith(b"WEBVTT\n"))
        self.assertIn(b"00:00:01.200 --> 00:00:02.400", result)
        self.assertIn("Olá".encode(), result)

    def test_existing_webvtt_is_preserved(self):
        source = b"WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        self.assertEqual(server.to_webvtt(source), source)

    def test_translation_helper_uses_provider_result(self):
        server.translate_synopsis_ptbr.cache_clear()
        response = BytesIO(json.dumps({"responseStatus": 200, "responseData": {"translatedText": "Sinopse em português."}}).encode())
        with patch.object(server.urllib.request, "urlopen", return_value=response):
            self.assertEqual(server.translate_synopsis_ptbr("An English synopsis."), ("Sinopse em português.", True))
        server.translate_synopsis_ptbr.cache_clear()

    def test_translation_helper_falls_back_to_original(self):
        server.translate_synopsis_ptbr.cache_clear()
        with patch.object(server.urllib.request, "urlopen", side_effect=TimeoutError):
            self.assertEqual(server.translate_synopsis_ptbr("An English synopsis."), ("An English synopsis.", False))
        server.translate_synopsis_ptbr.cache_clear()


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=2)

    def test_homepage_contains_localized_seo_metadata(self):
        with urllib.request.urlopen(self.base + "/") as response:
            page = response.read().decode()
            self.assertEqual(response.status, 200)
            self.assertIn('<html lang="pt-BR">', page)
            self.assertIn('name="description"', page)
            self.assertIn('"@type":"WebApplication"', page)

    def test_seasonal_endpoint_returns_current_airing_selection(self):
        with patch.object(server, "seasonal_catalog", return_value=[{"title": "Witch Hat Atelier", "slug": "witch-hat-atelier-90"}]):
            with urllib.request.urlopen(self.base + "/api/seasonal") as response:
                data = json.load(response)
        self.assertEqual(data["items"][0]["title"], "Witch Hat Atelier")

    def test_translation_endpoint_returns_ptbr_and_source_label(self):
        payload = json.dumps({"text": "A young hero finds a new home."}).encode()
        request = urllib.request.Request(self.base + "/api/translate", data=payload,
                                         headers={"Content-Type": "application/json"}, method="POST")
        with patch.object(server, "translate_synopsis_ptbr", return_value=("Um jovem herói encontra um novo lar.", True)):
            with urllib.request.urlopen(request) as response:
                data = json.load(response)
        self.assertEqual(data["synopsis"], "Um jovem herói encontra um novo lar.")
        self.assertTrue(data["translated"])
        self.assertEqual(data["provider"], "MyMemory")

    def test_translation_endpoint_rejects_oversized_text(self):
        payload = json.dumps({"text": "x" * 501}).encode()
        request = urllib.request.Request(self.base + "/api/translate", data=payload,
                                         headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request)
        self.assertEqual(error.exception.code, 400)
        error.exception.close()

    def test_episode_translation_endpoint_translates_titles_and_preserves_source_on_failure(self):
        payload = json.dumps({"titles": ["The First Step", "Episode 2"]}).encode()
        request = urllib.request.Request(self.base + "/api/translate-episodes", data=payload,
                                         headers={"Content-Type": "application/json"}, method="POST")
        def translate(text):
            return ("O primeiro passo", True) if text == "The First Step" else (text, False)
        with patch.object(server, "translate_text_ptbr", side_effect=translate):
            with urllib.request.urlopen(request) as response:
                data = json.load(response)
        self.assertEqual(data["titles"][0], {"original": "The First Step", "title": "O primeiro passo", "translated": True})
        self.assertEqual(data["titles"][1], {"original": "Episode 2", "title": "Episode 2", "translated": False})

    def test_episode_translation_endpoint_rejects_invalid_title_list(self):
        payload = json.dumps({"titles": ["x" * 201]}).encode()
        request = urllib.request.Request(self.base + "/api/translate-episodes", data=payload,
                                         headers={"Content-Type": "application/json"}, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request)
        self.assertEqual(error.exception.code, 400)
        error.exception.close()

    def test_invalid_episode_slug_is_rejected(self):
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(self.base + "/api/episodes?slug=not-valid")
        self.assertEqual(error.exception.code, 400)
        error.exception.close()

    def test_artwork_endpoint_returns_large_art_and_rejects_invalid_slug(self):
        page = '<div class="anis-cover" style="background-image: url(https://example.test/banner.webp)"></div><img class="film-poster-img" src="https://example.test/poster.webp">'
        with patch.object(server, "fetch", return_value=page):
            with urllib.request.urlopen(self.base + "/api/artwork?slug=sample-show-42") as response:
                self.assertEqual(json.load(response), {"banner": "https://example.test/banner.webp", "poster": "https://example.test/poster.webp"})
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(self.base + "/api/artwork?slug=not-valid")
        self.assertEqual(error.exception.code, 400)
        error.exception.close()


if __name__ == "__main__":
    unittest.main()
