package onl.ycode.stormify.schemasync.classifier

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.ByteBuffersDirectory
import java.io.Closeable

/**
 * In-memory slot classifier using Lucene NGramTokenizer + BM25.
 *
 * Each slot is a single Lucene Document whose `vocabulary` field accumulates
 * all training examples (seeds + user assignments). Querying with a column name
 * scores all slot docs and returns the top-N matches.
 *
 * Thread-safety: not thread-safe. Single-thread use only.
 */
class SlotClassifier : Closeable {

    data class Suggestion(val slotKey: String, val score: Float)

    private val directory = ByteBuffersDirectory()
    private val analyzer = NGramAnalyzer()
    private val writer: IndexWriter
    private var reader: DirectoryReader
    private var searcher: IndexSearcher

    private val vocabularies = mutableMapOf<String, MutableList<String>>()

    init {
        val config = IndexWriterConfig(analyzer).apply {
            similarity = BM25Similarity()
        }
        writer = IndexWriter(directory, config)
        writer.commit()
        reader = DirectoryReader.open(directory)
        searcher = IndexSearcher(reader).apply {
            similarity = BM25Similarity()
        }
    }

    /** Add seed examples for a slot. Idempotent across keys; new examples append. */
    fun seed(slotKey: String, examples: Collection<String>) {
        if (examples.isEmpty()) return
        vocabularies.getOrPut(slotKey) { mutableListOf() }.addAll(examples)
        rebuildSlotDoc(slotKey)
    }

    /** Record a user assignment: this column name belongs to this slot. */
    fun train(slotKey: String, columnName: String) {
        vocabularies.getOrPut(slotKey) { mutableListOf() }.add(columnName)
        rebuildSlotDoc(slotKey)
    }

    /** Top-N slot suggestions for a column name, ranked by BM25 score. */
    fun classify(columnName: String, topN: Int = 3): List<Suggestion> {
        if (vocabularies.isEmpty()) return emptyList()
        val tokens = tokenize(columnName)
        if (tokens.isEmpty()) return emptyList()
        val builder = BooleanQuery.Builder()
        tokens.distinct().forEach { token ->
            builder.add(TermQuery(Term(FIELD_VOCABULARY, token)), BooleanClause.Occur.SHOULD)
        }
        val hits = searcher.search(builder.build(), topN).scoreDocs
        val storedFields = searcher.storedFields()
        return hits.map { hit ->
            val doc = storedFields.document(hit.doc)
            Suggestion(doc.get(FIELD_SLOT), hit.score)
        }
    }

    private fun rebuildSlotDoc(slotKey: String) {
        val vocab = vocabularies[slotKey] ?: return
        val doc = Document().apply {
            add(StringField(FIELD_SLOT, slotKey, Field.Store.YES))
            add(TextField(FIELD_VOCABULARY, vocab.joinToString(" "), Field.Store.NO))
        }
        writer.updateDocument(Term(FIELD_SLOT, slotKey), doc)
        writer.commit()
        refreshReader()
    }

    private fun refreshReader() {
        val newReader = DirectoryReader.openIfChanged(reader, writer) ?: return
        reader.close()
        reader = newReader
        searcher = IndexSearcher(reader).apply {
            similarity = BM25Similarity()
        }
    }

    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        analyzer.tokenStream(FIELD_VOCABULARY, input).use { stream ->
            val termAttr = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            while (stream.incrementToken()) tokens.add(termAttr.toString())
            stream.end()
        }
        return tokens
    }

    override fun close() {
        reader.close()
        writer.close()
        directory.close()
    }

    private companion object {
        const val FIELD_SLOT = "slot"
        const val FIELD_VOCABULARY = "vocabulary"
    }
}
