#!/usr/bin/env python3
import json
import os
import re
import shutil
import sys
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape

ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "config.json"
RES_DIR = ROOT / "app" / "src" / "main" / "res"


def fail(message: str) -> None:
    print(f"[apply_config] {message}", file=sys.stderr)
    sys.exit(1)


def read_config() -> dict:
    if not CONFIG_PATH.exists():
        fail("config.json not found")
    with CONFIG_PATH.open("r", encoding="utf-8") as f:
        return json.load(f)


def normalize_app_names(value) -> dict:
    """
    config.json 支持：
      "appName": "AiChat"
      "appName": {"default": "AiChat", "zh-CN": "爱聊天"}

    输出 Android strings.xml 所需的名称表：
      default -> res/values/strings.xml
      zh-CN   -> res/values-zh-rCN/strings.xml
    """
    if isinstance(value, dict):
        names = {str(k): str(v) for k, v in value.items() if v is not None and str(v) != ""}
        if "default" not in names:
            names["default"] = next(iter(names.values()), "App")
        return names
    return {"default": str(value or "App")}


def default_app_name(names: dict) -> str:
    return names.get("default") or next(iter(names.values()), "App")


def android_values_dir_for_locale(locale: str) -> str:
    if locale == "default":
        return "values"

    # Android resource qualifier: zh-CN -> values-zh-rCN, pt-BR -> values-pt-rBR
    parts = re.split(r"[-_]", locale.strip())
    if not parts or not re.fullmatch(r"[A-Za-z]{2,3}", parts[0]):
        fail(f"invalid appName locale: {locale!r}")

    lang = parts[0].lower()
    qualifiers = [lang]
    if len(parts) >= 2 and parts[1]:
        region = parts[1].upper()
        if not re.fullmatch(r"[A-Z]{2}|\d{3}", region):
            fail(f"invalid appName locale region: {locale!r}")
        qualifiers.append(f"r{region}")

    return "values-" + "-".join(qualifiers)


def write_app_name_strings(names: dict) -> None:
    # 删除脚本之前生成的语言 strings，避免 config 里删除语言后仓库里还残留旧 app_name。
    # 只删除 strings.xml 文件；values-night/styles.xml 等其它资源不动。
    for strings in RES_DIR.glob("values*/strings.xml"):
        strings.unlink()

    for locale, name in names.items():
        values_dir = RES_DIR / android_values_dir_for_locale(locale)
        values_dir.mkdir(parents=True, exist_ok=True)
        (values_dir / "strings.xml").write_text(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            "<resources>\n"
            f"    <string name=\"app_name\">{xml_escape(name)}</string>\n"
            "</resources>\n",
            encoding="utf-8",
        )


def version_code(config: dict) -> int:
    value = config.get("version", config.get("versionCode"))
    if value is not None:
        try:
            code = int(value)
            if code > 0:
                return code
        except Exception:
            pass
    version_name = str(config.get("versionName") or "1.0.0")
    parts = [int(p) if p.isdigit() else 0 for p in version_name.split(".")[:3]]
    while len(parts) < 3:
        parts.append(0)
    return parts[0] * 10000 + parts[1] * 100 + parts[2]


def gradle_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def safe_artifact_name(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", value.strip())
    return safe.strip("._-") or "app"


def replace_tokens(path: Path, replacements: dict) -> None:
    text = path.read_text(encoding="utf-8")
    for old, new in replacements.items():
        text = text.replace(old, str(new))
    path.write_text(text, encoding="utf-8")


def process_icon(config: dict) -> None:
    icon = config.get("icon") or "logo.png"
    icon_path = (ROOT / str(icon)).resolve()
    if not icon_path.exists():
        fail(f"icon not found: {icon}")

    try:
        from PIL import Image, ImageDraw
    except Exception:
        fail("Pillow is required. Install it with: pip install Pillow")

    img = Image.open(icon_path)
    img.load()
    img = img.convert("RGBA")

    for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = img.resize((size, size), Image.LANCZOS)
        base = RES_DIR / f"mipmap-{density}"
        base.mkdir(parents=True, exist_ok=True)
        out.save(base / "ic_launcher.png", format="PNG")

        mask = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=(255, 255, 255, 255))
        result = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        result.paste(out, mask=mask)
        result.save(base / "ic_launcher_round.png", format="PNG")


def export_github_env(values: dict) -> None:
    env_path = os.environ.get("GITHUB_ENV")
    if not env_path:
        return
    with open(env_path, "a", encoding="utf-8") as f:
        for key, value in values.items():
            f.write(f"{key}={value}\n")


def main() -> None:
    config = read_config()
    package_name = str(config.get("appId") or "").strip()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", package_name):
        fail(f"invalid appId: {package_name!r}")

    names = normalize_app_names(config.get("appName"))
    name = default_app_name(names)
    v_name = str(config.get("versionName") or "1.0.0")
    v_code = version_code(config)

    replace_tokens(ROOT / "app" / "build.gradle", {
        "{{APP_PACKAGE}}": gradle_string(package_name),
        "{{VERSION_CODE}}": str(v_code),
        "{{VERSION_NAME}}": gradle_string(v_name),
    })
    write_app_name_strings(names)
    process_icon(config)

    apk_base = f"{safe_artifact_name(name)}_{safe_artifact_name(v_name)}"
    export_github_env({
        "APP_NAME": name,
        "APP_SAFE_NAME": safe_artifact_name(name),
        "VERSION_NAME": v_name,
        "VERSION_CODE": v_code,
        "APK_NAME": apk_base,
    })
    print(
        f"Applied config: appId={package_name}, appName={name}, "
        f"locales={','.join(names.keys())}, versionCode={v_code}, versionName={v_name}"
    )


if __name__ == "__main__":
    main()
