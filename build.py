#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
手工构建 APK（不依赖 Gradle）：
  aapt2 compile/link -> javac -> jar -> d8 -> 合并 dex -> zipalign -> apksigner

注意：aapt2 在 Windows 下打不开含中文的路径，
所以先把 app/ 复制到纯 ASCII 工作目录（CFBUILD 常量）再构建。

用法:
  python build.py
"""
import os
import ctypes
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
SDK = r"D:\D\android-sdk"
BT = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM = os.path.join(SDK, "platforms", "android-34", "android.jar")
JAVA_HOME = r"C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
KEYSTORE = os.path.join(ROOT, "keystore", "debug.keystore")
DIST = os.path.join(ROOT, "dist")

APP = "app"
OUT = "云服务领碎片.apk"


def exe(name):
    p = os.path.join(JAVA_HOME, "bin", name)
    return p if os.path.exists(p) else name


def run(cmd, cwd=None, quiet=False):
    if not quiet:
        print("  $", " ".join(str(c) for c in cmd[:6]), "..." if len(cmd) > 6 else "")
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    if r.returncode != 0:
        print(out)
        raise SystemExit("FAILED: %s (exit %d)" % (cmd[0], r.returncode))
    if out.strip() and not quiet:
        tail = [l for l in out.strip().splitlines() if l.strip()][-8:]
        for l in tail:
            print("   ", l)
    return out


def ensure_keystore():
    if os.path.exists(KEYSTORE):
        return
    os.makedirs(os.path.dirname(KEYSTORE), exist_ok=True)
    print("[key] 生成 debug 签名…")
    run([exe("keytool"), "-genkeypair", "-keystore", KEYSTORE,
         "-alias", "androiddebugkey", "-storepass", "android", "-keypass", "android",
         "-dname", "CN=Android Debug,O=Android,C=US", "-keyalg", "RSA",
         "-keysize", "2048", "-validity", "10000"], quiet=True)


def java_files(base):
    out = []
    for r, _d, fs in os.walk(base):
        for f in fs:
            if f.endswith(".java"):
                out.append(os.path.join(r, f))
    return out


def has_res_files(res_dir):
    if not os.path.isdir(res_dir):
        return False
    for _r, _d, fs in os.walk(res_dir):
        if fs:
            return True
    return False


def build():
    src_orig = os.path.join(ROOT, APP)
    work = os.path.join(r"D:\D\_cfbuild", APP)
    if os.path.exists(work):
        shutil.rmtree(work)
    os.makedirs(work)
    for item in ("AndroidManifest.xml", "res", "src"):
        s = os.path.join(src_orig, item)
        if os.path.exists(s):
            d = os.path.join(work, item)
            if os.path.isdir(s):
                shutil.copytree(s, d)
            else:
                shutil.copyfile(s, d)

    build_dir = os.path.join(work, "build")
    os.makedirs(build_dir)
    gen = os.path.join(build_dir, "gen")
    classes = os.path.join(build_dir, "classes")
    dexdir = os.path.join(build_dir, "dex")
    os.makedirs(gen)
    os.makedirs(classes)
    os.makedirs(dexdir)
    os.makedirs(DIST, exist_ok=True)

    aapt2 = os.path.join(BT, "aapt2.exe")
    manifest = os.path.join(work, "AndroidManifest.xml")
    res_dir = os.path.join(work, "res")
    res_zip = os.path.join(build_dir, "res.zip")
    base_apk = os.path.join(build_dir, "base.apk")

    print("[1/6] aapt2 compile")
    if has_res_files(res_dir):
        run([aapt2, "compile", "--dir", res_dir, "-o", res_zip])
        link_res = [res_zip]
    else:
        link_res = []

    print("[2/6] aapt2 link")
    run([aapt2, "link", "-o", base_apk, "-I", PLATFORM,
         "--manifest", manifest, "--java", gen,
         "--min-sdk-version", "24", "--target-sdk-version", "34",
         "--no-version-vectors"] + link_res)

    print("[3/6] javac")
    jsrcs = java_files(os.path.join(work, "src")) + java_files(gen)
    if not jsrcs:
        raise SystemExit("没有源码")
    run([exe("javac"), "--release", "8", "-encoding", "UTF-8",
         "-nowarn", "-classpath", PLATFORM, "-d", classes] + jsrcs)

    print("[4/6] jar + d8")
    cjar = os.path.join(build_dir, "classes.jar")
    run([exe("jar"), "cf", cjar, "-C", classes, "."], quiet=True)
    # build-tools 34 自带的 R8 处理 JDK21 编译的 class 会内部崩溃，用 cmdline-tools 里的新版 R8
    d8jar = os.path.join(SDK, "cmdline-tools", "latest", "lib", "d8-classpath.jar")
    run([exe("java"), "-cp", d8jar, "com.android.tools.r8.D8",
         "--lib", PLATFORM, "--min-api", "24", "--output", dexdir, cjar])

    print("[5/6] 合并 dex 进 apk")
    out_apk = os.path.join(build_dir, "unsigned.apk")
    shutil.copyfile(base_apk, out_apk)
    with zipfile.ZipFile(out_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dexdir, "classes.dex"), "classes.dex")

    aligned = os.path.join(build_dir, "aligned.apk")
    run([os.path.join(BT, "zipalign.exe"), "-f", "4", out_apk, aligned], quiet=True)

    print("[6/6] apksigner")
    dist_apk = os.path.join(DIST, OUT)
    run([exe("java"), "-jar", os.path.join(BT, "lib", "apksigner.jar"), "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
         "--out", dist_apk, aligned], quiet=True)
    size = os.path.getsize(dist_apk) / 1024.0
    print("OK -> %s (%.0f KB)" % (dist_apk, size))
    root_copy = os.path.join(ROOT, OUT)
    shutil.copyfile(dist_apk, root_copy)
    print("     交付副本 -> %s" % root_copy)
    return dist_apk


def main():
    if not os.path.exists(PLATFORM):
        raise SystemExit("缺少 %s，请先装 platforms;android-34" % PLATFORM)
    ensure_keystore()
    build()


if __name__ == "__main__":
    main()
