"""Draw small original native cursor PNGs; no game imagery or runtime pointer loop."""
from pathlib import Path
from PIL import Image, ImageDraw

def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    for name, fill in [('arrow', '#f0f6f1'), ('action', '#91e5ad'), ('disabled', '#b6c1bd')]:
        image = Image.new('RGBA', (32, 32))
        draw = ImageDraw.Draw(image)
        draw.polygon([(2, 2), (2, 27), (8, 21), (12, 30), (19, 27), (15, 19), (25, 19)], fill='#0d1715')
        draw.polygon([(4, 6), (4, 22), (9, 17), (14, 27), (16, 26), (12, 17), (19, 17)], fill=fill)
        if name == 'disabled':
            draw.rectangle((21, 22, 30, 31), fill='#0d1715')
            draw.line((23, 24, 28, 29), fill='#f0beb6', width=2)
            draw.line((28, 24, 23, 29), fill='#f0beb6', width=2)
        image.save(output / ('mcc-' + name + '.png'), optimize=True)
    image = Image.new('RGBA', (32, 32))
    draw = ImageDraw.Draw(image)
    draw.rectangle((10, 2, 21, 6), fill='#0d1715')
    draw.rectangle((14, 5, 17, 26), fill='#0d1715')
    draw.rectangle((10, 25, 21, 29), fill='#0d1715')
    draw.line((12, 4, 19, 4), fill='#eff7ef', width=1)
    draw.line((16, 5, 16, 26), fill='#eff7ef', width=1)
    draw.line((12, 27, 19, 27), fill='#eff7ef', width=1)
    image.save(output / 'mcc-text.png', optimize=True)

if __name__ == '__main__':
    generate(Path(__file__).resolve().parents[1] / 'frontend/public/assets/cursors')
