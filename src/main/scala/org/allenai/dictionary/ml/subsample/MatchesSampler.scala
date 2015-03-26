package org.allenai.dictionary.ml.subsample

import nl.inl.blacklab.search.{ Hits, Searcher }
import org.allenai.common.Logging
import org.allenai.dictionary._

/** Samples hits that the given query already matches.
  */
case class MatchesSampler() extends Sampler() with Logging {

  /** Returns a modified query expression whose capture groups are limited
    * to matching entities in a table.
    */
  def limitQueryToTable(qexpr: QExpr, table: Table): QExpr = {
    require(table.cols.size == 1)
    val captureGroup = QueryLanguage.getCaptureGroups(qexpr).head
    val captureSize = QueryLanguage.getQueryLength(captureGroup)
    logger.debug(s"Query has capture size of $captureSize")

    val allRows = table.positive ++ table.negative
    val allWords = allRows.map(x => x.values.head.qwords)

    val limitedQuery =
      if (captureSize == -1) {
        logger.debug(s"Variable length capture group, using full " +
          s"table disjunction of size ${allWords.size}")
        QAnd(QDisj(allWords.map(x => QSeq(x))), captureGroup.qexpr)
      } else {
        // We can remove all entries that are not of the right size
        val filteredTable = allWords.filter(q => q.size == captureSize).map(x => QSeq(x))
        logger.debug(s"Fixed length capture group, table " +
          s"Disjunction filtered to ${filteredTable.size}")

        val captureSequence = QueryLanguage.getCaptureGroups(qexpr).head match {
          case QUnnamed(QSeq(seq)) => Some(seq)
          case QNamed(QSeq(seq), _) => Some(seq)
          case _ => None
        }

        if (captureSequence.nonEmpty &&
          captureSequence.get.size == captureSize &&
          captureSequence.get.forall(_.isInstanceOf[QWildcard])) {
          // All wildcards, just match table elements of the right size
          QDisj(filteredTable)
        } else {
          QAnd(QDisj(filteredTable), captureGroup.qexpr)
        }
      }

    def recurse(qexpr: QExpr): QExpr = qexpr match {
      case _: QLeaf => qexpr
      case QSeq(children) => QSeq(children.map(recurse))
      case QDisj(children) => QDisj(children.map(recurse))
      case QNamed(expr, name) => QNamed(limitedQuery, name)
      case QUnnamed(expr) => QUnnamed(limitedQuery)
      case l: QAtom => recurse(l.qexpr)
      case QAnd(expr1, expr2) => QAnd(recurse(expr1), recurse(expr2))
    }
    recurse(qexpr)
  }

  override def getSample(qexpr: QExpr, searcher: Searcher): Hits = {
    searcher.find(BlackLabSemantics.blackLabQuery(qexpr))
  }

  override def getLabelledSample(qexpr: QExpr, searcher: Searcher, table: Table): Hits = {
    require(table.cols.size == 1)
    val limitedQuery = limitQueryToTable(qexpr, table)
    searcher.find(BlackLabSemantics.blackLabQuery(limitedQuery))
  }
}
