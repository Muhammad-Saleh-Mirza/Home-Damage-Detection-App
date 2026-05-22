# FixUp — Home Services Marketplace

A two-sided Android marketplace connecting homeowners with verified service providers in Pakistan.

**Customer photographs a damaged wall → AI detects damage type → service category is auto-suggested → price is estimated using AI + market data → request is posted → vendors bid → customer accepts the best bid.**

---

## Features

- **AI Damage Detection** — YOLOv8n-cls model classifies wall damage into 5 categories (wall crack, peeling paint, spalling, algae, electrical fault)
- **Smart Price Estimation** — Combines real bid data from an Excel dataset with a Grok AI call for dynamic, context-aware pricing in PKR
- **Two-sided marketplace** — Customers post requests; vendors browse and submit bids in real time
- **Real-time chat** — Customer ↔ vendor messaging per job (Firebase Firestore)
- **FixUp Chatbot** — Grok-powered assistant for damage identification, price advice, bid comparison, and urgency triage — responds in Urdu and English
- **Admin dashboard** — Manage vendors (suspend/activate), view all requests and platform stats
- **Vendor rating system** — Customers rate vendors after job completion; ratings update vendor averages

---

## Tech Stack

| Layer | Technology |
|---|---|
| Mobile App | Android (Kotlin) |
| Database | Firebase Firestore |
| Auth | Firebase Authentication |
| File Storage | Firebase Storage |
| Backend API | Python Flask |
| AI Damage Model | YOLOv8n-cls (5 classes) |
| Price Estimation | Grok API + Excel base data (pandas) |
| Chatbot | Grok API (FixUp-specific) |
| Backend Hosting | Hugging Face Spaces |

---

## Project Structure

```
FixUp/
├── android-app/                  # Android Studio project (Kotlin)
│   └── app/src/main/java/com/example/fixup/
│       ├── auth/                 # Splash, Login, Register
│       ├── customer/             # Home, PostRequest, Bids, RequestStatus, RateVendor
│       ├── vendor/               # VendorDashboard, RequestDetail
│       ├── admin/                # AdminDashboard, ManageVendors, AllRequests
│       ├── shared/               # ChatActivity, ChatbotActivity
│       └── utils/                # Constants, ImageUtils, NetworkUtils
├── backend/                      # Python Flask API
│   ├── app.py                    # All routes (/predict, /chat, /health)
│   ├── model_inference.py        # YOLOv8 inference logic
│   ├── price_lookup.py           # Excel-based base price lookup
│   ├── chatbot.py                # Grok chatbot logic
│   ├── bids_data.xlsx            # Seed price data (5 service categories)
│   ├── requirements.txt
│   └── model_weights/            # Place best.pt here (not in repo)
└── README.md
```

---

## Setup

### Prerequisites
- Android Studio (Hedgehog or newer)
- Python 3.10+
- A Firebase project with Auth, Firestore, and Storage enabled
- A Grok API key from [x.ai](https://x.ai)

### 1. Firebase Setup

1. Go to [console.firebase.google.com](https://console.firebase.google.com) → create a project named **FixUp**
2. Enable **Authentication** (Email/Password)
3. Enable **Firestore Database**
4. Enable **Storage**
5. Add an Android app → package name: `com.example.fixup`
6. Download `google-services.json` and place it in `android-app/app/`

### 2. Backend Setup

```bash
cd backend
pip install -r requirements.txt
```

Place your trained `best.pt` model in `backend/model_weights/`.

Set your Grok API key as an environment variable:
```bash
export GROK_API_KEY=your_key_here
```

Run locally:
```bash
python app.py
```

Or deploy to **Hugging Face Spaces** (recommended) — push the `backend/` folder and set `GROK_API_KEY` as a Space secret.

### 3. Android Setup

1. Open `android-app/` in Android Studio
2. Make sure `google-services.json` is in `android-app/app/`
3. In `utils/Constants.kt`, set your Hugging Face Space URL:
   ```kotlin
   const val FLASK_BASE_URL = "https://YOUR-SPACE.hf.space"
   ```
4. Build and run on a device or emulator (Min SDK 24)

---

## AI Model

The YOLOv8n-cls model is trained on ~3,600 images across 5 classes:

| Class | Service | Dataset Source |
|---|---|---|
| `wall_crack` | Plastering / Mason | BD3 Major + Minor Crack |
| `peeling_paint` | Painter | BD3 Peeling |
| `spalling` | Plastering / Mason | BD3 Spalling |
| `algae` | Plumber / Mason | BD3 Algae |
| `electrical_fault` | Electrician | Electrical hazards dataset |

Training notebook: `backend/ml-model/train_fixup_yolov8.ipynb`

The trained `best.pt` is **not included in this repo** due to file size. Train your own using the notebook or contact the author.

---

## Demo Accounts

Create these manually in Firebase Console after setup:

| Role | Email | Password |
|---|---|---|
| Admin | admin@fixup.pk | Admin@123 |
| Vendor (Mason) | mason@fixup.pk | Vendor@123 |
| Vendor (Electrician) | electrician@fixup.pk | Vendor@123 |
| Vendor (Painter) | painter@fixup.pk | Vendor@123 |
| Customer | customer@fixup.pk | Customer@123 |

After creating each user in Firebase Auth, manually add their Firestore document under `users/{uid}` with `role`, `serviceCategory` (vendors), `isActive: true`, `rating: 0`, `totalJobs: 0`.

---

## API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| POST | `/predict` | Upload image → returns detected class, service, confidence, price range |
| POST | `/chat` | Send message history → returns chatbot reply |
| GET | `/health` | Health check |

---

## License

This project was built as a Final Year Project (FYP). All rights reserved.
