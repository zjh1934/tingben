package com.tingben.app.core

object NonVerbal {

    private val cues = listOf(
        listOf("呜咽", "哽咽", "抽泣", "啜泣", "哭腔", "哭了", "哭出", "泪", "泣不成声")
                to "带出压抑的哭腔，声音微微发颤，气声偏重，句尾下沉",
        listOf("哈哈", "笑出", "笑了", "轻笑", "冷笑", "苦笑", "笑声", "咯咯", "嗤笑")
                to "在对应处带出一点轻声的笑，用气声，不夸张，不另加字词",
        listOf("叹了口气", "叹气", "长叹", "叹息", "呼出一口", "深深吸", "倒吸", "吸了口气")
                to "在对应处先有一口听得见的呼气或吸气，语速略放缓，再接着说",
        listOf("咳嗽", "咳了", "喘着", "喘息", "气喘", "上气不接下气")
                to "在对应处带一点短促的气声或咳嗽感，别盖住正文",
        listOf("颤抖", "发抖", "声音发颤", "倒吸一口凉气", "倒抽")
                to "声音略带发颤，语速放慢，尾音轻一点"
    )

    fun detect(text: String): String? {
        for ((words, direction) in cues) {
            if (words.any { text.contains(it) }) return direction
        }
        return null
    }

    fun apply(ann: Ann): Ann {
        val d = detect(ann.text) ?: return ann
        val base = ann.instruct.trim().removeSuffix("。").removeSuffix("，")
        val extra = "$d；除原文外不要另加任何字词，不要把括号或说明念出来"
        return Ann(ann.text, ann.speaker, if (base.isBlank()) extra else "$base。$extra")
    }

    fun count(anns: List<Ann>): Int = anns.count { detect(it.text) != null }
}
