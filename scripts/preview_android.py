#!/usr/bin/env python3
"""Build and open a local Android preview without Android Studio or GitHub CI."""

from __future__ import annotations

import argparse
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen
import xml.etree.ElementTree as ET
import zipfile

from resumable_download import download

ROOT = Path(__file__).resolve().parent.parent
STATE = ROOT / "private" / "android-preview"
AVD_NAME = "inzenyri-preview"
IMAGE = "system-images;android-35;google_apis;x86_64"
LITE_IMAGE = "system-images;android-30;default;x86_64"
GOOGLE_REPOSITORY = "https://dl.google.com/android/repository/"
STREAM_PROFILES = {
    "low": (640, 30, "1M"),
    "balanced": (960, 30, "2M"),
    "high": (1280, 60, "8M"),
}


def sdk_path():
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured).expanduser().resolve()
    usual = Path.home() / "Android" / "Sdk"
    return usual if usual.is_dir() else STATE / "sdk"


def execute(command, env, **kwargs):
    return subprocess.run(
        [str(part) for part in command], env=env, cwd=ROOT, check=True, **kwargs
    )


def command_tools(sdk):
    folders = [sdk / "cmdline-tools" / "latest" / "bin"]
    folders.extend(sorted((sdk / "cmdline-tools").glob("*/bin"), reverse=True))
    for folder in folders:
        if (folder / "sdkmanager").is_file() and (folder / "avdmanager").is_file():
            return folder
    return None


def install_command_tools(sdk):
    print("Stahuji oficiální Android Command-line Tools…", flush=True)
    with urlopen(GOOGLE_REPOSITORY + "repository2-1.xml", timeout=60) as response:
        repository = ET.fromstring(response.read())
    package = next(
        (
            node
            for node in repository.iter()
            if node.tag.endswith("remotePackage")
            and node.get("path") == "cmdline-tools;latest"
        ),
        None,
    )
    if package is None:
        raise RuntimeError("Google metadata neobsahují cmdline-tools;latest.")
    archive = next(
        (
            node
            for node in package.findall("./archives/archive")
            if node.findtext("host-os") == "linux"
        ),
        None,
    )
    if archive is None:
        raise RuntimeError("Chybí balíček Command-line Tools pro Linux.")
    complete = archive.find("complete")
    filename = complete.findtext("url")
    checksum = complete.find("checksum")
    if not filename or "/" in filename or "\\" in filename or checksum is None:
        raise RuntimeError("Neplatná metadata Android nástrojů.")
    algorithm = checksum.get("type", "sha1").replace("-", "").lower()
    if algorithm not in {"sha1", "sha256"}:
        raise RuntimeError("Nepodporovaný kontrolní součet Android nástrojů.")
    size = int(complete.findtext("size", "0"))
    sdk.mkdir(parents=True, exist_ok=True)
    archive_path = download(
        GOOGLE_REPOSITORY + filename,
        STATE / "downloads" / filename,
        size,
        algorithm,
        checksum.text or "",
    )
    with tempfile.TemporaryDirectory(dir=STATE) as temporary:
        directory = Path(temporary)
        unpacked = directory / "unpacked"
        with zipfile.ZipFile(archive_path) as archive_file:
            for member in archive_file.infolist():
                if (
                    not (unpacked / member.filename)
                    .resolve()
                    .is_relative_to(unpacked.resolve())
                ):
                    raise RuntimeError("Neplatná cesta ve staženém archivu.")
            archive_file.extractall(unpacked)
        destination = sdk / "cmdline-tools" / "latest"
        if destination.exists():
            raise RuntimeError(
                f"Neúplná instalace v {destination}; zkontroluj ji před dalším setupem."
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(unpacked / "cmdline-tools"), destination)
        for executable in (destination / "bin").iterdir():
            if executable.is_file():
                executable.chmod(0o755)


def setup(sdk, env, *, avd_name=AVD_NAME, image=IMAGE):
    tools = command_tools(sdk)
    if tools is None:
        install_command_tools(sdk)
        tools = command_tools(sdk)
    print("Android SDK licence potvrď v následující nabídce.", flush=True)
    execute([tools / "sdkmanager", f"--sdk_root={sdk}", "--licenses"], env)
    execute(
        [
            tools / "sdkmanager",
            f"--sdk_root={sdk}",
            "platform-tools",
            "emulator",
            "platforms;android-35",
            "build-tools;35.0.0",
            image,
        ],
        env,
    )
    avds = execute(
        [sdk / "emulator" / "emulator", "-list-avds"],
        env,
        capture_output=True,
        text=True,
    ).stdout.splitlines()
    if avd_name not in avds:
        execute(
            [
                tools / "avdmanager",
                "create",
                "avd",
                "--name",
                avd_name,
                "--package",
                image,
                "--device",
                "pixel_2" if image == LITE_IMAGE else "pixel_7",
            ],
            env,
            input="no\n",
            text=True,
        )


