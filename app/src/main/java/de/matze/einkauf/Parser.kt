package de.matze.einkauf

/** Ein Eintrag auf der Liste. */
data class Item(val id: String, val text: String, val store: String?, val cat: String?)

/** Ergebnis des Parsers, bevor es zur Liste wird. */
data class Found(val text: String, val store: String?)

/**
 * Zerlegt WhatsApp-Einkaufszettel (Liste oder Fließtext, Deutsch/Polnisch)
 * und ordnet Produkte den Bereichen im Supermarkt zu.
 * 1:1 aus der Web-App übernommen; enthält nichts Android-Spezifisches.
 */
class Parser(known: Map<String, String>) {

    companion object {
        val CATS: List<Pair<String, String>> = listOf(
            "obst" to "Obst & Gemüse", "brot" to "Brot & Backwaren", "theke" to "Fleisch, Wurst & Fisch",
            "kuehl" to "Milch, Käse & Kühlregal", "vorrat" to "Nudeln, Reis & Konserven", "fruehstueck" to "Frühstück & Kaffee",
            "suess" to "Süßes & Snacks", "getraenke" to "Getränke", "tier" to "Tierbedarf", "drogerie" to "Drogerie & Haushalt",
            "tk" to "Tiefkühl", "kasse" to "An der Kasse", "sonst" to "Sonstiges"
        )
        val CAT_NAME: Map<String, String> = CATS.toMap()
        val CAT_IDS: List<String> = CATS.map { it.first }

        private const val LETTER = "a-zäöüßąćęłńóśźż"

        private val STORES = listOf("edeka", "rewe", "lidl", "aldi", "kaufland", "netto", "penny", "norma", "globus", "dm",
            "rossmann", "müller", "mueller", "metro", "biedronka", "polski sklep", "bäcker", "baecker", "fleischer", "apotheke", "drogerie")
        private val STORE_RE = Regex(
            "(?:^|\\n|[.!]\\s*)\\s*(?:bei |von |im |in |beim |w |z )?(" + STORES.joinToString("|") + ")\\s*[:\\-–]\\s*",
            RegexOption.IGNORE_CASE
        )
        private val TIMESTAMP_RE = Regex(
            "^\\[?\\d{1,2}[./]\\d{1,2}[./]\\d{2,4},?\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\]?\\s*(-\\s*)?[^:\\n]{1,40}:\\s*",
            RegexOption.MULTILINE
        )
        private val EMOJI_RE = Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]")
        private val STORE_MARK_RE = Regex("^\\u0001(.+)\\u0001$")
        private val SPLIT_RE = Regex(";|,|!+|\\s+\\+\\s+|\\+|\\s+(?:und|sowie|oraz|i)\\s+", RegexOption.IGNORE_CASE)
        private val BULLET_RE = Regex("^\\s*(?:[-*•·–—>]|\\d+[.)]\\s|\\[\\s?[x ]?\\])\\s*", RegexOption.IGNORE_CASE)
        private val TRAIL_RE = Regex("[.?:]+$")
        private val MULTISPACE_RE = Regex("\\s{2,}")
        private val WS_RE = Regex("\\s+")

        /** Paare aus Regex und "global" (alle Vorkommen ersetzen). */
        private val FILLER: List<Pair<Regex, Boolean>> = listOf(
            Regex("^(hallo|hi|hey|hej|cześć|czesc|kochanie|schatz|liebling|skarbie)\\b[^,]*[,!]?", RegexOption.IGNORE_CASE) to false,
            Regex("\\b(kannst|könntest|koenntest|kanst) du (bitte |noch |mal )*", RegexOption.IGNORE_CASE) to true,
            Regex("\\b(bitte|noch|mal|danke|dziękuję|dzieki|dzięki|proszę|prosze|jeszcze|też|tez)\\b", RegexOption.IGNORE_CASE) to true,
            Regex("\\b(mitbringen|einkaufen|kaufen|besorgen|holen|hol|kup|kupić|kupic|weź|wez)\\b", RegexOption.IGNORE_CASE) to true,
            Regex("\\b(wir brauchen|brauchen wir|ich brauche|potrzebujemy|potrzebuję|potrzebuje)\\b", RegexOption.IGNORE_CASE) to true,
            Regex("^(und|i|oraz|sowie|auch)\\s+", RegexOption.IGNORE_CASE) to false
        )

