package com.dostup.app.vpn

/** Where traffic leaves Cloudflare, parsed from a cdn-cgi/trace response. */
data class ExitInfo(val colo: String, val country: String, val ip: String, val warp: Boolean) {
    val city: String? get() = CITIES[colo]

    /**
     * Traffic leaving Cloudflare here crosses Russian networks and DPI again, which is why
     * Telegram over IPv4 dies on such routes. Measured by [colo], not [country]: Cloudflare
     * reports loc=RU for Russian users even when the exit is in Helsinki.
     */
    val inRussia: Boolean get() = colo in RUSSIAN_COLOS

    /** "ورشو, PL" or just the code when the city is unknown. */
    val label: String get() = listOfNotNull(city ?: colo, country.takeIf { it.isNotBlank() }).joinToString(", ")

    companion object {
        fun parse(body: String): ExitInfo? {
            val fields = body.lineSequence()
                .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }
                .toMap()
            val colo = fields["colo"] ?: return null
            return ExitInfo(
                colo = colo,
                country = fields["loc"].orEmpty(),
                ip = fields["ip"].orEmpty(),
                warp = fields["warp"] == "on" || fields["warp"] == "plus",
            )
        }

        val RUSSIAN_COLOS = setOf("DME", "SVO", "VKO", "LED", "KJA", "SVX", "OVB", "KZN", "ROV", "AER", "KHV", "VVO")

        /** Cloudflare data centres reachable from Russia and nearby, by IATA code. */
        val CITIES = mapOf(
            "WAW" to "ورشو", "FRA" to "فرانکفورت", "HEL" to "هلسینکی", "ARN" to "استکهلم",
            "AMS" to "آمستردام", "VIE" to "وین", "PRG" to "پراگ", "BUD" to "بوداپست",
            "RIX" to "ریگا", "TLL" to "تالین", "VNO" to "ویلنیوس", "DME" to "مسکو",
            "LED" to "سن‌پترزبورگ", "KJA" to "کراسنویارسک", "SVX" to "یکاترینبورگ", "OVB" to "نووسیبیرسک",
            "OTP" to "بخارست", "SOF" to "صوفیه", "BEG" to "بلگراد", "KIV" to "کیشینف",
            "DUS" to "دوسلدورف", "HAM" to "هامبورگ", "MUC" to "مونیخ", "BER" to "برلین", "TXL" to "برلین",
            "CPH" to "کپنهاگ", "OSL" to "اسلو", "LHR" to "لندن", "MAN" to "منچستر", "CDG" to "پاریس",
            "MRS" to "مارسی", "MXP" to "میلان", "FCO" to "رم", "ZRH" to "زوریخ", "GVA" to "ژنو",
            "IST" to "استانبول", "EVN" to "ایروان", "TBS" to "تفلیس", "GYD" to "باکو", "ALA" to "آلماتی",
            "TAS" to "تاشکند", "KBP" to "کیف", "MSQ" to "مینسک", "ATH" to "آتن", "LIS" to "لیسبون",
            "MAD" to "مادرید", "BCN" to "بارسلون", "BRU" to "بروکسل", "DUB" to "دوبلین",
        )
    }
}