def tune_avd(env, gpu, *, avd_name=AVD_NAME, memory=2048):
    """Change only the stopped preview AVD; preserve its data and original configuration."""
    config = Path(env["ANDROID_AVD_HOME"]) / f"{avd_name}.avd" / "config.ini"
    original = config.read_text()
    settings = {
        "hw.lcd.width": "720",
        "hw.lcd.height": "1280",
        "hw.lcd.density": "280",
        "hw.cpu.ncore": "2",
        "hw.ramSize": str(memory),
        "hw.gpu.enabled": "yes",
        "hw.gpu.mode": gpu,
        "showDeviceFrame": "no",
    }
    updated = []
    remaining = dict(settings)
    for line in original.splitlines():
        key = line.partition("=")[0].strip()
        if key in settings:
            if key in remaining:
                updated.append(f"{key}={remaining.pop(key)}")
        else:
            updated.append(line)
    updated.extend(f"{key}={value}" for key, value in remaining.items())
    text = "\n".join(updated) + "\n"
    if text == original:
        return False
    backup = config.with_name("config.ini.before-preview-tuning")
    if not backup.exists():
        shutil.copy2(config, backup)
    temporary = config.with_suffix(".ini.tmp")
    temporary.write_text(text)
    temporary.replace(config)
    return True


