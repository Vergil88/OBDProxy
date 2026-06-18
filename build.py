#!/usr/bin/env python3
"""Fixed build script: properly handles paths for Android APK"""
import subprocess, os, shutil, zipfile, glob, datetime

PROJ = r"D:\DKE\OBDProxyApp"
SRC = os.path.join(PROJ, "app", "src", "main")
BUILD = os.path.join(PROJ, "app", "build")
SDK = r"C:\Program Files (x86)\Android\android-sdk"
ANDROID_JAR = os.path.join(SDK, "platforms", "android-36", "android.jar")
BT = os.path.join(SDK, "build-tools", "36.0.0")
JAVA_BIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin"
PKG = "com.obdproxy"
# Use forward-slash style path for package directory
PKG_PATH = PKG.replace(".", "/")

os.makedirs(os.path.join(BUILD, "javac"), exist_ok=True)
os.makedirs(os.path.join(BUILD, "dex"), exist_ok=True)
os.makedirs(os.path.join(BUILD, "apk"), exist_ok=True)

def run(cmd, desc, timeout=120):
    print(f"\n[{desc}]")
    print(f"  CMD: {' '.join(cmd)}")
    r = subprocess.run(cmd, capture_output=False, text=True, timeout=timeout)
    if r.returncode != 0:
        print(f"  FAILED with code {r.returncode}")
        raise SystemExit(1)
    print(f"  OK")
    return r

# Step 1: aapt2 compile
RES_ZIP = os.path.join(BUILD, "apk", "res.zip")
run([os.path.join(BT, "aapt2.exe"), "compile", "--dir", os.path.join(SRC, "res"), "-o", RES_ZIP], "aapt2 compile")

# Step 2: aapt2 link
APK_UNSIGNED = os.path.join(BUILD, "apk_unsigned.apk")
JAVA_GEN = os.path.join(BUILD, "javac")
MANIFEST = os.path.join(SRC, "AndroidManifest.xml")
run([os.path.join(BT, "aapt2.exe"), "link",
     "-o", APK_UNSIGNED,
     "-I", ANDROID_JAR,
     "--manifest", MANIFEST,
     "--java", JAVA_GEN,
     RES_ZIP], "aapt2 link")

# Step 3: javac
R_JAVA = os.path.join(JAVA_GEN, PKG_PATH, "R.java")
MAIN_JAVA = os.path.join(SRC, "java", PKG_PATH, "MainActivity.java")
run([os.path.join(JAVA_BIN, "javac.exe"),
     "-encoding", "UTF-8",
     "-d", JAVA_GEN,
     "-cp", ANDROID_JAR,
     R_JAVA, MAIN_JAVA], "javac")

# Step 4: d8 (DEX)
DEX_OUT = os.path.join(BUILD, "dex")
# Use jar + d8 approach
class_files = glob.glob(os.path.join(JAVA_GEN, PKG_PATH, "*.class"))
print(f"  Found {len(class_files)} class files")
jar_input = os.path.join(DEX_OUT, "input.jar")
run([os.path.join(JAVA_BIN, "jar.exe"), "cf", jar_input] + class_files, "jar")
run([os.path.join(BT, "d8.bat"), "--release", "--lib", ANDROID_JAR,
     "--output", DEX_OUT, jar_input], "d8")

# Step 5: Inject DEX into APK using Python zipfile (preserves forward slashes!)
DEX_FILE = os.path.join(DEX_OUT, "classes.dex")
TMP_DIR = os.path.join(BUILD, "tmp_inject")
shutil.rmtree(TMP_DIR, ignore_errors=True)

with zipfile.ZipFile(APK_UNSIGNED, 'r') as z:
    z.extractall(TMP_DIR)

shutil.copy(DEX_FILE, TMP_DIR)

APK_WITH_DEX = os.path.join(BUILD, "apk_with_dex.apk")
if os.path.exists(APK_WITH_DEX):
    os.remove(APK_WITH_DEX)

# KEY FIX: Use forward slashes for all paths in the ZIP
with zipfile.ZipFile(APK_WITH_DEX, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(TMP_DIR):
        for f in files:
            fp = os.path.join(root, f)
            # Normalize to forward slashes for Android compatibility
            arc = os.path.relpath(fp, TMP_DIR).replace("\\", "/")
            z.write(fp, arc)

shutil.rmtree(TMP_DIR, ignore_errors=True)
print(f"  DEX injected: {os.path.getsize(APK_WITH_DEX)} bytes")

# Step 6: zipalign + sign
APK_ALIGNED = os.path.join(BUILD, "apk_aligned.apk")
if os.path.exists(APK_ALIGNED):
    os.remove(APK_ALIGNED)

run([os.path.join(BT, "zipalign.exe"), "-p", "4", APK_WITH_DEX, APK_ALIGNED], "zipalign")

FINAL_APK = os.path.join(PROJ, "OBDProxy.apk")

# Backup existing APK before overwriting
BACKUP_DIR = os.path.join(PROJ, "apk_backups")
os.makedirs(BACKUP_DIR, exist_ok=True)
if os.path.exists(FINAL_APK):
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_name = f"OBDProxy_{ts}.apk"
    shutil.copy2(FINAL_APK, os.path.join(BACKUP_DIR, backup_name))
    print(f"  Backed up to: {backup_name}")

if os.path.exists(FINAL_APK):
    os.remove(FINAL_APK)

run([os.path.join(BT, "apksigner.bat"), "sign",
     "--ks", r"D:\DKE\debug.keystore",
     "--ks-pass", "pass:android",
     "--ks-key-alias", "debug",
     "--out", FINAL_APK,
     APK_ALIGNED], "apksigner")

print("\n=== BUILD SUCCESS ===")
