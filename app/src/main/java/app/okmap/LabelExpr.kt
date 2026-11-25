package app.okmap

import org.maplibre.android.style.expressions.Expression

/** Languages that can be shown on the map. Property names follow the OpenMapTiles schema. */
enum class Lang(val prefKey: String, val title: String, val expr: String) {
    // Georgian: name:ka, otherwise the local `name` (in Georgia it is Georgian)
    KA(
        "lang_ka", "ქართული — Georgian",
        "['to-string',['coalesce',['get','name:ka'],['get','name'],'']]"
    ),
    RU(
        "lang_ru", "Русский — Russian",
        "['to-string',['coalesce',['get','name:ru'],'']]"
    ),
    EN(
        "lang_en", "English",
        "['to-string',['coalesce',['get','name:en'],['get','name_en'],['get','name:latin'],'']]"
    )
}

/**
 * Builds a MapLibre "text-field" expression that prints the enabled languages one per line,
 * skipping empty values and duplicates (e.g. when name:en == name:ka).
 * Written with single quotes for readability; converted to JSON at the end.
 */
object LabelExpr {

    private fun names(langs: List<Lang>): String {
        val n = langs.size
        if (n == 1) return langs[0].expr

        fun p(i: Int) = "['var','p$i']"
        fun v(i: Int) = "['var','v$i']"

        // v_i = p_i unless empty or equal to an earlier language
        val vDefs = (1..n).map { i ->
            if (i == 1) {
                p(1)
            } else {
                val conds = mutableListOf("['==',${p(i)},'']")
                for (j in 1 until i) conds += "['==',${p(i)},${p(j)}]"
                "['case',['any',${conds.joinToString(",")}],'',${p(i)}]"
            }
        }

        val parts = mutableListOf(v(1))
        for (i in 2..n) {
            val prev = (1 until i).joinToString(",") { "['!=',${v(it)},'']" }
            parts += "['case',['all',['any',$prev],['!=',${v(i)},'']],'\\n','']"
            parts += v(i)
        }

        var s = "['concat',${parts.joinToString(",")}]"
        for (i in n downTo 1) s = "['let','v$i',${vDefs[i - 1]},$s]"
        for (i in n downTo 1) s = "['let','p$i',${langs[i - 1].expr},$s]"
        return s
    }

    fun build(langs: List<Lang>, layerId: String): Expression {
        val n = names(langs)
        val s = when {
            // peaks: names + elevation
            layerId.startsWith("lbl-peak") ->
                "['let','n',$n,['case',['has','ele'],['case',['==',['var','n'],''],['concat',['to-string',['get','ele']],' м']," +
                    "['concat',['var','n'],'\\n',['to-string',['get','ele']],' м']],['var','n']]]"
            // roads: if no name in the selected languages, show the road reference (M1, S12...)
            layerId.startsWith("lbl-road") ->
                "['let','n',$n,['case',['==',['var','n'],''],['to-string',['coalesce',['get','ref'],'']],['var','n']]]"
            else -> n
        }
        return Expression.raw(s.replace('\'', '"'))
    }
}