def preview(
    sdk,
    env,
    *,
    reuse_apk=False,
    gpu="host",
    avd_name=AVD_NAME,
    memory=2048,
    vulkan=False,
    cold_boot=False,
    scrcpy=False,
    stream_quality="balanced",
    with_audio=False,
    show_fps=False,
):
    adb = sdk / "platform-tools" / "adb"
    emulator = sdk / "emulator" / "emulator"
    if not adb.is_file() or not emulator.is_file():
        raise RuntimeError("Chybí Android emulátor nebo SDK. Spusť nejdřív --setup.")
    if not os.access("/dev/kvm", os.R_OK | os.W_OK):
        raise RuntimeError(
            "KVM není dostupné. Povol virtualizaci v BIOS/UEFI a přístup svého účtu k /dev/kvm."
        )
    if scrcpy and shutil.which("scrcpy") is None:
        raise RuntimeError(
            "Pro --scrcpy nainstaluj nástroj scrcpy z balíčků své distribuce."
        )
    if not os.environ.get("DISPLAY") and not os.environ.get("WAYLAND_DISPLAY"):
        raise RuntimeError(
            "Preview potřebuje grafické prostředí (DISPLAY nebo WAYLAND_DISPLAY)."
        )
    avds = execute(
        [emulator, "-list-avds"], env, capture_output=True, text=True
    ).stdout.splitlines()
    if avd_name not in avds:
        raise RuntimeError(
            f"Emulátor {avd_name} ještě není vytvořený. Spusť --setup se stejnými přepínači profilu."
        )
    devices = execute(
        [adb, "devices"], env, capture_output=True, text=True
    ).stdout.splitlines()[1:]
    occupied = set()
    serial = None
    for line in devices:
        parts = line.split()
        if len(parts) < 2 or not parts[0].startswith("emulator-"):
            continue
        occupied.add(parts[0])
        if parts[1] == "device":
            result = execute(
                [adb, "-s", parts[0], "emu", "avd", "name"],
                env,
                capture_output=True,
                text=True,
                timeout=10,
            )
            if avd_name in result.stdout.splitlines():
                serial = parts[0]
                break
    # Build before starting a stopped emulator to avoid competing for limited RAM.
    if not reuse_apk:
        print("Sestavuji Inženýři Preview (nejvýše 2 workery, JVM 1 GB)…", flush=True)
        execute(
            [
                "gradle",
                "-p",
                ROOT / "app",
                "assembleDebug",
                "-PlocalPreview=true",
                "--console=plain",
                "--no-daemon",
                "--max-workers=2",
                "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8",
                "-Pkotlin.compiler.execution.strategy=in-process",
            ],
            env,
        )
    output = ROOT / "app" / "build" / "outputs" / "apk" / "debug"
    metadata_path = output / "output-metadata.json"
    if not metadata_path.is_file():
        raise RuntimeError("Chybí preview APK. Spusť příkaz bez --reuse-apk.")
    metadata = json.loads(metadata_path.read_text())
    if metadata["applicationId"] != "cz.inzenyri.mobile.preview":
        raise RuntimeError(
            "APK není oddělená preview aplikace. Spusť příkaz bez --reuse-apk."
        )
    apk = (output / metadata["elements"][0]["outputFile"]).resolve()
    if not apk.is_relative_to(output.resolve()) or not apk.is_file():
        raise RuntimeError("Chybí platné preview APK. Spusť příkaz bez --reuse-apk.")
    process = None
    log_path = STATE / ("emulator-lite.log" if avd_name != AVD_NAME else "emulator.log")
    renderer_state = STATE / f"{avd_name}-renderer.json"
    emulator_env = env.copy()
    # The bundled Qt lacks a Wayland plugin on some Linux installations.
    if env.get("DISPLAY"):
        emulator_env["QT_QPA_PLATFORM"] = "xcb"
    renderer = {
        "gpu": gpu,
        "vulkan": vulkan,
        "qt": emulator_env.get("QT_QPA_PLATFORM"),
        "headless": scrcpy,
    }
    if serial is None:
        port = next(
            (
                port
                for port in range(5580, 5682, 2)
                if f"emulator-{port}" not in occupied
            ),
            None,
        )
        if port is None:
            raise RuntimeError("Není volný port pro emulátor.")
        serial = f"emulator-{port}"
        changed = tune_avd(env, gpu, avd_name=avd_name, memory=memory)
        try:
            previous_renderer = json.loads(renderer_state.read_text())
        except (OSError, ValueError):
            previous_renderer = None
        print(
            f"Spouštím {avd_name}: 720×1280, RAM {memory} MB, 2 CPU, GPU {gpu}, KVM, Vulkan {'zapnutý' if vulkan else 'vypnutý'}…",
            flush=True,
        )
        command = [
            str(emulator),
            "-avd",
            avd_name,
            "-port",
            str(port),
            "-no-boot-anim",
            "-memory",
            str(memory),
            "-cores",
            "2",
            "-gpu",
            gpu,
            "-accel",
            "on",
        ]
        if not vulkan:
            command.extend(["-feature", "-Vulkan"])
        if scrcpy:
            command.append("-no-window")
        if changed or cold_boot or previous_renderer != renderer:
            # A snapshot of the old display/RAM configuration cannot be reused safely.
            command.append("-no-snapshot-load")
        with log_path.open("w") as log:
            process = subprocess.Popen(
                command,
                env=emulator_env,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
    else:
        print(
            "Používám běžící preview. Změny GPU, Vulkanu, paměti a režimu okna vyžadují zavření a nové spuštění emulátoru.",
            flush=True,
        )
    print("Čekám na spuštění Androidu…", flush=True)
    deadline = time.monotonic() + 240
    while True:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"Emulátor skončil. Podrobnosti: {log_path}")
        try:
            boot = subprocess.run(
                [str(adb), "-s", serial, "shell", "getprop", "sys.boot_completed"],
                env=env,
                capture_output=True,
                text=True,
                timeout=5,
            )
            if boot.returncode == 0 and boot.stdout.strip() == "1":
                break
        except subprocess.TimeoutExpired:
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError(
                f"Android se nespustil do 4 minut. Podrobnosti: {log_path}"
            )
        time.sleep(1)
    if process is not None:
        renderer_state.write_text(json.dumps(renderer))
    execute([adb, "-s", serial, "install", "-r", apk], env)
    execute(
        [
            adb,
            "-s",
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "cz.inzenyri.mobile.preview/cz.inzenyri.mobile.MainActivity",
        ],
        env,
    )
    print(
        "Preview běží. Po změně kódu zopakuj stejný příkaz; emulátor i data se zachovají."
    )
    if scrcpy:
        viewer_env = env.copy()
        viewer_env["ADB"] = str(adb)
        size, fps, bitrate = STREAM_PROFILES[stream_quality]
        command = [
            "scrcpy",
            "--serial",
            serial,
            f"--max-size={size}",
            f"--max-fps={fps}",
            f"--video-bit-rate={bitrate}",
            "--video-codec=h264",
            "--window-title=Inženýři Preview",
        ]
        if not with_audio:
            command.append("--no-audio")
        if show_fps:
            command.append("--print-fps")
        print(
            f"scrcpy: profil {stream_quality}, max. {size} px, {fps} FPS, {bitrate} bit/s, "
            f"zvuk {'zapnutý' if with_audio else 'vypnutý'}.",
            flush=True,
        )
        execute(command, viewer_env)
        print(
            f"Okno zavřeno. Emulátor běží dál; ukončíš ho příkazem: {adb} -s {serial} emu kill"
        )


