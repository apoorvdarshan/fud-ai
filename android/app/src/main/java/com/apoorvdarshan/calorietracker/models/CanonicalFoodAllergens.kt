package com.apoorvdarshan.calorietracker.models

import java.util.Locale

internal object CanonicalFoodAllergens {
    // Exact whole-name aliases only. Keep in sync with CanonicalFoodAllergens.swift.
    private val rows = """
    milk|dairy|lactose|casein|whey|حليب|süd|mléko|mlékem|Milch|leche|lait|दूध|latte|乳|牛乳|우유|melk|mleko|leite|lapte|молоко|süt|молоко|牛奶
    egg|eggs|بيض|yumurta|vejce|vejci|Ei|huevo|œuf|अंडा|uovo|卵|달걀|ei|jajko|ovo|ou|яйцо|yumurta|яйце|鸡蛋
    peanut|peanuts|groundnut|فول سوداني|yerfındığı|arašídy|arašíd|Erdnuss|cacahuete|arachide|मूंगफली|arachide|落花生|땅콩|pinda|orzeszki ziemne|amendoim|arahide|арахис|yer fıstığı|арахіс|花生
    tree nut|tree nuts|nuts|almond|cashew|hazelnut|macadamia|pecan|pistachio|walnut|مكسرات شجرية|ağac qoz-fındığı|skořápkové plody|Schalenfrüchte|frutos de cáscara|fruits à coque|पेड़ के मेवे|frutta a guscio|木の実|견과류|noten|orzechy drzewne|castanhas|fructe cu coajă lemnoasă|древесные орехи|sert kabuklu yemişler|деревні горіхи|树坚果
    wheat|قمح|buğda|pšenice|Weizen|trigo|blé|गेहूँ|grano|小麦|밀|tarwe|pszenica|trigo|grâu|пшеница|buğday|пшениця|小麦
    gluten|غلوتين|qlüten|lepek|Gluten|gluten|gluten|ग्लूटेन|glutine|グルテン|글루텐|gluten|gluten|glúten|gluten|глютен|glüten|глютен|麸质
    soy|soya|soybean|soybeans|صويا|soya|sója|Soja|soja|soja|सोया|soia|大豆|대두|soja|soja|soja|soia|соя|soya|соя|大豆
    fish|سمك|balıq|ryby|Fisch|pescado|poisson|मछली|pesce|魚|생선|vis|ryby|peixe|pește|рыба|balık|риба|鱼
    shellfish|crustacean|crustaceans|shrimp|prawn|crab|lobster|قشريات|xərçəngkimilər|korýši|Krebstiere|crustáceos|crustacés|क्रस्टेशियन|crostacei|甲殻類|갑각류|schaaldieren|skorupiaki|crustáceos|crustacee|ракообразные|kabuklular|ракоподібні|甲壳类
    sesame|سمسم|küncüt|sezam|Sesam|sésamo|sésame|तिल|sesamo|ごま|참깨|sesam|sezam|gergelim|susan|кунжут|susam|кунжут|芝麻
    celery|كرفس|kərəviz|celer|Sellerie|apio|céleri|अजमोदा|sedano|セロリ|셀러리|selderij|seler|aipo|țelină|сельдерей|kereviz|селера|芹菜
    mustard|خردل|xardal|hořčice|Senf|mostaza|moutarde|सरसों|senape|マスタード|겨자|mosterd|gorczyca|mostarda|muștar|горчица|hardal|гірчиця|芥末
    lupin|lupine|ترمس|lüpin|vlčí bob|Lupine|altramuz|lupin|ल्यूपिन|lupino|ルピナス|루핀|lupine|łubin|tremoço|lupin|люпин|acı bakla|люпин|羽扇豆
    sulphite|sulfite|sulfites|sulphites|sulfur dioxide|كبريتيت|sulfitlər|siřičitany|Sulfite|sulfitos|sulfites|सल्फाइट|solfiti|亜硫酸塩|아황산염|sulfieten|siarczyny|sulfitos|sulfiți|сульфиты|sülfitler|сульфіти|亚硫酸盐
    mollusc|mollusk|molluscs|mollusks|رخويات|molyusklar|měkkýši|Weichtiere|moluscos|mollusques|मोलस्क|molluschi|軟体動物|연체동물|weekdieren|mięczaki|moluscos|moluște|моллюски|yumuşakçalar|молюски|软体动物
    """.trimIndent().lines()
    val ids = rows.map { it.substringBefore('|') }
    private val allowed = ids.toSet()
    private val aliases = buildMap {
        rows.forEach { row ->
            val id = row.substringBefore('|')
            row.split('|').forEach { put(AllergenAnalysis.normalize(it), id) }
        }
    }

    fun idFor(name: String): String? = aliases[AllergenAnalysis.normalize(name)]

    /** Only accept exact protocol IDs from AI; translated or invented IDs are not evidence. */
    fun clean(values: List<String>): List<String> =
        values.map { it.trim().lowercase(Locale.ROOT) }.filter { it in allowed }.distinct()

    val prompt: String =
        """Include an "allergens" JSON array for the whole food and each listed ingredient. Use only these exact IDs: ${ids.joinToString(", ")}. List only possible allergens supported by the visible label, described ingredients, or recognizable food. Use [] if unknown; this is not evidence of absence. Do not translate IDs."""
}
