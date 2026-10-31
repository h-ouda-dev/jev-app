package app.heron.core

import app.heron.core.tokenizer.ByteLevelBpeTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerTest {
    // A tiny ModernBERT-shaped tokenizer.json: byte-level BPE + [CLS]/[SEP] template.
    private val json = """
    {
      "added_tokens": [
        {"id": 0, "content": "[CLS]", "special": true},
        {"id": 1, "content": "[SEP]", "special": true},
        {"id": 2, "content": "[UNK]", "special": true},
        {"id": 16, "content": "   ", "special": false}
      ],
      "pre_tokenizer": {"type": "ByteLevel", "add_prefix_space": false, "trim_offsets": true, "use_regex": true},
      "post_processor": {
        "type": "TemplateProcessing",
        "single": [{"SpecialToken": {"id": "[CLS]", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "[SEP]", "type_id": 0}}],
        "pair": [{"SpecialToken": {"id": "[CLS]", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "[SEP]", "type_id": 0}}, {"Sequence": {"id": "B", "type_id": 1}}, {"SpecialToken": {"id": "[SEP]", "type_id": 1}}]
      },
      "model": {
        "type": "BPE",
        "unk_token": "[UNK]",
        "vocab": {"[CLS]": 0, "[SEP]": 1, "[UNK]": 2, "h": 3, "i": 4, "Ġ": 5, "t": 6, "e": 7, "r": 8, "hi": 9, "Ġt": 10, "Ġth": 11, "Ġthe": 12, "Ġther": 13, "Ġthere": 14, "!": 15},
        "merges": ["h i", "Ġ t", "Ġt h", "Ġth e", ["Ġthe", "r"], "Ġther e"]
      }
    }
    """.trimIndent()

    private val tok = ByteLevelBpeTokenizer.fromJson(json)

    @Test fun byteEncoderMapsSpaceToG() {
        assertEquals('Ġ', ByteLevelBpeTokenizer.BYTE_ENCODER[' '.code])
        assertEquals('a', ByteLevelBpeTokenizer.BYTE_ENCODER['a'.code])
    }

    @Test fun appliesMergesInRankOrder() {
        assertEquals(listOf(9, 14, 15), tok.tokenize("hi there!"))
    }

    @Test fun unknownCharactersBecomeUnk() {
        assertEquals(listOf(9, 2), tok.tokenize("hiz"))
    }

    @Test fun addedTokensAreMatchedBeforeBpe() {
        // "   " is an added token (ModernBERT has these for whitespace runs).
        assertEquals(listOf(9, 16, 9), tok.tokenize("hi   hi"))
    }

    @Test fun pairTemplateAndTruncation() {
        val enc = tok.encodePair("hi", "hi there!")
        assertArrayEquals(longArrayOf(0, 9, 1, 9, 14, 15, 1), enc.ids)
        assertArrayEquals(longArrayOf(1, 1, 1, 1, 1, 1, 1), enc.attentionMask)
        val short = tok.encodePair("hi", "hi there!", maxLength = 5)
        assertEquals(5, short.length)
        assertEquals(0L, short.ids.first())
        assertEquals(1L, short.ids.last())
    }
}
