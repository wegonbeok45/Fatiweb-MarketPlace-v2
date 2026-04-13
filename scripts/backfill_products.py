#!/usr/bin/env python3
import argparse
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any, Dict, Optional

import jwt
from google.cloud import firestore
from google.oauth2.credentials import Credentials


ROOT = Path(__file__).resolve().parents[1]
SEED_PATH = ROOT / "app" / "src" / "main" / "assets" / "products_seed.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Backfill malformed Firestore product documents for FatiWeb."
    )
    parser.add_argument(
        "--service-account",
        type=Path,
        help="Path to a Firebase service account JSON file.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print planned changes without writing them to Firestore.",
    )
    return parser.parse_args()


def resolve_service_account(explicit_path: Optional[Path]) -> Path:
    candidates = []
    if explicit_path:
        candidates.append(explicit_path)
    candidates.extend(
        [
            ROOT / "service_account.json",
            *ROOT.glob("*firebase-adminsdk-*.json"),
            *ROOT.glob("*firebase-adminsdk*.json"),
        ]
    )
    for candidate in candidates:
        if candidate and candidate.exists():
            return candidate
    raise FileNotFoundError(
        "No Firebase service account JSON found. Pass --service-account explicitly."
    )


def load_service_account(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def fetch_server_epoch() -> int:
    request = urllib.request.Request("https://www.google.com/generate_204")
    with urllib.request.urlopen(request, timeout=20) as response:
        return int(parsedate_to_datetime(response.headers["Date"]).timestamp()) - 60


def build_access_token(service_account: Dict[str, Any]) -> str:
    now = fetch_server_epoch()
    payload = {
        "iss": service_account["client_email"],
        "scope": " ".join(
            [
                "https://www.googleapis.com/auth/cloud-platform",
                "https://www.googleapis.com/auth/datastore",
                "https://www.googleapis.com/auth/firebase",
            ]
        ),
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3500,
    }
    assertion = jwt.encode(payload, service_account["private_key"], algorithm="RS256")
    body = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        }
    ).encode()
    request = urllib.request.Request("https://oauth2.googleapis.com/token", data=body)
    with urllib.request.urlopen(request, timeout=30) as response:
        token_payload = json.loads(response.read().decode("utf-8"))
    return token_payload["access_token"]


def load_seed_data() -> Dict[str, Dict[str, Any]]:
    if not SEED_PATH.exists():
        return {}
    with SEED_PATH.open("r", encoding="utf-8") as fh:
        raw = json.load(fh)
    return {item["id"]: item for item in raw if isinstance(item, dict) and item.get("id")}


def normalize_text(value: Any, fallback: str) -> str:
    text = str(value or "").strip()
    return text if text else fallback


def fallback_subtitle(seed: Dict[str, Any], doc: Dict[str, Any]) -> str:
    return normalize_text(
        doc.get("subtitle"),
        normalize_text(seed.get("subtitle"), "Produit artisanal tunisien"),
    )


def fallback_description(seed: Dict[str, Any], doc: Dict[str, Any], title: str) -> str:
    default = f"{title} presente avec les informations minimales pour rester visible dans le catalogue."
    return normalize_text(doc.get("description"), normalize_text(seed.get("description"), default))


def build_patch(doc_id: str, doc: Dict[str, Any], seed: Dict[str, Any]) -> Dict[str, Any]:
    now = firestore.SERVER_TIMESTAMP
    title = normalize_text(doc.get("title"), normalize_text(seed.get("title"), doc_id.replace("_", " ").title()))
    subtitle = fallback_subtitle(seed, doc)
    description = fallback_description(seed, doc, title)

    image_url = normalize_text(doc.get("imageUrl"), normalize_text(seed.get("imageUrl"), ""))
    image_urls = doc.get("imageUrls")
    if not isinstance(image_urls, list):
        image_urls = []
    image_urls = [str(item).strip() for item in image_urls if str(item).strip()]
    if not image_urls and image_url:
        image_urls = [image_url]

    patch: Dict[str, Any] = {}
    expected = {
        "title": title,
        "subtitle": subtitle,
        "description": description,
        "imageUrl": image_url,
        "imageUrls": image_urls,
        "isActive": bool(doc.get("isActive", True)),
    }

    for key, value in expected.items():
        if doc.get(key) != value:
            patch[key] = value

    if doc.get("createdAt") in (None, ""):
        patch["createdAt"] = now
    patch["updatedAt"] = now
    return patch


def main() -> None:
    args = parse_args()
    service_account = resolve_service_account(args.service_account)
    service_account_data = load_service_account(service_account)
    seed_by_id = load_seed_data()
    access_token = build_access_token(service_account_data)
    db = firestore.Client(
        project=service_account_data["project_id"],
        credentials=Credentials(access_token),
    )
    docs = list(db.collection("products").stream())

    scanned = 0
    updated = 0
    skipped = 0

    print(f"[{datetime.now(timezone.utc).isoformat()}] Scanning {len(docs)} product documents")
    for snapshot in docs:
        scanned += 1
        current = snapshot.to_dict() or {}
        patch = build_patch(snapshot.id, current, seed_by_id.get(snapshot.id, {}))
        if not patch:
            skipped += 1
            continue

        if args.dry_run:
            print(f"DRY-RUN {snapshot.id}: {json.dumps({k: str(v) for k, v in patch.items()}, ensure_ascii=False)}")
        else:
            snapshot.reference.set(patch, merge=True)
            print(f"UPDATED {snapshot.id}: {', '.join(sorted(patch.keys()))}")
        updated += 1

    print(
        json.dumps(
            {
                "scanned": scanned,
                "updated": updated,
                "skipped": skipped,
                "dry_run": args.dry_run,
                "service_account": str(service_account),
                "project_id": service_account_data["project_id"],
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
