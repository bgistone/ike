package org.allenai.ike

import scala.collection.immutable.Iterable

import java.{ lang, util }

import org.allenai.common.Config._
import org.allenai.common.Logging

import org.allenai.word2vec.Searcher.{ Match, UnknownWordException }
import org.allenai.word2vec.{ Searcher, Word2VecModel }
import com.typesafe.config.Config

import scala.collection.JavaConverters._
import scala.collection.{ mutable, SeqView }
import scala.util.{ Try, Success, Failure }

trait SimilarPhrasesSearcher {

  /** Given a phrase, returns upto maxNumSimilarPhrases closest phrases. The similarity measure
    * is dependent on the specific implementation of the SimilarPhrasesSearcher.
    */
  def getSimilarPhrases(phrase: String): Seq[SimilarPhrase]
  /** Given a set of phrases, retrieves and ranks phrases close to this set,
    * in terms of similarity measure. The similarity measure and ranking is dependent on the
    * specific implementation of the SimilarPhrasesSearcher.
    */
  def getSimilarPhrases(phraseSeq: Seq[String]): Seq[SimilarPhrase]
}

/** This class takes a sequence of EmbeddingBasedPhrasesSearchers as input and combines the
  * results produced by these EmbeddingBasedPhrasesSearchers using the combination strategy
  * defined in config file "combinationPhraseSearcher:combinationStrategy".
  */
class EmbeddingSearcherCombinator(searcherList: Seq[EmbeddingBasedPhrasesSearcher], config: Config)
    extends Logging
    with SimilarPhrasesSearcher {

  val embeddingBasedPhraseSearcherList: Seq[EmbeddingBasedPhrasesSearcher] = searcherList
  var prevEmbeddingSize = -1
  for (searcher <- searcherList) {
    val currentEmbeddingSize = searcher.embeddingSize
    if (prevEmbeddingSize > 0) {
      assert(currentEmbeddingSize == prevEmbeddingSize, "Embedding sizes of searchers being " +
        "combined in the EmbeddingSearcherCombinator should be the same.")
    }
    prevEmbeddingSize = currentEmbeddingSize
  }
  val combinationStrategy = config[String]("combinationStrategy")

  /** Given a phrase, returns upto maxNumSimilarPhrases closest phrases.
    */
  override def getSimilarPhrases(phrase: String): Seq[SimilarPhrase] = {
    val unionSetOfSimilarPhrases = (for {
      searcher <- embeddingBasedPhraseSearcherList
    } yield {
      val phraseWithUnderscores = phrase.replace(' ', '_').toLowerCase
      try {
        searcher.getSimilarPhrasesFromMatches(searcher.model.getMatches(
          phraseWithUnderscores,
          searcher.maxNumSimilarPhrases
        ))
      } catch {
        case _: UnknownWordException => Seq.empty
      }
    }).flatten

    // Group the set of similar phrases from all searchers by the phrase, and add up the similarity
    // scores
    groupAndCombineScoresOfSimilarPhrases(unionSetOfSimilarPhrases)
  }

  /** Given a bunch of phrases, computes their centroid and determines n closest word2vec
    * neighbors.
    * Utility function for table expansion.
    * @param phraseSeq
    */
  override def getSimilarPhrases(phraseSeq: Seq[String]): Seq[SimilarPhrase] = {
    val unionSetOfSimilarPhrases = (for {
      model <- embeddingBasedPhraseSearcherList
    } yield {
      val vectors = for {
        phrase <- phraseSeq
        vector <- model.getVectorForPhrase(phrase)
      } yield vector
      if (vectors.length > 0) {
        val centroidVector = vectors.reduceLeft[Vector[Double]] { (v1, v2) =>
          model.addVectors(
            v1,
            v2
          )
        } map
          (_ / vectors.length)
        model.getSimilarPhrases(centroidVector)
      } else {
        Seq.empty[SimilarPhrase]
      }
    }).flatten

    // Group the set of similar phrases from all searchers by the phrase, and combine the similarity
    // scores based on the combination strategy
    groupAndCombineScoresOfSimilarPhrases(unionSetOfSimilarPhrases)
  }

  def groupAndCombineScoresOfSimilarPhrases(similarPhraseSet: Seq[SimilarPhrase]): Seq[SimilarPhrase] = {
    if (combinationStrategy.equals("sum")) {
      similarPhraseSet.groupBy(_.qwords).map(group => new SimilarPhrase(group._1, group._2
        .map(_.similarity).sum)).toSeq.sortBy(-1 * _.similarity)
    } else if (combinationStrategy.equals("min")) {
      similarPhraseSet.groupBy(_.qwords).map(group => new SimilarPhrase(group._1, group._2
        .map(_.similarity).min)).toSeq.sortBy(-1 * _.similarity)
    } else if (combinationStrategy.equals("max")) {
      similarPhraseSet.groupBy(_.qwords).map(group => new SimilarPhrase(group._1, group._2
        .map(_.similarity).max)).toSeq.sortBy(-1 * _.similarity)
    } else {
      // default strategy is average
      similarPhraseSet.groupBy(_.qwords).map(group => new SimilarPhrase(group._1, group._2
        .map(_.similarity).sum / embeddingBasedPhraseSearcherList.size)).toSeq.sortBy(-1 * _
        .similarity)
    }
  }
}

