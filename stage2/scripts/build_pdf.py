#!/usr/bin/env python3
"""Builds a PDF from a Markdown report, with a table of contents that has real page numbers.

    python scripts/build_pdf.py docs/REPORT.md

Chromium has no way to compute page references, so the PDF is built twice: the first pass finds the
page of each section, and the second writes those numbers in the table of contents. The `<!-- TOC -->`
line in the Markdown marks where the table goes. Requires Microsoft Edge or Chrome.
"""
import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown
from pypdf import PdfReader

BROWSERS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "/usr/bin/google-chrome", "/usr/bin/chromium", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]

CSS = """
@page { size: A4; margin: 20mm 17mm 20mm 17mm; @bottom-center { content: counter(page); font: 9pt Georgia, serif; color: #52514e; } }
@page :first { @bottom-center { content: none; } }
body { font: 10.5pt/1.45 Georgia, 'Times New Roman', serif; color: #0b0b0b; }
h1, h2, h3, h4 { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.2; break-after: avoid; }
h1 { font-size: 19pt; margin: 0 0 10pt; }
h2 { font-size: 14pt; margin: 20pt 0 8pt; border-bottom: 1px solid #d9d8d3; padding-bottom: 3pt; break-before: auto; }
h3 { font-size: 11.5pt; margin: 14pt 0 5pt; }
h4 { font-size: 10.5pt; margin: 10pt 0 4pt; }
p, li { orphans: 3; widows: 3; }
table { border-collapse: collapse; width: 100%; font: 8pt/1.3 'Segoe UI', Arial, sans-serif; margin: 8pt 0 12pt; }
th, td { border-bottom: 1px solid #e4e3df; padding: 3pt 4pt; text-align: left; vertical-align: top; }
th { border-bottom: 1.5px solid #52514e; font-weight: 600; }
tr { break-inside: avoid; }
code { font: 8.8pt Consolas, 'Courier New', monospace; background: #f3f2ee; padding: 0 2pt; overflow-wrap: anywhere; }
pre { background: #f3f2ee; padding: 7pt 9pt; font: 7.8pt/1.3 Consolas, 'Courier New', monospace; white-space: pre-wrap; break-inside: avoid; }
pre code { background: none; padding: 0; font: inherit; }
img { max-width: 100%; display: block; margin: 8pt auto; break-inside: avoid; }
.cover { height: 235mm; display: flex; flex-direction: column; justify-content: center; break-after: page; }
.cover h1 { font-size: 26pt; }
.cover .subtitle { font: 14pt 'Segoe UI', Arial, sans-serif; color: #52514e; margin-bottom: 26pt; }
.cover table { width: auto; font-size: 10.5pt; }
.cover td, .cover th { border: none; padding: 3pt 16pt 3pt 0; }
.cover th { font-weight: 600; }
.toc { break-after: page; }
.toc table { font-size: 10pt; }
.toc td { border: none; padding: 2.5pt 0; }
.toc td:last-child { text-align: right; width: 30pt; }
.toc .sub td:first-child { padding-left: 14pt; color: #52514e; font-size: 9pt; }
.toc .sub td:last-child { color: #52514e; font-size: 9pt; }
"""

HEADING = re.compile(r"<(h[23])(?: id=\"([^\"]*)\")?>(.*?)</\1>", re.S)


def find_browser():
    for candidate in BROWSERS:
        if os.path.exists(candidate):
            return candidate
    for name in ("msedge", "chrome", "chromium", "google-chrome"):
        found = shutil.which(name)
        if found:
            return found
    sys.exit("Microsoft Edge or Chrome is required to build the PDF")


def print_pdf(browser, html_path, pdf_path):
    with tempfile.TemporaryDirectory() as profile:
        subprocess.run([browser, "--headless", "--disable-gpu", "--no-pdf-header-footer", f"--user-data-dir={profile}",
                        f"--print-to-pdf={pdf_path}", html_path.as_uri()], check=True, capture_output=True, timeout=180)


def plain(fragment):
    return html.unescape(re.sub(r"<[^>]+>", "", fragment)).strip()


def normalize(text):
    return re.sub(r"\s+", " ", text).strip().lower()


def page_of_each_heading(pdf_path, headings, first_body_page):
    pages = [normalize(page.extract_text() or "") for page in PdfReader(str(pdf_path)).pages]
    numbers = {}
    current = first_body_page
    for _, title, _ in headings:
        wanted = normalize(title)
        for index in range(current, len(pages)):
            if wanted in pages[index]:
                numbers[title] = index + 1
                current = index
                break
        else:
            raise RuntimeError(f"Section not found in the PDF: {title}")
    return numbers


def toc_html(headings, numbers):
    rows = []
    for level, title, anchor in headings:
        css = "" if level == "h2" else ' class="sub"'
        number = numbers.get(title, "0")
        rows.append(f'<tr{css}><td><a href="#{anchor}" style="color:inherit;text-decoration:none">{html.escape(title)}</a></td><td>{number}</td></tr>')
    return '<div class="toc"><h2>Table of contents</h2><table>' + "".join(rows) + "</table></div>"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    source = args.source.resolve()
    target = source.with_suffix(".pdf")

    converter = markdown.Markdown(extensions=["tables", "fenced_code", "toc", "md_in_html", "attr_list"])
    body = converter.convert(source.read_text(encoding="utf-8"))
    if "<!-- TOC -->" not in body:
        sys.exit("The source has no <!-- TOC --> line")
    head, tail = body.split("<!-- TOC -->", 1)
    headings = [(m.group(1), plain(m.group(3)), m.group(2)) for m in HEADING.finditer(tail)]

    def document(numbers):
        return (f'<!doctype html><html lang="en"><head><meta charset="utf-8"><base href="{source.parent.as_uri()}/">'
                f"<title>{html.escape(source.stem)}</title><style>{CSS}</style></head><body>"
                f"{head}{toc_html(headings, numbers)}{tail}</body></html>")

    browser = find_browser()
    with tempfile.TemporaryDirectory() as work:
        page = Path(work) / "report.html"
        draft = Path(work) / "draft.pdf"
        page.write_text(document({}), encoding="utf-8")
        print_pdf(browser, page, draft)
        # The body starts right after the table of contents, which sits on the pages before it.
        reader_pages = [normalize(p.extract_text() or "") for p in PdfReader(str(draft)).pages]
        toc_page = next(i for i, text in enumerate(reader_pages) if "table of contents" in text)
        first_title = normalize(headings[0][1])
        first_body = next(i for i in range(toc_page + 1, len(reader_pages)) if first_title in reader_pages[i])
        numbers = page_of_each_heading(draft, headings, first_body)
        page.write_text(document(numbers), encoding="utf-8")
        print_pdf(browser, page, target)

    pages = PdfReader(str(target)).pages
    print(f"{target}: {len(pages)} pages, {len(headings)} sections in the table of contents")


if __name__ == "__main__":
    main()
