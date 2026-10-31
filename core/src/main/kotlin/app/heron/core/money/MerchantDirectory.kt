package app.heron.core.money

/**
 * Merchant knowledge for Qatar and the wider GCC (plus global brands people
 * pay from there). Matching is by whole words; a longer, more specific match
 * beats a shorter one, so "talabat mart" (groceries) wins over "talabat"
 * (food delivery) and "lulu exchange" (transfers) over "lulu" (groceries).
 *
 * Mall names are deliberately left out: "STARBUCKS VILLAGGIO" is coffee, not shopping.
 */
object MerchantDirectory {
    val KEYWORDS: Map<BudgetCategory, List<String>> = mapOf(
        BudgetCategory.GROCERIES to listOf(
            // Qatar
            "carrefour", "lulu", "lulu hypermarket", "monoprix", "al meera", "almeera", "megamart", "mega mart", "spinneys",
            "family food centre", "family food center", "safari", "safari hypermarket", "grand hypermarket", "ansar gallery",
            "quality hypermarket", "saveco", "rawabi", "al rawabi", "fresh market", "talabat mart", "snoonu market", "baqala", "bakala",
            "géant", "geant", "jones the grocer", "dukkan", "parco", "aswaq", "new indian supermarket", "mall hypermarket",
            // Wider GCC
            "union coop", "choithrams", "waitrose", "west zone", "nesto", "viva supermarket", "instashop", "careem quik", "noon minutes",
            "panda", "hyperpanda", "danube", "tamimi", "othaim", "al othaim", "bindawood", "farm superstores", "manuel market",
            "sultan center", "the sultan center", "al rumaithiya", "city centre hypermarket", "al fair", "lulu express",
            // Generic
            "supermarket", "hypermarket", "grocery", "groceries", "mini mart", "minimart", "butchery", "bakery and grocery",
            "walmart", "tesco", "aldi", "lidl", "kroger", "whole foods", "costco", "sainsbury", "asda",
        ),
        BudgetCategory.DINING to listOf(
            // Delivery
            "talabat", "snoonu", "rafeeq", "deliveroo", "hungerstation", "jahez", "mrsool", "the chefz", "noon food", "careem food",
            "uber eats", "doordash", "zomato", "keeta",
            // Cafés
            "starbucks", "costa", "costa coffee", "tim hortons", "caribou", "%arabica", "arabica", "flat white", "dunkin",
            "krispy kreme", "paul", "patchi cafe", "bateel", "karak", "chapati karak", "chapati & karak", "cafe", "café", "coffee",
            "espresso", "barista", "black tap", "% arabica", "joe and the juice", "pret", "brew92", "half million",
            // Restaurants & fast food
            "mcdonald", "mcdonalds", "kfc", "burger king", "hardee", "hardees", "pizza hut", "domino", "dominos", "papa john",
            "papa johns", "subway", "five guys", "shake shack", "nando", "nandos", "texas roadhouse", "p.f. chang", "pf chang",
            "cheesecake factory", "al baik", "albaik", "kudu", "herfy", "shawarma", "shawarmer", "turkey central", "al mourjan",
            "zaatar w zeit", "operation falafel", "wingstop", "popeyes", "applebee", "chili's", "fuddruckers", "johnny rockets",
            "krispy", "restaurant", "grill", "kitchen", "diner", "bistro", "eatery", "burger", "pizza", "sushi", "bakery",
            "pastry", "juice", "food court",
        ),
        BudgetCategory.TRANSPORT to listOf(
            // Fuel
            "woqod", "qatar fuel", "adnoc", "enoc", "eppco", "emarat", "aldrees", "sasco", "petromin", "naft", "bapco",
            "al maha", "shell", "knpc", "oula", "alfa petroleum", "fuel", "petrol", "gas station", "petrol station",
            // Rides, transit, tolls, parking
            "uber", "careem", "karwa", "mowasalat", "bolt", "yango", "indrive", "lyft", "taxi", "limousine",
            "doha metro", "qatar rail", "metro", "salik", "darb", "nol", "rta", "mawaqif", "parking", "q-park", "valet",
            "car wash", "tyre", "tire", "auto service", "toll",
        ),
        BudgetCategory.SHOPPING to listOf(
            "noon", "amazon", "amazon.ae", "amazon.sa", "namshi", "ounass", "sivvi", "6thstreet", "centrepoint", "centerpoint",
            "max fashion", "splash", "babyshop", "home centre", "home center", "home box", "homebox", "ikea", "pan emirates",
            "ace hardware", "jarir", "extra stores", "sharaf dg", "emax", "virgin megastore", "jumbo electronics",
            "techno blue", "ali bin ali", "debenhams", "h&m", "zara", "bershka", "pull&bear", "pull and bear", "massimo dutti",
            "mango", "sephora", "faces", "bath & body works", "bath and body works", "victoria's secret", "nike", "adidas",
            "sun & sand sports", "sun and sand sports", "decathlon", "shein", "temu", "aliexpress", "apple store", "samsung",
            "lc waikiki", "defacto", "marks & spencer", "marks and spencer", "aldo", "charles & keith", "pandora", "swarovski",
            "boots retail", "mothercare", "toys r us", "hamleys", "electronics", "boutique", "fashion", "store", "mall",
        ),
        BudgetCategory.BILLS to listOf(
            // Telecom
            "ooredoo", "vodafone", "etisalat", "e&", "du", "virgin mobile", "stc", "mobily", "zain", "batelco", "omantel",
            "viva", "redbull mobile", "friendi",
            // Utilities & government
            "kahramaa", "dewa", "sewa", "addc", "aadc", "taqa", "fewa", "etihad water", "nama", "saudi electricity",
            "marafeq", "qatar cool", "empower", "metrash", "moi", "hukoomi", "ministry", "absher", "tasheel", "gdrfa",
            "traffic fine", "municipality",
            // Insurance & rent
            "qic", "qatar insurance", "axa", "daman", "bupa", "tawuniya", "insurance", "rent", "electricity", "water bill",
            "internet", "telecom", "utility", "utilities",
        ),
        BudgetCategory.SUBSCRIPTIONS to listOf(
            "netflix", "spotify", "anghami", "osn", "osn+", "shahid", "starzplay", "starz play", "disney", "disney+",
            "youtube premium", "youtube", "apple.com/bill", "apple.com", "itunes", "icloud", "google one", "google play",
            "amazon prime", "prime video", "microsoft 365", "xbox game pass", "playstation plus", "chatgpt", "openai",
            "anthropic", "claude", "adobe", "canva", "dropbox", "linkedin premium", "tod tv", "bein", "yango play",
            "careem plus", "talabat pro", "noon one", "deliveroo plus", "patreon", "subscription", "membership",
        ),
        BudgetCategory.ENTERTAINMENT to listOf(
            "vox", "vox cinemas", "novo", "novo cinemas", "reel cinemas", "cinepolis", "muvi", "quest", "doha quest",
            "angry birds world", "kidzania", "img worlds", "ferrari world", "yas waterworld", "warner bros world",
            "dubai parks", "virtuocity", "magic planet", "playstation", "steam", "nintendo", "q-tickets", "qtickets",
            "platinumlist", "ticketmaster", "cinema", "bowling", "theme park", "concert", "museum", "zoo",
        ),
        BudgetCategory.HEALTH to listOf(
            "hamad medical", "hmc", "phcc", "sidra", "aster", "al ahli hospital", "doha clinic", "al emadi", "naseem al rabeeh",
            "naseem", "wellcare", "kulud", "al mana", "boots", "life pharmacy", "aster pharmacy", "nahdi", "al dawaa",
            "fitness first", "gold's gym", "golds gym", "anytime fitness", "1rebel", "barry's", "snap fitness",
            "pharmacy", "clinic", "hospital", "medical", "dental", "dentist", "optician", "optics", "gym", "fitness",
        ),
        BudgetCategory.TRAVEL to listOf(
            "qatar airways", "emirates", "etihad", "flydubai", "air arabia", "saudia", "flynas", "gulf air", "oman air",
            "kuwait airways", "riyadh air", "booking.com", "agoda", "expedia", "airbnb", "almosafer", "wego",
            "tajawal", "rehlat", "marriott", "hilton", "ihg", "accor", "rotana", "jumeirah", "four seasons", "ritz",
            "qatar duty free", "duty free", "hamad international", "airline", "airways", "hotel", "resort", "travel",
        ),
        BudgetCategory.TRANSFERS to listOf(
            "fawran", "transfer", "western union", "al zaman exchange", "al dar exchange", "city exchange", "lulu exchange",
            "al ansari", "uae exchange", "al fardan exchange", "remittance", "exchange house", "money exchange",
            "atm", "cash withdrawal", "withdrawal", "sent to", "wallet top up", "top-up",
        ),
    )

    /**
     * Scores categories for a merchant. Matches in the merchant name count fully,
     * matches elsewhere in the message count half, and multi-word matches outweigh
     * single words so the most specific brand wins.
     */
    fun score(merchant: String, message: String): Map<BudgetCategory, Float> {
        val m = merchant.lowercase()
        val all = message.lowercase()
        val out = HashMap<BudgetCategory, Float>()
        for ((cat, words) in KEYWORDS) {
            var best = 0f
            for (w in words) {
                val specificity = 2f + (w.count { it == ' ' } * 1.5f) + (if (w.length >= 8) 0.5f else 0f)
                val pts = when {
                    m.containsWord(w) -> specificity
                    all.containsWord(w) -> specificity * 0.5f
                    else -> 0f
                }
                if (pts > best) best = pts
            }
            if (best > 0f) out[cat] = best
        }
        return out
    }

    private fun String.containsWord(w: String): Boolean {
        var from = 0
        while (true) {
            val i = indexOf(w, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !this[i - 1].isLetterOrDigit()
            val end = i + w.length
            val afterOk = end >= length || !this[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }
}
