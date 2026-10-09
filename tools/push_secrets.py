#!/usr/bin/env python3
"""把签名凭据写入 GitHub Actions Secrets（值只走内存，不打印）。"""
import base64, json, os, subprocess, sys
from nacl.public import SealedBox, PublicKey
from nacl.encoding import Base64Encoder

REPO = "7tattoo/SamsungMusicRC"
TOKEN = os.environ["GITHUB_TOKEN"]
PROP = "/var/minis-euleros/workspace/SamsungMusicRC/local.properties"
JKS = "/var/minis-euleros/workspace/SamsungMusicRC/app/7tattoo.jks"


def gh(method, path, body=None):
    cmd = ["curl", "-sS", "-X", method,
           "-H", f"Authorization: Bearer {TOKEN}",
           "-H", "Accept: application/vnd.github+json",
           "https://api.github.com" + path]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"curl 失败: {r.stderr[:200]}")
    try:
        return json.loads(r.stdout or "{}")
    except Exception:
        sys.exit(f"响应非 JSON: {r.stdout[:200]}")


props = {}
for line in open(PROP, encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()

pk = gh("GET", f"/repos/{REPO}/actions/secrets/public-key")
key_id, key_b64 = pk["key_id"], pk["key"]
print("public key id:", key_id)


def put(name, value):
    sealed = SealedBox(PublicKey(key_b64, encoder=Base64Encoder)).encrypt(value.encode())
    res = gh("PUT", f"/repos/{REPO}/actions/secrets/{name}",
             {"encrypted_value": base64.b64encode(sealed).decode(), "key_id": key_id})
    print(f"{name}: {'写入成功' if res == {} else res}")


with open(JKS, "rb") as f:
    jks_b64 = base64.b64encode(f.read()).decode()

put("SIGNING_JKS_BASE64", jks_b64)
put("SIGNING_KEYSTORE_PASSWORD", props["key.store.password"])
put("SIGNING_KEY_PASSWORD", props["key.alias.password"])
put("SIGNING_KEY_ALIAS", props.get("key.alias", "7tattoo"))

lst = gh("GET", f"/repos/{REPO}/actions/secrets")
print("当前 secrets 列表:", [s["name"] for s in lst.get("secrets", [])])