def main():
    parser = argparse.ArgumentParser(
        description="Lokální Android preview na Linuxu bez IDE a GitHubu."
    )
    parser.add_argument(
        "--setup",
        action="store_true",
        help="Stáhnout SDK a obraz Androidu a vytvořit emulátor (několik GB).",
    )
    parser.add_argument(
        "--reuse-apk",
        action="store_true",
        help="Použít poslední preview APK bez sestavení; nezahrne nové změny kódu.",
    )
    parser.add_argument(
        "--gpu",
        choices=["host", "auto", "swiftshader"],
        default="host",
        help="Grafika emulátoru: host = hardwarová GPU (výchozí), auto nebo pomalejší softwarový swiftshader.",
    )
    parser.add_argument(
        "--vulkan",
        action="store_true",
        help="Zapnout Vulkan (výchozí je OpenGL bez Vulkanu).",
    )
    parser.add_argument(
        "--cold-boot",
        action="store_true",
        help="Start bez načtení snapshotu; nemaže data aplikace.",
    )
    parser.add_argument(
        "--lite",
        action="store_true",
        help="Oddělený čistý Android 11 bez Google služeb, 1536 MB RAM; poprvé přidej --setup.",
    )
    parser.add_argument(
        "--scrcpy",
        action="store_true",
        help="Spustit emulátor bez Qt okna a ovládat jej přes scrcpy (musí být nainstalované).",
    )
    parser.add_argument(
        "--stream-quality",
        choices=STREAM_PROFILES,
        default="balanced",
        help="Přenos scrcpy: low 640px/1Mb/s, balanced 960px/2Mb/s (výchozí), high 1280px/8Mb/s.",
    )
    parser.add_argument(
        "--with-audio",
        action="store_true",
        help="Zapnout přenos zvuku přes scrcpy (výchozí je vypnutý).",
    )
    parser.add_argument(
        "--show-fps",
        action="store_true",
        help="Vypisovat skutečné FPS přenosu scrcpy při změnách obrazu.",
    )
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine().lower() not in {
        "x86_64",
        "amd64",
    }:
        raise RuntimeError(
            "Tento preview příkaz je určený pro Linux na Intel/AMD 64bit."
        )
    for tool in ["java", "gradle"]:
        if shutil.which(tool) is None:
            raise RuntimeError(f"Chybí {tool}. Nainstaluj JDK 17 a Gradle 9.8.1.")
    STATE.parent.mkdir(mode=0o700, exist_ok=True)
    STATE.mkdir(mode=0o700, exist_ok=True)
    sdk = sdk_path()
    env = os.environ.copy()
    env["ANDROID_HOME"] = env["ANDROID_SDK_ROOT"] = str(sdk)
    env["ANDROID_AVD_HOME"] = str(STATE / "avd")
    Path(env["ANDROID_AVD_HOME"]).mkdir(exist_ok=True)
    avd_name = AVD_NAME + "-lite" if args.lite else AVD_NAME
    if args.setup:
        setup(sdk, env, avd_name=avd_name, image=LITE_IMAGE if args.lite else IMAGE)
    preview(
        sdk,
        env,
        reuse_apk=args.reuse_apk,
        gpu=args.gpu,
        avd_name=avd_name,
        memory=1536 if args.lite else 2048,
        vulkan=args.vulkan,
        cold_boot=args.cold_boot,
        scrcpy=args.scrcpy,
        stream_quality=args.stream_quality,
        with_audio=args.with_audio,
        show_fps=args.show_fps,
    )


if __name__ == "__main__":
    try:
        main()
    except (
        RuntimeError,
        OSError,
        ValueError,
        ET.ParseError,
        zipfile.BadZipFile,
        subprocess.SubprocessError,
    ) as error:
        print(f"Chyba: {error}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        print("\nPreview příkaz přerušen; spuštěný emulátor můžeš zavřít jeho oknem.")
        sys.exit(130)
