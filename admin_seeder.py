import json
import requests
import os
import sys
import time

API_KEY = "AIzaSyCBmcuN_SGTVJ08K4f1y5Jix7WsXEbcnug"
PROJECT_ID = "fatiweb-marketplace"

seed_file = "app/src/main/assets/products_seed.json"
if not os.path.exists(seed_file):
    print("Seed file not found")
    sys.exit(1)

with open(seed_file, "r", encoding="utf-8") as f:
    products = json.load(f)

def to_fs(val):
    if isinstance(val, str): return {"stringValue": val}
    if isinstance(val, bool): return {"booleanValue": val}
    if isinstance(val, int): return {"integerValue": str(val)}
    if isinstance(val, float): return {"doubleValue": val}
    if isinstance(val, list): return {"arrayValue": {"values": [to_fs(v) for v in val]}}
    if val is None: return {"nullValue": None}
    return {"stringValue": str(val)}

total_success = 0
total = len(products)
print(f"Starting seeding of {total} products via REST API...")

for i, obj in enumerate(products):
    pid = obj["id"]
    title = obj["title"]
    subtitle = obj.get("subtitle", "")
    description = obj.get("description", "")
    category = obj.get("category", "")
    price = float(obj.get("price", 0))
    stock = int(obj.get("stock", 0))
    origin = obj.get("origin", "")
    source_url = obj.get("sourceImageUrl", "")
    is_bio = obj.get("isBio", False)
    tags = obj.get("tags", [])
    bullets = obj.get("bullets", [])

    print(f"[{i+1}/{total}] Uploading {pid}...")

    # For safety, use sourceImageUrl directly 
    uploaded_url = source_url

    search_keywords = [w for w in title.lower().split(" ") if len(w) > 2]
    search_keywords.extend(subtitle.lower().split(" "))
    search_keywords.append(category.lower())
    search_keywords = list(set(search_keywords))

    product_data = {
        "id": pid,
        "title": title,
        "subtitle": subtitle,
        "price": price,
        "rating": 0.0,
        "reviewsCount": 0,
        "tags": tags,
        "description": description,
        "bullets": bullets,
        "imageUrl": uploaded_url,
        "imageUrls": [uploaded_url],
        "category": category,
        "categoryIds": [category],
        "origin": origin,
        "stock": stock,
        "isBio": is_bio,
        "isActive": True,
        "status": "published",
        "searchKeywords": search_keywords,
        "createdAt": int(time.time() * 1000),
        "updatedAt": int(time.time() * 1000)
    }

    # Add timestamp using serverValue
    fields = {k: to_fs(v) for k, v in product_data.items()}
    
    doc_payload = {
        "name": f"projects/{PROJECT_ID}/databases/(default)/documents/products/{pid}",
        "fields": fields
    }

    url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents/products?documentId={pid}&key={API_KEY}"
    
    res = requests.patch(
        f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents/products/{pid}?key={API_KEY}",
        json=doc_payload
    )
    
    if res.status_code in [200, 201]:
        total_success += 1
    else:
        print(f"FAILED {pid}: {res.text}")

print(f"Seeding complete! Total products written: {total_success}")
