import requests
import json

API_KEY = "AIzaSyDGs3c9fSw_BcuX2fk6BS_PI0FQMNXvLZo"
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={API_KEY}"

# Simulating Android's ChatMessage list with consecutive user messages
history = [
    {"role": "user", "text": "Bonjour, avez-vous des épices ?"},
    {"role": "model", "text": "Oui, nous avons du safran bio et du curcuma. Comment puis-je vous aider ?\n\n(Ceci est une réponse du simulateur)"},
    # These two consecutive 'user' roles caused the 400 Bad Request bug
    {"role": "user", "text": "Super, je voudrais tester le safran."},
    {"role": "user", "text": "Est-ce qu'il est d'origine tunisienne ?"}
]

# Simulate GeminiChatService logic (collapsing)
contents = []
contents.append({
    "role": "user",
    "parts": [{"text": "[CONTEXT SYSTÈME — NE PAS MENTIONNER]\nTu es FatiBot, assistant de FatiWeb. Les produits sont le Safran (Tune) et le Curcuma."}]
})
contents.append({
    "role": "model",
    "parts": [{"text": "Compris."}]
})

current_role = None
current_text = ""

for msg in history:
    if current_role is None:
        current_role = msg["role"]
        current_text = msg["text"]
    elif current_role == msg["role"]:
        # COLLAPSE consecutive identical roles!
        current_text += "\n\n" + msg["text"]
    else:
        contents.append({
            "role": current_role,
            "parts": [{"text": current_text}]
        })
        current_role = msg["role"]
        current_text = msg["text"]

if current_role is not None:
    contents.append({
        "role": current_role,
        "parts": [{"text": current_text}]
    })

payload = {
    "contents": contents,
    "generationConfig": {
        "temperature": 0.7,
        "maxOutputTokens": 512
    }
}

print("Payload sent to Gemini (Notice the collapsed user message block!):")
print(json.dumps(contents[-1], indent=2))

print("\n--- Waiting for Gemini's response ---\n")

try:
    response = requests.post(ENDPOINT, json=payload, headers={"Content-Type": "application/json; charset=utf-8"})
    
    if response.status_code == 200:
        data = response.json()
        model_reply = data["candidates"][0]["content"]["parts"][0]["text"]
        print(f"✅ SUCCESS! [200 OK]")
        print(f"FatiBot Reply:\n{model_reply}")
    else:
        print(f"❌ FAILED! [{response.status_code}]")
        print(response.text)
except Exception as e:
    print(f"Error: {e}")
