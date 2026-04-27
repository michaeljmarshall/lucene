/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search;

import java.io.IOException;
import java.util.List;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.hnsw.RandomVectorScorer;

/**
 * Computes the similarity score between a given query vector and different document vectors. This
 * is used for exact searching and scoring
 *
 * @lucene.experimental
 */
public interface VectorScorer {
  int DEFAULT_BULK_BATCH_SIZE = 64;

  /**
   * Compute the score for the current document ID.
   *
   * @return the score for the current document ID
   * @throws IOException if an exception occurs during score computation
   */
  float score() throws IOException;

  /**
   * @return a {@link DocIdSetIterator} over the documents.
   */
  DocIdSetIterator iterator();

  /**
   * An optional bulk scorer implementation that allows bulk scoring over the provided matching
   * docs. The iterator of this instance of VectorScorer should be used and iterated in conjunction
   * with the provided matchingDocs iterator to score only the documents that are present in both
   * iterators. If the provided matchingDocs iterator is null, then all documents should be scored.
   * Additionally, if the iterators are unpositioned (docID() == -1), this method should position
   * them to the first document.
   *
   * @param matchingDocs Optional filter to iterate over the documents to score
   * @return a {@link Bulk} scorer
   * @throws IOException if an exception occurs during bulk scorer creation
   * @lucene.experimental
   */
  default Bulk bulk(DocIdSetIterator matchingDocs) throws IOException {
    final DocIdSetIterator disi = BulkWithIterator.applyMatchingDocs(iterator(), matchingDocs);
    if (disi.docID() == -1) {
      disi.nextDoc();
    }
    return new BulkWithIterator(disi) {
      @Override
      public float nextDocsAndScores(int upTo, Bits liveDocs, DocAndFloatFeatureBuffer buffer)
          throws IOException {
        assert upTo > 0;
        buffer.growNoCopy(DEFAULT_BULK_BATCH_SIZE);
        int size = 0;
        float maxScore = Float.NEGATIVE_INFINITY;
        for (int doc = iterator.docID();
            doc < upTo && size < DEFAULT_BULK_BATCH_SIZE;
            doc = iterator.nextDoc()) {
          if (liveDocs == null || liveDocs.get(doc)) {
            buffer.docs[size] = doc;
            buffer.features[size] = score();
            maxScore = Math.max(maxScore, buffer.features[size]);
            ++size;
          }
        }
        buffer.size = size;
        return maxScore;
      }
    };
  }

  /**
   * Bulk scorer interface to score multiple vectors at once
   *
   * @lucene.experimental
   */
  interface Bulk {
    /**
     * Score docs ids iterating to upTo documents, store the results in the provided buffer. Behaves
     * similarly to {@link Scorer#nextDocsAndScores(int, Bits, DocAndFloatFeatureBuffer)}
     *
     * @param upTo the maximum doc ID to score
     * @param liveDocs the live docs, or null if all docs are live
     * @param buffer the buffer to store the results
     * @return the max score of the scored documents
     * @throws IOException if an exception occurs during scoring
     */
    float nextDocsAndScores(int upTo, Bits liveDocs, DocAndFloatFeatureBuffer buffer)
        throws IOException;

    /**
     * Advances the internal iterator to the target doc ID
     *
     * @param target the target doc ID to advance to
     * @return the new doc ID after advancing
     * @throws IOException if an exception occurs during advancing
     */
    int advance(int target) throws IOException;

    /**
     * Returns the current doc ID of the internal iterator
     *
     * @return the current doc ID
     */
    int docID();

    static Bulk fromRandomScorerDense(
        RandomVectorScorer scorer,
        KnnVectorValues.DocIndexIterator iterator,
        DocIdSetIterator matchingDocs) {
      final DocIdSetIterator matches = BulkWithIterator.applyMatchingDocs(iterator, matchingDocs);
      return new BulkWithIterator(matches) {
        @Override
        public float nextDocsAndScores(int upTo, Bits liveDocs, DocAndFloatFeatureBuffer buffer)
            throws IOException {
          assert upTo > 0;
          if (iterator.docID() == -1) {
            iterator.nextDoc();
          }
          buffer.growNoCopy(DEFAULT_BULK_BATCH_SIZE);
          int size = 0;
          for (int doc = iterator.docID();
              doc < upTo && size < DEFAULT_BULK_BATCH_SIZE;
              doc = iterator.nextDoc()) {
            if (liveDocs == null || liveDocs.get(doc)) {
              buffer.docs[size++] = doc;
            }
          }
          buffer.size = size;
          return scorer.bulkScore(buffer.docs, buffer.features, size);
        }
      };
    }

    static Bulk fromRandomScorerSparse(
        RandomVectorScorer scorer,
        KnnVectorValues.DocIndexIterator iterator,
        DocIdSetIterator matchingDocs) {
      final DocIdSetIterator matches = BulkWithIterator.applyMatchingDocs(iterator, matchingDocs);
      // capture the DocIndexIterator separately: inside the anonymous class, the inherited
      // `iterator` field refers to `matches`, so we need a distinct reference to call .index()
      final KnnVectorValues.DocIndexIterator docIndexIter = iterator;
      return new BulkWithIterator(matches) {
        int[] docIds = new int[0];

        @Override
        public float nextDocsAndScores(int upTo, Bits liveDocs, DocAndFloatFeatureBuffer buffer)
            throws IOException {
          assert upTo > 0;
          if (iterator.docID() == -1) {
            iterator.nextDoc();
          }
          buffer.growNoCopy(DEFAULT_BULK_BATCH_SIZE);
          docIds = ArrayUtil.growNoCopy(docIds, DEFAULT_BULK_BATCH_SIZE);
          int size = 0;
          for (int doc = iterator.docID();
              doc < upTo && size < DEFAULT_BULK_BATCH_SIZE;
              doc = iterator.nextDoc()) {
            if (liveDocs == null || liveDocs.get(doc)) {
              buffer.docs[size] = docIndexIter.index();
              docIds[size] = doc;
              ++size;
            }
          }
          buffer.size = size;
          float maxScore = scorer.bulkScore(buffer.docs, buffer.features, size);
          // copy back the real doc IDs
          System.arraycopy(docIds, 0, buffer.docs, 0, size);
          return maxScore;
        }
      };
    }
  }
}

/**
 * Package-private base class for {@link VectorScorer.Bulk} implementations whose {@link
 * VectorScorer.Bulk#advance} and {@link VectorScorer.Bulk#docID} delegate to a {@link
 * DocIdSetIterator}. Also provides the shared {@link #applyMatchingDocs} helper used by all three
 * factory sites.
 */
abstract class BulkWithIterator implements VectorScorer.Bulk {

  /** The iterator that drives document traversal for this bulk scorer. */
  protected final DocIdSetIterator iterator;

  BulkWithIterator(DocIdSetIterator iterator) {
    this.iterator = iterator;
  }

  /**
   * Returns {@code base} when {@code matchingDocs} is null, otherwise a conjunction of both so only
   * documents present in both iterators are visited.
   */
  static DocIdSetIterator applyMatchingDocs(DocIdSetIterator base, DocIdSetIterator matchingDocs) {
    return matchingDocs == null
        ? base
        : ConjunctionUtils.createConjunction(List.of(matchingDocs, base), List.of());
  }

  @Override
  public final int advance(int target) throws IOException {
    return iterator.advance(target);
  }

  @Override
  public final int docID() {
    return iterator.docID();
  }
}
