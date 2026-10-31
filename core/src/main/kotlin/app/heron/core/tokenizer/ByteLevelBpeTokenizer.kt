package app.heron.core.tokenizer

import app.heron.core.text.MiniJson
import app.heron.core.text.asList
import app.heron.core.text.asMap

/** Token ids + attention mask, ready to feed an encoder. */
data class Encoding(val ids: LongArray, val attentionMask: LongArray, val typeIds: LongArray) {
    val length: Int get() = ids.size
}

/**
 * A byte-level BPE tokenizer that reads a Hugging Face `tokenizer.json`
 * (the format ModernBERT, RoBERTa and GPT-2 style models ship). It implements
 * the GPT-2 pre-tokenizer regex, byte-to-unicode mapping, ranked BPE merges and
 * the pair template from the file's post-processor ([CLS] A [SEP] B [SEP] or
 * RoBERTa's <s> A </s></s> B </s>).
 */
class ByteLevelBpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRanks: Map<Pair<String, String>, Int>,
    private val singleTemplate: List<Piece>,
    private val pairTemplate: List<Piece>,
    private val unkId: Int?,
    private val addPrefixSpace: Boolean,
    /** Added tokens (e.g. ModernBERT's whitespace runs) matched before BPE, like HF does. */
    private val addedTokens: Map<String, Int> = emptyMap(),
) {
    private val addedPattern: Regex? = addedTokens.keys.filter { it.isNotEmpty() }
        .sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.let { toks -> Regex(toks.joinToString("|") { Regex.escape(it) }) }

    sealed interface Piece {
        data class Special(val id: Int) : Piece
        data class Seq(val second: Boolean) : Piece
    }

    private val cache = object : LinkedHashMap<String, IntArray>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 20_000
    }

    /** Encodes a single sequence with special tokens, truncated to [maxLength]. */
    fun encode(text: String, maxLength: Int = 512): Encoding = build(tokenize(text), null, singleTemplate, maxLength)

    /** Encodes a pair (premise, hypothesis); truncates the longer side first. */
    fun encodePair(a: String, b: String, maxLength: Int = 512): Encoding =
        build(tokenize(a), tokenize(b), pairTemplate, maxLength)

    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>()
        val pattern = addedPattern
        if (pattern == null) {
            tokenizeSegment(text, out, first = true)
            return out
        }
        // Leftmost-longest split on added tokens, then BPE the text between them.
        var pos = 0
        for (m in pattern.findAll(text)) {
            if (m.range.first > pos) tokenizeSegment(text.substring(pos, m.range.first), out, first = pos == 0)
            out.add(addedTokens.getValue(m.value))
            pos = m.range.last + 1
        }
        if (pos < text.length) tokenizeSegment(text.substring(pos), out, first = pos == 0)
        return out
    }

    private fun tokenizeSegment(text: String, out: MutableList<Int>, first: Boolean) {
        val input = if (first && addPrefixSpace && text.isNotEmpty() && !text[0].isWhitespace()) " $text" else text
        for (m in PRETOKENIZE.findAll(input)) {
            val word = m.value.toByteArray(Charsets.UTF_8).joinToString("") { BYTE_ENCODER[it.toInt() and 0xFF].toString() }
            val ids = synchronized(cache) { cache[word] } ?: bpe(word).also { synchronized(cache) { cache[word] = it } }
            ids.forEach { out.add(it) }
        }
    }

    private fun bpe(word: String): IntArray {
        var parts = word.map { it.toString() }.toMutableList()
        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until parts.size - 1) {
                val r = mergeRanks[parts[i] to parts[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; bestIdx = i }
            }
            if (bestIdx < 0) break
            val a = parts[bestIdx]
            val b = parts[bestIdx + 1]
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) {
                    merged.add(a + b); i += 2
                } else {
                    merged.add(parts[i]); i++
                }
            }
            parts = merged
        }
        val ids = ArrayList<Int>(parts.size)
        for (p in parts) {
            val id = vocab[p]
            when {
                id != null -> ids.add(id)
                unkId != null -> ids.add(unkId)
                // Byte-level vocabularies contain every single byte symbol, so splitting always succeeds.
                else -> p.forEach { ch -> vocab[ch.toString()]?.let(ids::add) }
            }
        }
        return ids.toIntArray()
    }

    private fun build(a: List<Int>, b: List<Int>?, template: List<Piece>, maxLength: Int): Encoding {
        val specials = template.count { it is Piece.Special }
        var la = a.size
        var lb = b?.size ?: 0
        while (la + lb + specials > maxLength && (la > 0 || lb > 0)) {
            if (la >= lb) la-- else lb--
        }
        val ids = ArrayList<Long>(la + lb + specials)
        val types = ArrayList<Long>(la + lb + specials)
        var seenSecond = false
        for (piece in template) {
            when (piece) {
                is Piece.Special -> { ids.add(piece.id.toLong()); types.add(if (seenSecond) 1 else 0) }
                is Piece.Seq -> if (piece.second) {
                    seenSecond = true
                    b?.take(lb)?.forEach { ids.add(it.toLong()); types.add(1) }
                } else {
                    a.take(la).forEach { ids.add(it.toLong()); types.add(0) }
                }
            }
        }
        return Encoding(ids.toLongArray(), LongArray(ids.size) { 1L }, types.toLongArray())
    }

    companion object {
        /** GPT-2 / ModernBERT pre-tokenization pattern. */
        private val PRETOKENIZE =
            Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        /** GPT-2 reversible byte -> printable unicode mapping. */
        val BYTE_ENCODER: CharArray = run {
            val bs = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toMutableList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            }
            val table = CharArray(256)
            bs.forEachIndexed { i, b -> table[b] = cs[i].toChar() }
            table
        }

        fun fromJson(json: String): ByteLevelBpeTokenizer {
            val root = MiniJson.parse(json).asMap() ?: error("tokenizer.json is not an object")
            val model = root["model"].asMap() ?: error("tokenizer.json has no model")
            require((model["type"] as? String ?: "BPE") == "BPE") { "Only BPE tokenizers are supported" }
            val vocab = HashMap<String, Int>()
            model["vocab"].asMap()?.forEach { (k, v) -> vocab[k] = (v as Number).toInt() }
            val added = HashMap<String, Int>()
            root["added_tokens"].asList()?.forEach { t ->
                val m = t.asMap() ?: return@forEach
                added[m["content"] as String] = (m["id"] as Number).toInt()
            }
            vocab.putAll(added)

            val ranks = HashMap<Pair<String, String>, Int>()
            model["merges"].asList()?.forEachIndexed { rank, m ->
                val pair = when (m) {
                    is String -> m.split(' ', limit = 2).let { it[0] to it[1] }
                    is List<*> -> (m[0] as String) to (m[1] as String)
                    else -> error("Unsupported merge entry: $m")
                }
                ranks[pair] = rank
            }

            val unk = (model["unk_token"] as? String)?.let { vocab[it] }
            val addPrefix = root["pre_tokenizer"].findByteLevel()?.get("add_prefix_space") as? Boolean ?: false
            val (single, pair) = templates(root["post_processor"].asMap(), vocab)
            return ByteLevelBpeTokenizer(vocab, ranks, single, pair, unk, addPrefix, added)
        }

        private fun Any?.findByteLevel(): Map<String, Any?>? {
            val m = asMap() ?: return null
            if (m["type"] == "ByteLevel") return m
            return m["pretokenizers"].asList()?.firstNotNullOfOrNull { it.findByteLevel() }
        }

        private fun templates(pp: Map<String, Any?>?, vocab: Map<String, Int>): Pair<List<Piece>, List<Piece>> {
            fun special(name: String): Piece = Piece.Special(vocab[name] ?: error("Special token $name missing from vocab"))
            val a = Piece.Seq(false)
            val b = Piece.Seq(true)
            when (pp?.get("type")) {
                "TemplateProcessing" -> {
                    fun parse(list: Any?): List<Piece> = list.asList().orEmpty().map { item ->
                        val m = item.asMap()!!
                        m["SpecialToken"].asMap()?.let { special(it["id"] as String) }
                            ?: Piece.Seq((m["Sequence"].asMap()?.get("id") as? String) == "B")
                    }
                    return parse(pp["single"]) to parse(pp["pair"])
                }
                "RobertaProcessing" -> {
                    val cls = special((pp["cls"].asList()?.get(0) as? String) ?: "<s>")
                    val sep = special((pp["sep"].asList()?.get(0) as? String) ?: "</s>")
                    return listOf(cls, a, sep) to listOf(cls, a, sep, sep, b, sep)
                }
                "Sequence" -> {
                    pp["processors"].asList()?.mapNotNull { it.asMap() }
                        ?.firstOrNull { it["type"] == "TemplateProcessing" || it["type"] == "RobertaProcessing" || it["type"] == "BertProcessing" }
                        ?.let { return templates(it, vocab) }
                }
            }
            // BertProcessing or unknown: fall back to the BERT/ModernBERT layout.
            val cls = special(if ("[CLS]" in vocab) "[CLS]" else "<s>")
            val sep = special(if ("[SEP]" in vocab) "[SEP]" else "</s>")
            return listOf(cls, a, sep) to listOf(cls, a, sep, b, sep)
        }
    }
}
