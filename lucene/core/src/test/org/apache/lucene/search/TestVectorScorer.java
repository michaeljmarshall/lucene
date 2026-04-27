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

import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;

import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnByteVectorField;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.util.FixedBitSet;

public class TestVectorScorer extends LuceneTestCase {

  public void testFindAll() throws IOException {
    VectorEncoding encoding = RandomPicks.randomFrom(random(), VectorEncoding.values());
    try (Directory indexStore =
            getIndexStore(
                "field", encoding, new float[] {0, 1}, new float[] {1, 2}, new float[] {0, 0});
        IndexReader reader = DirectoryReader.open(indexStore)) {
      assert reader.leaves().size() == 1;
      LeafReaderContext context = reader.leaves().get(0);
      final VectorScorer vectorScorer;
      switch (encoding) {
        case BYTE:
          vectorScorer = context.reader().getByteVectorValues("field").scorer(new byte[] {1, 2});
          break;
        case FLOAT32:
          vectorScorer = context.reader().getFloatVectorValues("field").scorer(new float[] {1, 2});
          break;
        default:
          throw new IllegalArgumentException("unexpected vector encoding: " + encoding);
      }

      DocIdSetIterator iterator = vectorScorer.iterator();
      int numDocs = 0;
      while (iterator.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        numDocs++;
      }
      assertEquals(3, numDocs);
    }
  }

  public void testBulkAdvanceAndDocId() throws IOException {
    try (Directory dir =
            getIndexStore(
                "field",
                VectorEncoding.FLOAT32,
                new float[] {0, 1},
                new float[] {1, 2},
                new float[] {2, 3},
                new float[] {3, 4},
                new float[] {4, 5});
        IndexReader reader = DirectoryReader.open(dir)) {
      LeafReaderContext ctx = reader.leaves().get(0);

      // collect all vector doc IDs so we can target specific positions
      List<Integer> allDocs = new ArrayList<>();
      VectorScorer ref = ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 2});
      DocIdSetIterator refIt = ref.iterator();
      while (refIt.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        allDocs.add(refIt.docID());
      }
      assumeTrue("need at least 2 vector docs", allDocs.size() >= 2);