/** This class constructs a similarity phrase searcher based on embeddings based vector
  * representations learnt from large text corpora. E.g. word2vec embeddings, PMI based embeddings.
  */
class EmbeddingBasedPhrasesSearcher(config: Config) extends Logging with SimilarPhrasesSearcher {

  val maxNumSimilarPhrases = 100

  val model = {
    logger.info("Loading phrase vectors ...")
    val file = DataFile.fromDatastore(config[Config]("vectors"))
    val format = config[String]("format")
    val result = if (format.equals("binary")) {
      Word2VecModel.fromBinFile(file).forSearch()
    } else {
      Word2VecModel.fromTextFile(file).forSearch()
    }
    logger.info("Loading phrase vectors complete")
    result
  }

  val embeddingSize = config[Int]("embeddingSize")

  /** Given a phrase, returns upto maxNumSimilarPhrases closest phrases.
    * @param phrase
    * @return
    */
  override def getSimilarPhrases(phrase: String): Seq[SimilarPhrase] = {
    val phraseWithUnderscores = phrase.replace(' ', '_').toLowerCase
    try {
      getSimilarPhrasesFromMatches(model.getMatches(phraseWithUnderscores, maxNumSimilarPhrases))
    } catch {
      case _: UnknownWordException => Seq.empty
    }
  }

  /** Given a vector, returns upto maxNumSimilarPhrases closest phrases.
    * @param vector
    * @return
    */
  def getSimilarPhrases(vector: Vector[Double]): Seq[SimilarPhrase] = {
    try {
      getSimilarPhrasesFromMatches(model.getMatches(vector.toArray, maxNumSimilarPhrases))
    } catch {
      case _: UnknownWordException => Seq.empty
    }
  }

  /** Helper Method to construct a Seq of SimilarPhrases from matches returned by the Searcher.
    * @param matches
    * @return
    */
  def getSimilarPhrasesFromMatches(matches: util.List[Match]): Seq[SimilarPhrase] = {
    matches.asScala.map { m =>
      val qwords = m.`match`().split("_").map(QWord)
      SimilarPhrase(qwords, m.distance())
    }
  }

  /** Returns a word2vec Vector for a given phrase if found.
    * @param phrase
    * @return
    */
  def getVectorForPhrase(phrase: String): Option[Vector[Double]] = {
    val phraseWithUnderscores = phrase.replace(' ', '_').toLowerCase
    try {
      Some(model.getRawVector(phraseWithUnderscores).asScala.toVector.map(_.doubleValue))
    } catch {
      case _: UnknownWordException => None
    }
  }

  /** Given a bunch of phrases, computes their centroid and determines n closest word2vec neighbors.
    * Utility function for table expansion.
    * @param phraseSeq
    */
  override def getSimilarPhrases(phraseSeq: Seq[String]): Seq[SimilarPhrase] = {
    val vectors = for {
      phrase <- phraseSeq
      vector <- getVectorForPhrase(phrase)
    } yield vector

    if (vectors.length > 0) {
      val centroidVector = vectors.reduceLeft[Vector[Double]] { (v1, v2) => addVectors(v1, v2) } map
        (_ / vectors.length)
      getSimilarPhrases(centroidVector)
    } else {
      Seq.empty[SimilarPhrase]
    }
  }

  /** Helper Method to add two vectors of Doubles.
    */
  def addVectors(vector1: Vector[Double], vector2: Vector[Double]): Vector[Double] = {
    vector1 zip vector2 map { case (x, y) => x + y }
  }
}
