#!/usr/bin/env python3
import json
import urllib.error
import urllib.parse
import urllib.request
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import jwt


ROOT = Path(__file__).resolve().parents[1]
PROJECT_ID = "fatiweb-marketplace"
STORAGE_BUCKET = "fatiweb-marketplace.firebasestorage.app"


def load_service_account() -> Dict[str, Any]:
    for candidate in [
        ROOT / "service_account.json",
        *ROOT.glob("*firebase-adminsdk-*.json"),
    ]:
        if candidate.exists():
            return json.loads(candidate.read_text(encoding="utf-8"))
    raise FileNotFoundError("No service account JSON found.")


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
                "https://www.googleapis.com/auth/firebase",
                "https://www.googleapis.com/auth/datastore",
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
        payload = json.loads(response.read().decode("utf-8"))
    return payload["access_token"]


def api_request(
    method: str,
    url: str,
    token: str,
    body: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode("utf-8").strip()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8")
        raise RuntimeError(f"{method} {url} failed: {detail}") from exc


def create_ruleset(token: str, file_name: str) -> str:
    content = (ROOT / file_name).read_text(encoding="utf-8")
    response = api_request(
        "POST",
        f"https://firebaserules.googleapis.com/v1/projects/{PROJECT_ID}/rulesets",
        token,
        body={
            "source": {
                "files": [
                    {
                        "name": file_name,
                        "content": content,
                    }
                ]
            }
        },
    )
    return response["name"]


def upsert_release(token: str, release_name: str, ruleset_name: str) -> Dict[str, Any]:
    release = {
        "name": release_name,
        "rulesetName": ruleset_name,
    }
    patch_url = (
        f"https://firebaserules.googleapis.com/v1/{release_name}"
        "?updateMask=rulesetName"
    )
    try:
        return api_request(
            "PATCH",
            patch_url,
            token,
            body={
                "release": release,
                "updateMask": "rulesetName",
            },
        )
    except RuntimeError as exc:
        if "404" not in str(exc):
            raise
    return api_request(
        "POST",
        f"https://firebaserules.googleapis.com/v1/projects/{PROJECT_ID}/releases",
        token,
        body=release,
    )


def load_index_definitions() -> List[Dict[str, Any]]:
    payload = json.loads((ROOT / "firestore.indexes.json").read_text(encoding="utf-8"))
    return payload.get("indexes", [])


def normalized_index_signature(index: Dict[str, Any]) -> tuple:
    return (
        index["collectionGroup"],
        index["queryScope"],
        tuple(
            (field["fieldPath"], field.get("order"), field.get("arrayConfig"))
            for field in index["fields"]
            if field["fieldPath"] != "__name__"
        ),
    )


def fetch_existing_indexes(token: str, collection_group: str) -> List[Dict[str, Any]]:
    response = api_request(
        "GET",
        "https://firestore.googleapis.com/v1/"
        f"projects/{PROJECT_ID}/databases/(default)/collectionGroups/{collection_group}/indexes",
        token,
    )
    indexes = response.get("indexes", [])
    result = []
    for index in indexes:
        result.append(
            {
                "collectionGroup": collection_group,
                "queryScope": index.get("queryScope", "COLLECTION"),
                "fields": [
                    {
                        key: value
                        for key, value in field.items()
                        if key in {"fieldPath", "order", "arrayConfig"}
                    }
                    for field in index.get("fields", [])
                ],
            }
        )
    return result


def ensure_indexes(token: str) -> List[str]:
    created = []
    wanted = load_index_definitions()
    by_group: Dict[str, List[Dict[str, Any]]] = {}
    for index in wanted:
        by_group.setdefault(index["collectionGroup"], []).append(index)

    for collection_group, group_indexes in by_group.items():
        existing_signatures = {
            normalized_index_signature(index)
            for index in fetch_existing_indexes(token, collection_group)
        }
        for index in group_indexes:
            signature = normalized_index_signature(index)
            if signature in existing_signatures:
                continue
            body = {
                "queryScope": index["queryScope"],
                "fields": index["fields"],
            }
            api_request(
                "POST",
                "https://firestore.googleapis.com/v1/"
                f"projects/{PROJECT_ID}/databases/(default)/collectionGroups/{collection_group}/indexes",
                token,
                body=body,
            )
            created.append(collection_group)
    return created


def main() -> None:
    service_account = load_service_account()
    token = build_access_token(service_account)
    result: Dict[str, Any] = {}

    firestore_ruleset = create_ruleset(token, "firestore.rules")
    firestore_release = upsert_release(
        token,
        f"projects/{PROJECT_ID}/releases/cloud.firestore",
        firestore_ruleset,
    )
    result["firestore_release"] = firestore_release["name"]
    result["firestore_ruleset"] = firestore_ruleset

    try:
        storage_ruleset = create_ruleset(token, "storage.rules")
        storage_release = upsert_release(
            token,
            f"projects/{PROJECT_ID}/releases/firebase.storage/{STORAGE_BUCKET}",
            storage_ruleset,
        )
        result["storage_release"] = storage_release["name"]
        result["storage_ruleset"] = storage_ruleset
    except Exception as exc:
        result["storage_error"] = str(exc)

    try:
        result["created_indexes"] = ensure_indexes(token)
    except Exception as exc:
        result["index_error"] = str(exc)

    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
