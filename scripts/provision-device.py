"""ADB debug-build account provisioning; never pass a password as an argument.

Interactive: python scripts/provision-device.py --host pbx.example.invalid --port 5060 --user extension --transport TCP
Automation: pipe a complete JSON config into this script with --stdin.
The import file lives only in the app's private directory and is deleted after reading.
"""
import argparse
import getpass
import json
import re
import shlex
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--host")
parser.add_argument("--port", type=int)
parser.add_argument("--user")
parser.add_argument("--transport", choices=["UDP", "TCP", "TLS"])
parser.add_argument("--serial", help="ADB device serial when multiple devices are connected")
parser.add_argument("--stdin", action="store_true")
args = parser.parse_args()
if args.stdin:
    config = json.load(sys.stdin)
else:
    missing = [name for name in ("host", "port", "user", "transport") if not getattr(args, name)]
    if missing:
        parser.error("Interactive mode requires " + ", ".join("--" + name for name in missing))
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    config = dict(host=args.host, port=args.port, user=args.user, password=getpass.getpass("SIP password (hidden): "), transport=args.transport, enabled=True, directBoot=False)
package = "dev.mysip"
adb = ["adb"] + (["-s", args.serial] if args.serial else [])
info = subprocess.check_output(adb + ["shell", "cmd", "package", "list", "packages", "-U", package], text=True)
match = re.search(r"package:dev\.mysip uid:(\d+)", info)
if not match:
    raise SystemExit("Install the debug APK first")
uid = match[1]
directory = f"/data/user_de/0/{package}/files"
script = f"umask 077; mkdir -p {directory}; cat > {directory}/provision.json; chown {uid}:{uid} {directory} {directory}/provision.json; restorecon -R {directory}"
command = "am broadcast --receiver-foreground -a dev.mysip.DEBUG -n dev.mysip/.DebugReceiver --es op import"
try:
    subprocess.run(adb + ["shell", "su -c " + shlex.quote(script)], input=json.dumps(config).encode(), check=True)
    result = subprocess.run(adb + ["shell", "su -c " + shlex.quote(command)], capture_output=True, text=True, check=True)
    if "Configuration imported" not in result.stdout:
        raise SystemExit("Account import failed; check the debug build and device permissions")
    print("Configuration imported")
finally:
    # Also remove the private import file if the debug receiver could not run.
    cleanup = "rm -f " + directory + "/provision.json"
    subprocess.run(adb + ["shell", "su -c " + shlex.quote(cleanup)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