        /** Wörter, die zum NÄCHSTEN Wort gehören (Eigenschaften, Mengen). */
        private val PRE: Set<String> = ("grüne grün grünen rote rot roten gelbe gelb weiße weiss weisse schwarze frische frisch frischen " +
            "kleine klein große grosse saure süße suesse helle dunkle bio light zero vollkorn laktosefreie laktosefrei vegane vegan " +
            "geriebener geriebenen gemahlener gemahlen gehackt gehackte gekochte geräucherter mageres mageren fettarme fettarm halbfette " +
            "kernige zarte ganze ganzen 2x 3x 4x 5x 6x 10x g kg l ml stück stk pack packung packungen flasche flaschen dose dosen becher " +
            "beutel tüte tüten glas gläser bund kiste kasten paar netz zielone zielony czerwone czerwony świeże swieze świeży duże duzy małe male")
            .split(" ").toSet()

        /** Wörter, die zum VORHERIGEN Wort gehören. */
        private val POST: Set<String> = "tk tiefkühl tiefkuehl bio light zero".split(" ").toSet()

        /** Wenn diese Wörter vorkommen, ist es eine Beschreibung – nicht zerlegen. */
        private val GLUE = Regex("\\b(vom|von|mit|ohne|für|fuer|aus|oder|zum|zur|der|die|das|den|dem|nicht|z|ze|bez|dla|albo|lub)\\b", RegexOption.IGNORE_CASE)
        private val NUM_RE = Regex("^\\d+([.,]\\d+)?(x|g|kg|l|ml)?$", RegexOption.IGNORE_CASE)
        private val UPPER_RE = Regex("[A-ZÄÖÜ]")
        private val TK_WORD_RE = Regex("\\btk\\b", RegexOption.IGNORE_CASE)
        private val TK_CAT_RE = Regex("\\(tk\\)|tiefk[üu]h|(^|\\s)tk(\\s|$)")
        private val QTY_PREFIX_RE = Regex("^[\\d.,]+\\s*(x|g|kg|l|ml|stück|stk)?\\s+")

        /** Erste passende Regel gewinnt. "*" am Anfang = nur als ganzes Wort. */
        private val RULES: List<Pair<String, List<Any>>> = listOf(
            "obst" to "wassermelone",
            "vorrat" to "frikas konserve dose tomatenmark passata",
            "suess" to "schokolade chips",
            "tk" to "(tk) tiefkühl tiefkuehl *tk pizza *eis eiscreme pommes fischstäbchen mrożon lody",
            "tier" to "katze futter leckerli streu hund kot karma żwirek",
            "drogerie" to "zewa klopapier toilettenpapier küchenrolle küchenpapier taschentücher spülmittel waschmittel weichspüler spültabs tabs reiniger schwamm müllbeutel mülltüten alufolie frischhaltefolie backpapier shampoo duschgel seife zahnpasta zahnbürste deo windel feuchttücher rasier handcreme pfanne batterie papier płyn proszek szampon",
            "theke" to "fleisch hack hähnchen huhn hühner pute schwein rind wurst würstchen salami schinken speck bacon steak schnitzel gulasch filet lachs fisch thunfisch garnele leberkäse aufschnitt mięso kiełbas szynk kurczak parówk",
            "getraenke" to "wasser saft cola fanta sprite limo schorle bier wein sekt sprudel eistee *tee energy redbull woda sok piwo wino",
            "kuehl" to "milch sahne joghurt jogurt quark butter käse mozzarella feta schmand creme fraiche frischkäse margarine pudding *eier *ei skyr hefe mleko masło śmietan *ser twaróg jajk",
            "obst" to "apfel äpfel banane birne orange mandarine clementine zitrone limette traube erdbeer himbeer heidelbeer beere kiwi mango ananas melone pfirsich nektarine avocado tomate gurke paprika zwiebel knoblauch kartoffel möhre karotte salat brokkoli blumenkohl zucchini aubergine pilz champignon lauch porree sellerie spinat kohl radieschen ingwer petersilie schnittlauch basilikum kräuter dill obst gemüse rucola jabłk banan pomidor ogór cebul czosnek ziemniak marchew sałat papryk owoc warzyw cytryn truskaw",
            "brot" to "brot brötchen semmel toast baguette croissant brezel kuchen chleb bułk",
            "fruehstueck" to "kaffee müsli muesli cornflakes haferflocken marmelade konfitüre nutella honig aufstrich kakao kawa płatki dżem miód",
            "suess" to "schoko keks gummibär bonbon süßigkeit nuss erdnuss popcorn salzstangen cracker riegel waffel czekolad ciastk",
            "vorrat" to "nudel spaghetti pasta reis mehl zucker *salz pfeffer gewürz *öl essig ketchup senf mayo soße sauce brühe linsen bohnen mais passata tomatenmark makaron ryż mąka cukier"
        ).map { (cat, words) ->
            cat to words.split(" ").map { w ->
                if (w.startsWith("*")) Regex("(^|[^$LETTER])" + Regex.escape(w.substring(1)) + "($|[^$LETTER])") else w
            }
        }

