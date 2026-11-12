package com.dostup.app.vpn

/** Where traffic leaves Cloudflare, parsed from a cdn-cgi/trace response. */
data class ExitInfo(val colo: String, val country: String, val ip: String, val warp: Boolean) {
    /** The exit city in Russian for [language] "ru", in Persian for "fa", in English otherwise; null when unknown. */
    fun city(language: String): String? = CITIES[colo]?.let {
        when (language) {
            "ru" -> it.ru
            "fa" -> FA_CITIES[colo] ?: it.en
            else -> it.en
        }
    }

    /**
     * Traffic leaving Cloudflare here crosses Russian networks and DPI again, which is why
     * Telegram over IPv4 dies on such routes. Measured by [colo], not [country]: Cloudflare
     * reports loc=RU for Russian users even when the exit is in Helsinki.
     */
    val inRussia: Boolean get() = colo in RUSSIAN_COLOS

    class City(val en: String, val ru: String)

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
            "WAW" to City("Warsaw", "Варшава"), "FRA" to City("Frankfurt", "Франкфурт"),
            "HEL" to City("Helsinki", "Хельсинки"), "ARN" to City("Stockholm", "Стокгольм"),
            "AMS" to City("Amsterdam", "Амстердам"), "VIE" to City("Vienna", "Вена"),
            "PRG" to City("Prague", "Прага"), "BUD" to City("Budapest", "Будапешт"),
            "RIX" to City("Riga", "Рига"), "TLL" to City("Tallinn", "Таллин"),
            "VNO" to City("Vilnius", "Вильнюс"), "DME" to City("Moscow", "Москва"),
            "LED" to City("Saint Petersburg", "Санкт-Петербург"), "KJA" to City("Krasnoyarsk", "Красноярск"),
            "SVX" to City("Yekaterinburg", "Екатеринбург"), "OVB" to City("Novosibirsk", "Новосибирск"),
            "OTP" to City("Bucharest", "Бухарест"), "SOF" to City("Sofia", "София"),
            "BEG" to City("Belgrade", "Белград"), "KIV" to City("Chișinău", "Кишинёв"),
            "DUS" to City("Düsseldorf", "Дюссельдорф"), "HAM" to City("Hamburg", "Гамбург"),
            "MUC" to City("Munich", "Мюнхен"), "BER" to City("Berlin", "Берлин"), "TXL" to City("Berlin", "Берлин"),
            "CPH" to City("Copenhagen", "Копенгаген"), "OSL" to City("Oslo", "Осло"),
            "LHR" to City("London", "Лондон"), "MAN" to City("Manchester", "Манчестер"),
            "CDG" to City("Paris", "Париж"), "MRS" to City("Marseille", "Марсель"),
            "MXP" to City("Milan", "Милан"), "FCO" to City("Rome", "Рим"),
            "ZRH" to City("Zurich", "Цюрих"), "GVA" to City("Geneva", "Женева"),
            "IST" to City("Istanbul", "Стамбул"), "EVN" to City("Yerevan", "Ереван"),
            "TBS" to City("Tbilisi", "Тбилиси"), "GYD" to City("Baku", "Баку"),
            "ALA" to City("Almaty", "Алматы"), "TAS" to City("Tashkent", "Ташкент"),
            "KBP" to City("Kyiv", "Киев"), "MSQ" to City("Minsk", "Минск"),
            "ATH" to City("Athens", "Афины"), "LIS" to City("Lisbon", "Лиссабон"),
            "MAD" to City("Madrid", "Мадрид"), "BCN" to City("Barcelona", "Барселона"),
            "BRU" to City("Brussels", "Брюссель"), "DUB" to City("Dublin", "Дублин"),
        )

        /** Persian names of the same data centres; an entry missing here falls back to English. */
        val FA_CITIES = mapOf(
            "WAW" to "ورشو",
            "FRA" to "فرانکفورت",
            "HEL" to "هلسینکی",
            "ARN" to "استکهلم",
            "AMS" to "آمستردام",
            "VIE" to "وین",
            "PRG" to "پراگ",
            "BUD" to "بوداپست",
            "RIX" to "ریگا",
            "TLL" to "تالین",
            "VNO" to "ویلنیوس",
            "DME" to "مسکو",
            "LED" to "سن‌پترزبورگ",
            "KJA" to "کراسنویارسک",
            "SVX" to "یکاترینبورگ",
            "OVB" to "نووسیبیرسک",
            "OTP" to "بخارست",
            "SOF" to "صوفیه",
            "BEG" to "بلگراد",
            "KIV" to "کیشینف",
            "DUS" to "دوسلدورف",
            "HAM" to "هامبورگ",
            "MUC" to "مونیخ",
            "BER" to "برلین",
            "TXL" to "برلین",
            "CPH" to "کپنهاگ",
            "OSL" to "اسلو",
            "LHR" to "لندن",
            "MAN" to "منچستر",
            "CDG" to "پاریس",
            "MRS" to "مارسی",
            "MXP" to "میلان",
            "FCO" to "رم",
            "ZRH" to "زوریخ",
            "GVA" to "ژنو",
            "IST" to "استانبول",
            "EVN" to "ایروان",
            "TBS" to "تفلیس",
            "GYD" to "باکو",
            "ALA" to "آلماتی",
            "TAS" to "تاشکند",
            "KBP" to "کیف",
            "MSQ" to "مینسک",
            "ATH" to "آتن",
            "LIS" to "لیسبون",
            "MAD" to "مادرید",
            "BCN" to "بارسلون",
            "BRU" to "بروکسل",
            "DUB" to "دوبلین",
        )
    }
}
