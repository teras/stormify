package onl.ycode.stormify.schemasync.classifier

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.ngram.NGramTokenFilter

/**
 * Language-agnostic analyzer that turns each input into character n-grams (3–5).
 * Pipeline: KeywordTokenizer → LowerCaseFilter → ASCIIFoldingFilter → NGramTokenFilter(3, 5).
 *
 * Why language-agnostic: no stemmer, no stop-words, no language-specific filters.
 * Same behavior on `customer`, `kunde`, `pelatis`.
 */
class NGramAnalyzer(
    private val minGram: Int = 3,
    private val maxGram: Int = 5,
) : Analyzer() {
    override fun createComponents(fieldName: String): TokenStreamComponents {
        val tokenizer = KeywordTokenizer()
        var stream: TokenStream = LowerCaseFilter(tokenizer)
        stream = ASCIIFoldingFilter(stream)
        stream = NGramTokenFilter(stream, minGram, maxGram, false)
        return TokenStreamComponents(tokenizer, stream)
    }
}