        fun cap(s: String): String =
            if (s.isEmpty()) "" else s.substring(0, 1).uppercase() + s.substring(1).lowercase()

        fun upperFirst(s: String): String =
            if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
    }

    private val known: Map<String, String> = known
    private val knownKeys: List<Pair<String, Regex?>> = known.keys
        .sortedByDescending { it.length }
        .map { k -> k to (if (k.length < 5) Regex("(^|[^$LETTER])" + Regex.escape(k) + "($|[^$LETTER])") else null) }

    private fun splitRun(chunk: String): List<String> {
        val words = chunk.split(WS_RE).filter { it.isNotEmpty() }
        if (words.size < 3 || GLUE.containsMatchIn(chunk)) return listOf(chunk)
        val out = mutableListOf<String>()
        var cur = mutableListOf<String>()
        for (w in words) {
            val lw = w.lowercase()
            if (lw in POST && cur.isEmpty() && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + " " + w
                continue
            }
            cur.add(w)
            if (lw !in PRE && !NUM_RE.matches(lw)) {
                out.add(cur.joinToString(" "))
                cur = mutableListOf()
            }
        }
        if (cur.isNotEmpty()) out.add(cur.joinToString(" "))
        return out
    }

    private fun tidy(input: String): String {
        var s = input.split(WS_RE).joinToString(" ") { w ->
            if (w.length > 3 && w == w.uppercase() && UPPER_RE.containsMatchIn(w)) w.substring(0, 1) + w.substring(1).lowercase() else w
        }
        s = TK_WORD_RE.replace(s, "(TK)")
        return upperFirst(s)
    }

    fun localParse(text: String): List<Found> {
        var t = TIMESTAMP_RE.replace(text, "")
        t = EMOJI_RE.replace(t, " ")
        // Läden als Abschnitte markieren
        t = STORE_RE.replace(t) { m -> "\n\u0001" + m.groupValues[1] + "\u0001\n" }

        data class Raw(var text: String, val store: String?)
        val result = mutableListOf<Raw>()
        var store: String? = null

        for (line in t.split("\n")) {
            val sm = STORE_MARK_RE.find(line)
            if (sm != null) { store = cap(sm.groupValues[1]); continue }
            for (part in line.split(SPLIT_RE)) {
                var p = BULLET_RE.replaceFirst(part, "")
                for ((re, global) in FILLER) {
                    p = if (global) re.replace(p, " ") else re.replaceFirst(p, " ")
                }
                p = TRAIL_RE.replace(p, "")
                p = MULTISPACE_RE.replace(p, " ").trim()
                if (p.isEmpty()) continue
                // einzelnes "tk" o. ä. hängt am vorherigen Produkt
                if (p.lowercase() in POST && result.isNotEmpty()) {
                    result[result.size - 1].text += " $p"
                    continue
                }
                for (x in splitRun(p)) {
                    if (x.length > 1 && x.length < 60) result.add(Raw(x, store))
                }
            }
        }
        return result.map { Found(tidy(it.text), it.store) }
    }

    fun guessCat(text: String): String {
        val t = " " + text.lowercase() + " "
        if (TK_CAT_RE.containsMatchIn(t)) return "tk"
        val bare = QTY_PREFIX_RE.replaceFirst(text.lowercase(), "").trim()
        known[bare]?.let { return it }
        for ((k, re) in knownKeys) {
            val hit = if (re != null) re.containsMatchIn(t) else t.contains(k)
            if (hit) return known.getValue(k)
        }
        for ((cat, keys) in RULES) {
            if (keys.any { k -> if (k is String) t.contains(k) else (k as Regex).containsMatchIn(t) }) return cat
        }
        return "sonst"
    }

    fun catOf(it: Item): String =
        if (it.cat != null && CAT_NAME.containsKey(it.cat)) it.cat else guessCat(it.text)
}