      VectorScorer scorer = ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 2});
      VectorScorer.Bulk bulk = scorer.bulk(null);
      DocAndFloatFeatureBuffer buf = new DocAndFloatFeatureBuffer();

      // score the first batch; after this call the iterator is positioned
      int stopAt = allDocs.get(allDocs.size() / 2) + 1;
      bulk.nextDocsAndScores(stopAt, null, buf);

      // docId() must be consistent with where the iterator stopped
      int currentDoc = bulk.docID();
      assertTrue("docId() should be >= 0 after scoring", currentDoc >= 0);

      // advance to the last known vector doc and verify docId() tracks it
      int lastDoc = allDocs.get(allDocs.size() - 1);
      if (lastDoc >= currentDoc) {
        int advanced = bulk.advance(lastDoc);
        assertEquals("advance() return value should equal docId()", advanced, bulk.docID());
      }

      // exhausting the iterator returns and holds NO_MORE_DOCS
      bulk.advance(DocIdSetIterator.NO_MORE_DOCS);
      assertEquals(DocIdSetIterator.NO_MORE_DOCS, bulk.docID());
    }
  }

  public void testBulkNextDocsAndScores() throws IOException {
    // Three docs whose exact scores we can predict with EUCLIDEAN similarity
    try (Directory dir =
            getIndexStore(
                "field",
                VectorEncoding.FLOAT32,
                new float[] {1, 0},
                new float[] {0, 1},
                new float[] {1, 1});
        IndexReader reader = DirectoryReader.open(dir)) {
      LeafReaderContext ctx = reader.leaves().get(0);
      float[] query = new float[] {1, 0};

      // collect expected scores via the iterator path
      VectorScorer refScorer = ctx.reader().getFloatVectorValues("field").scorer(query);
      List<Float> expected = new ArrayList<>();
      DocIdSetIterator it = refScorer.iterator();
      while (it.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        expected.add(refScorer.score());
      }

      // collect scores via the bulk path
      VectorScorer bulkScorer = ctx.reader().getFloatVectorValues("field").scorer(query);
      VectorScorer.Bulk bulk = bulkScorer.bulk(null);
      DocAndFloatFeatureBuffer buf = new DocAndFloatFeatureBuffer();
      List<Float> actual = new ArrayList<>();
      while (bulk.docID() != DocIdSetIterator.NO_MORE_DOCS) {
        bulk.nextDocsAndScores(DocIdSetIterator.NO_MORE_DOCS, null, buf);
        for (int i = 0; i < buf.size; i++) {
          actual.add(buf.features[i]);
        }
      }

      assertEquals(expected.size(), actual.size());
      for (int i = 0; i < expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i), 0f);
      }
    }
  }

  public void testBulkWithMatchingDocsFilter() throws IOException {
    // 4 docs with vectors; use a filter that only matches every other doc
    try (Directory dir =
            getIndexStore(
                "field",
                VectorEncoding.FLOAT32,
                new float[] {0, 1},
                new float[] {1, 2},
                new float[] {2, 3},
                new float[] {3, 4});
        IndexReader reader = DirectoryReader.open(dir)) {
      LeafReaderContext ctx = reader.leaves().get(0);
      int maxDoc = ctx.reader().maxDoc();

      // reference: collect all scored doc IDs without a filter
      VectorScorer refScorer =
          ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 1});
      List<Integer> allDocs = new ArrayList<>();
      DocIdSetIterator refIt = refScorer.iterator();
      while (refIt.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        allDocs.add(refIt.docID());
      }
      // need at least 2 vector docs to make the filter meaningful
      assumeTrue("need at least 2 vector docs", allDocs.size() >= 2);

      // build a filter that allows only the first half of the vector docs
      int cutoff = allDocs.get(allDocs.size() / 2);
      DocIdSetIterator filter =
          new DocIdSetIterator() {
            int doc = -1;

            @Override
            public int docID() {
              return doc;
            }

            @Override
            public int nextDoc() {
              return advance(doc + 1);
            }

            @Override
            public int advance(int target) {
              doc = target <= cutoff ? cutoff : NO_MORE_DOCS;
              return doc;
            }

            @Override
            public long cost() {
              return 1;
            }
          };

      VectorScorer scorer = ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 1});
      VectorScorer.Bulk bulk = scorer.bulk(filter);
      DocAndFloatFeatureBuffer buf = new DocAndFloatFeatureBuffer();
      List<Integer> filteredDocs = new ArrayList<>();
      while (bulk.docID() != DocIdSetIterator.NO_MORE_DOCS) {
        bulk.nextDocsAndScores(DocIdSetIterator.NO_MORE_DOCS, null, buf);
        for (int i = 0; i < buf.size; i++) {
          filteredDocs.add(buf.docs[i]);
        }
      }

      // every returned doc must be <= cutoff
      for (int doc : filteredDocs) {
        assertTrue("doc " + doc + " should be <= cutoff " + cutoff, doc <= cutoff);
      }
      assertFalse(
          "filtered result should be a strict subset", filteredDocs.size() == allDocs.size());
    }
  }

  public void testBulkRespectsLiveDocs() throws IOException {
    try (Directory dir =
            getIndexStore(
                "field",
                VectorEncoding.FLOAT32,
                new float[] {0, 1},
                new float[] {1, 2},
                new float[] {2, 3});
        IndexReader reader = DirectoryReader.open(dir)) {
      LeafReaderContext ctx = reader.leaves().get(0);
      int maxDoc = ctx.reader().maxDoc();

      // collect all vector doc IDs first
      VectorScorer refScorer =
          ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 1});
      List<Integer> allDocs = new ArrayList<>();
      DocIdSetIterator refIt = refScorer.iterator();
      while (refIt.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        allDocs.add(refIt.docID());
      }
      assumeTrue("need at least 2 vector docs", allDocs.size() >= 2);

      // mark the first vector doc as deleted
      FixedBitSet liveDocs = new FixedBitSet(maxDoc);
      liveDocs.set(0, maxDoc); // all live initially
      liveDocs.clear(allDocs.get(0)); // delete first vector doc

      VectorScorer scorer = ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 1});
      VectorScorer.Bulk bulk = scorer.bulk(null);
      DocAndFloatFeatureBuffer buf = new DocAndFloatFeatureBuffer();
      List<Integer> scoredDocs = new ArrayList<>();
      while (bulk.docID() != DocIdSetIterator.NO_MORE_DOCS) {
        bulk.nextDocsAndScores(DocIdSetIterator.NO_MORE_DOCS, liveDocs, buf);
        for (int i = 0; i < buf.size; i++) {
          scoredDocs.add(buf.docs[i]);
        }
      }

      assertFalse("deleted doc should not be scored", scoredDocs.contains(allDocs.get(0)));
      assertEquals(allDocs.size() - 1, scoredDocs.size());
    }
  }

  public void testBulkUpToBound() throws IOException {
    // verify that nextDocsAndScores stops at the upTo boundary
    try (Directory dir =
            getIndexStore(
                "field",
                VectorEncoding.FLOAT32,
                new float[] {0, 1},
                new float[] {1, 2},
                new float[] {2, 3},
                new float[] {3, 4});
        IndexReader reader = DirectoryReader.open(dir)) {
      LeafReaderContext ctx = reader.leaves().get(0);

      VectorScorer refScorer =
          ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 1});
      List<Integer> allDocs = new ArrayList<>();
      DocIdSetIterator refIt = refScorer.iterator();
      while (refIt.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        allDocs.add(refIt.docID());
      }
      assumeTrue("need at least 2 vector docs", allDocs.size() >= 2);

      int upTo = allDocs.get(allDocs.size() / 2); // stop partway through

      VectorScorer scorer = ctx.reader().getFloatVectorValues("field").scorer(new float[] {1, 1});
      VectorScorer.Bulk bulk = scorer.bulk(null);
      DocAndFloatFeatureBuffer buf = new DocAndFloatFeatureBuffer();
      bulk.nextDocsAndScores(upTo, null, buf);

      for (int i = 0; i < buf.size; i++) {
        assertTrue("doc " + buf.docs[i] + " should be < upTo " + upTo, buf.docs[i] < upTo);
      }
    }
  }

  /** Creates a new directory and adds documents with the given vectors as kNN vector fields */
  private Directory getIndexStore(String field, VectorEncoding encoding, float[]... contents)
      throws IOException {
    Directory indexStore = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), indexStore);
    for (int i = 0; i < contents.length; ++i) {
      Document doc = new Document();
      if (encoding == VectorEncoding.BYTE) {
        byte[] v = new byte[contents[i].length];
        for (int j = 0; j < v.length; j++) {
          v[j] = (byte) contents[i][j];
        }
        doc.add(new KnnByteVectorField(field, v, EUCLIDEAN));
      } else {
        doc.add(new KnnFloatVectorField(field, contents[i]));
      }
      doc.add(new StringField("id", "id" + i, Field.Store.YES));
      writer.addDocument(doc);
    }
    // Add some documents without a vector
    for (int i = 0; i < 5; i++) {
      Document doc = new Document();
      doc.add(new StringField("other", "value", Field.Store.NO));
      writer.addDocument(doc);
    }
    writer.forceMerge(1);
    writer.close();
    return indexStore;
  }
}
