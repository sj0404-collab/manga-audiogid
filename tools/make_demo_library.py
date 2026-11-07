#!/usr/bin/env python3
"""
Генерирует демо-библиотеку для быстрой проверки читалки:
  demo-library/
    Grand Blue/            (манга: обложка + главы-архивы .cbz)
      cover.jpg
      Том 1. Глава 1.cbz
      Том 1. Глава 2.cbz
    Idol of Child/         (манга: обложка + главы-папки со страницами)
      cover.jpg
      Глава 1/*.jpg
    Вебтун Пример/         (вебтун: длинные страницы)
      cover.jpg
      Глава 1.cbz
"""
import os, zipfile, io, textwrap
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
OUT = "demo-library"

def bubble_page(lines, w=900, h=1300, title="", seed=0):
    img = Image.new("RGB", (w, h), (235, 235, 238))
    d = ImageDraw.Draw(img)
    # «панель» с серым фоном и рамкой
    d.rectangle([30, 30, w - 30, h - 30], outline=(20, 20, 20), width=6, fill=(214, 209, 202))
    font_title = ImageFont.truetype(FONT, 26)
    if title:
        d.text((50, 48), title, font=font_title, fill=(40, 40, 40))
    font = ImageFont.truetype(FONT, 26)
    y = 120 + seed * 10
    for ln in lines:
        bb = d.textbbox((0, 0), ln, font=font)
        tw, th = bb[2] - bb[0], bb[3] - bb[1]
        x = 70
        d.ellipse([x - 18, y - 14, x + tw + 18, y + th + 20], fill="white", outline=(10, 10, 10), width=4)
        d.text((x, y), ln, font=font, fill=(0, 0, 0))
        y += th + 60
    return img

def save_jpg(img, path, quality=88):
    img.save(path, "JPEG", quality=quality)

def add_to_zip(zf, name, img, quality=88):
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    zf.writestr(name, buf.getvalue())

CH1 = ["Вы должны быть", "равнодушны, как и всегда.", "Притворитесь, что", "обдумываете её слова."]
CH2 = ["Стой! Ты куда собралась?", "Я не понимаю, что", "здесь происходит.", "Объясни мне, пожалуйста."]
WEBTOON = ["Это вебтун.", "Читается сверху вниз,", "без рывков и подвисаний.", "Проверяем длинную ленту."]

def build():
    os.makedirs(OUT, exist_ok=True)

    # --- манга с архивами ---
    manga = os.path.join(OUT, "Grand Blue")
    os.makedirs(manga, exist_ok=True)
    save_jpg(bubble_page(["Grand Blue"], title="Обложка"), os.path.join(manga, "cover.jpg"))
    for idx, lines in enumerate([CH1, CH2], start=1):
        with zipfile.ZipFile(os.path.join(manga, f"Том 1. Глава {idx}.cbz"), "w", zipfile.ZIP_STORED) as zf:
            for p in range(1, 4):
                img = bubble_page(lines, title=f"Глава {idx} · страница {p}", seed=p)
                add_to_zip(zf, f"{p:03d}.jpg", img)

    # --- манга с главами-папками ---
    manga2 = os.path.join(OUT, "Idol of Child")
    os.makedirs(manga2, exist_ok=True)
    save_jpg(bubble_page(["Idol of Child"], title="Обложка"), os.path.join(manga2, "cover.jpg"))
    ch = os.path.join(manga2, "Глава 1")
    os.makedirs(ch, exist_ok=True)
    for p in range(1, 4):
        save_jpg(bubble_page(CH2, title=f"Страница {p}", seed=p), os.path.join(ch, f"{p:03d}.jpg"))

    # --- вебтун ---
    wt = os.path.join(OUT, "Вебтун Пример")
    os.makedirs(wt, exist_ok=True)
    save_jpg(bubble_page(["Вебтун Пример"], title="Обложка"), os.path.join(wt, "cover.jpg"))
    with zipfile.ZipFile(os.path.join(wt, "Глава 1.cbz"), "w", zipfile.ZIP_STORED) as zf:
        for p in range(1, 5):
            img = bubble_page(WEBTOON, w=800, h=1800, title=f"Вебтун · {p}", seed=p)
            add_to_zip(zf, f"{p:03d}.jpg", img)

    # упаковать всё в один архив для переноса на телефон
    with zipfile.ZipFile("demo-library.zip", "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(OUT):
            for f in files:
                full = os.path.join(root, f)
                zf.write(full, os.path.relpath(full, OUT))
    print("готово: demo-library.zip")

if __name__ == "__main__":
    build()
