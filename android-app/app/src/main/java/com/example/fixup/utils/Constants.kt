    package com.example.fixup.utils

object Constants {
    const val COLLECTION_USERS    = "users"
    const val COLLECTION_REQUESTS = "serviceRequests"
    const val COLLECTION_BIDS     = "bids"
    const val COLLECTION_CHATS    = "chats"
    const val COLLECTION_RATINGS  = "ratings"
    const val FLASK_BASE_URL      = "https://hamzafixup-fixup-backend.hf.space"
    const val FLASK_PREDICT       = "/predict"
    const val FLASK_CHAT          = "/chat"
    const val ROLE_CUSTOMER       = "customer"
    const val ROLE_VENDOR         = "vendor"
    const val ROLE_ADMIN          = "admin"
    const val DEFAULT_CITY        = "Karachi"
    const val MAX_DISTANCE_KM     = 5.0

    val DETECTED_CLASS_URDU = mapOf(
        "wall_crack"       to "دیوار کی دراڑ",
        "peeling_paint"    to "اکھڑتا ہوا پینٹ",
        "spalling"         to "سطح کا گرنا",
        "algae"            to "کائی / نمی",
        "electrical_fault" to "بجلی کی خرابی"
    )

    val SERVICE_CATEGORY_URDU = mapOf(
        "Plastering / Mason" to "پلاسٹر / مزدور",
        "Painter"            to "پینٹر",
        "Electrician"        to "الیکٹریشن",
        "Plumber / Mason"    to "پلمبر / مزدور",
        "General Handyman"   to "عام مستری"
    )
}
