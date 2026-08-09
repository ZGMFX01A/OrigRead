from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
RES_DIR = ROOT / "app" / "src" / "main" / "res"


def main() -> None:
    drawable_dir = RES_DIR / "drawable-nodpi"
    drawable_dir.mkdir(parents=True, exist_ok=True)

    logo = Image.open(ROOT / "logo.png").convert("RGBA")
    icon = Image.open(ROOT / "icon.png").convert("RGBA")

    # Logo 用于应用内品牌展示，Icon 用于系统桌面与自适应图标。
    logo.save(drawable_dir / "origread_logo.png")
    icon.save(drawable_dir / "origread_icon.png")

    density_sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in density_sizes.items():
        target_dir = RES_DIR / f"mipmap-{density}"
        target_dir.mkdir(parents=True, exist_ok=True)
        launcher = icon.resize((size, size), Image.Resampling.LANCZOS)
        launcher.save(target_dir / "ic_launcher.png")
        launcher.save(target_dir / "ic_launcher_round.png")


if __name__ == "__main__":
    main()
