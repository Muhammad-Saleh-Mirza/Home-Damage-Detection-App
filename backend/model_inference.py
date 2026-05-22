from ultralytics import YOLO
from PIL import Image
import io
import os

_model_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "model_weights", "best.pt")
model = YOLO(_model_path)

CATEGORY_MAP = {
    "wall_crack":       {"service": "Plastering / Mason", "icon": "🧱"},
    "peeling_paint":    {"service": "Painter",            "icon": "🎨"},
    "peeling":          {"service": "Painter",            "icon": "🎨"},
    "spalling":         {"service": "Plastering / Mason", "icon": "🧱"},
    "algae":            {"service": "Plumber / Mason",    "icon": "💧"},
    "electrical_fault": {"service": "Electrician",        "icon": "⚡"},
}


def predict(image_bytes):
    img     = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    results = model(img)
    probs   = results[0].probs
    names   = results[0].names

    top1_name = names[probs.top1]
    top1_conf = probs.top1conf.item()
    top2_name = names[probs.top5[1]]
    top2_conf = probs.top5conf[1].item()

    # Safety override: spalling vs electrical close call → prefer electrical
    if top1_name == "spalling" and top2_name == "electrical_fault":
        if (top1_conf - top2_conf) < 0.20:
            top1_name = "electrical_fault"
            top1_conf = top2_conf

    if top1_conf < 0.50:
        return {
            "detected":   top1_name,
            "service":    "Plastering / Mason",
            "icon":       "🧱",
            "confidence": round(top1_conf, 2),
            "fallback":   True,
        }

    mapped = CATEGORY_MAP.get(top1_name, {"service": "Plastering / Mason", "icon": "🧱"})
    return {
        "detected":   top1_name,
        "service":    mapped["service"],
        "icon":       mapped["icon"],
        "confidence": round(top1_conf, 2),
        "fallback":   False,
    }
