package com.calima.search.query;

/** Okapi BM25 relevance: rare terms weigh more, and repeated terms saturate on long books. */
final class Bm25 {

    private Bm25() {
    }

    static double idf(double bookCount, long booksWithTerm) {
        return Math.log(1 + (bookCount - booksWithTerm + 0.5) / (booksWithTerm + 0.5));
    }

    static double score(double idf, int termFrequency, int bookLength, double averageBookLength) {
        double lengthNorm = 1 - SearchEngine.BM25_B + SearchEngine.BM25_B * bookLength / averageBookLength;
        return idf * termFrequency * (SearchEngine.BM25_K1 + 1) / (termFrequency + SearchEngine.BM25_K1 * lengthNorm);
    }
}
