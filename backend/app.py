from flask import Flask, request, jsonify
from model_inference import predict
from price_lookup import get_base_price
from chatbot import chat
import requests as http_requests
import json
import os

app = Flask(__name__)

GROQ_API_KEY = os.environ.get("GROQ_API_KEY")


def get_ai_price_estimate(detected_class, confidence, description, city, base_price, lang="en"):
    urdu_note = "\nIMPORTANT: Write the 'justification' value in Urdu language only." if lang == "ur" else ""
    prompt = f"""You are a Pakistani home repair pricing expert in {city}.

Job details:
- Damage type: {detected_class.replace('_', ' ')} (confidence: {confidence:.0%})
- Customer description: "{description}"
- Real market data from {base_price['sample_count']} bids in {city}:
    Lowest bid: PKR {base_price['min']}
    Highest bid: PKR {base_price['max']}
    Average bid: PKR {base_price['mean']}

Your task: estimate min, max, and recommended price for THIS specific job.
Rules:
- Your numbers MUST be within PKR {base_price['min']} and PKR {base_price['max']}
- Adjust within that range based on severity in the description
- Mild damage = closer to PKR {base_price['min']}, severe = closer to PKR {base_price['max']}{urdu_note}

Reply with ONLY this JSON, no other text:
{{"min": PUT_MIN_HERE, "max": PUT_MAX_HERE, "recommended": PUT_RECOMMENDED_HERE, "justification": "PUT_ONE_SENTENCE_HERE"}}"""

    try:
        res = http_requests.post(
            "https://api.groq.com/openai/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {GROQ_API_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "model": "llama-3.3-70b-versatile",
                "messages": [{"role": "user", "content": prompt}],
                "temperature": 0.3,
            },
            timeout=10,
        )
        content = res.json()["choices"][0]["message"]["content"]
        return json.loads(content)
    except Exception:
        # Always fall back to Excel data — never crash the /predict route
        return {
            "min":           base_price["min"],
            "max":           base_price["max"],
            "recommended":   base_price["mean"],
            "justification": f"Based on {base_price['sample_count']} recent bids in {city}",
        }


@app.route("/predict", methods=["POST"])
def predict_route():
    if "image" not in request.files:
        return jsonify({"error": "No image provided"}), 400

    description = request.form.get("description", "No description provided")
    city        = request.form.get("city", "Karachi")
    lang        = request.form.get("lang", "en")

    result     = predict(request.files["image"].read())
    base_price = get_base_price(result["service"], city)
    ai_price   = get_ai_price_estimate(
        result["detected"], result["confidence"],
        description, city, base_price, lang,
    )

    return jsonify({
        **result,
        "price_min":         ai_price["min"],
        "price_max":         ai_price["max"],
        "price_recommended": ai_price["recommended"],
        "price_reason":      ai_price["justification"],
        "sample_count":      base_price["sample_count"],
    })


@app.route("/chat", methods=["POST"])
def chat_route():
    data     = request.get_json()
    messages = data.get("messages", []) if data else []
    if not messages:
        return jsonify({"error": "No messages provided"}), 400
    reply = chat(messages)
    return jsonify({"reply": reply})


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 7860))
    app.run(host="0.0.0.0", port=port, debug=False)
