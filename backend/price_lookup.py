import pandas as pd
import os

_base_dir = os.path.dirname(os.path.abspath(__file__))

try:
    df = pd.read_csv(os.path.join(_base_dir, "bids_data.csv"))
except Exception:
    df = pd.read_excel(os.path.join(_base_dir, "bids_data.xlsx"), engine="openpyxl")


def get_base_price(service_category, city="Karachi"):
    row = df[(df["service_category"] == service_category) & (df["city"] == city)]
    if row.empty:
        return {"min": 500, "max": 5000, "mean": 2000, "sample_count": 0}
    return {
        "min":          int(row["min_price"].values[0]),
        "max":          int(row["max_price"].values[0]),
        "mean":         int(row["mean_price"].values[0]),
        "sample_count": int(row["sample_count"].values[0]),
    }
