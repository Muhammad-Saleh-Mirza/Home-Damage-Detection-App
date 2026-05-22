import requests
import os

GROQ_API_KEY = os.environ.get("GROQ_API_KEY")

SYSTEM_PROMPT = """You are FixUp Assistant, a helpful chatbot for the FixUp home services app in Pakistan.
You help customers with home repair problems. You can:
1. Help identify what type of damage they have and which service they need
2. Give honest price advice based on Pakistani market rates (Karachi)
3. Help them choose between vendor bids (advise on rating vs price tradeoff)
4. Triage urgency — tell them if something needs immediate attention
5. Give post-repair guidance if problems persist

Market rates reference (Karachi):
- Plastering / Mason: PKR 800–3500
- Electrician: PKR 600–4000
- Painter: PKR 2000–12000
- Plumber / Mason: PKR 500–2500
- General Handyman: PKR 500–3000

Always respond in the same language the user writes in (Urdu or English).
Keep responses concise — max 3-4 sentences. Be friendly and practical.
Never recommend specific vendors by name. Never discuss topics unrelated to home repair."""


def chat(messages):
    if not GROQ_API_KEY:
        return "Chatbot is not configured. Please contact support."
    try:
        res = requests.post(
            "https://api.groq.com/openai/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {GROQ_API_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "model": "llama-3.3-70b-versatile",
                "messages": [{"role": "system", "content": SYSTEM_PROMPT}] + messages,
                "temperature": 0.7,
            },
            timeout=15,
        )
        if res.status_code != 200:
            print("GROQ ERROR:", res.text)
            return f"API Error ({res.status_code}). Please try again."

        return res.json()["choices"][0]["message"]["content"]

    except requests.exceptions.Timeout:
        return "Request timed out. Please try again."
    except requests.exceptions.ConnectionError:
        return "Connection error. Check server/network."
    except Exception as e:
        print("UNEXPECTED ERROR:", str(e))
        return "Sorry, I'm having trouble connecting. Please try again in a moment."
