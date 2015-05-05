package org.allenai.dictionary.ml.queryop

import org.allenai.common.testkit.UnitSpec
import org.allenai.dictionary.{QPos, QWord}
import org.allenai.dictionary.ml._

import scala.collection.immutable.IntMap

class TestOpGenerator extends UnitSpec {

  "getRepeatedOpMatch" should "Create correct repeated ops" in {
    val matches = QueryMatches(QuerySlotData(
    Some(QWord("d")), QueryToken(1), false, false, true), Seq(
      QueryMatch(Seq(Token("b", "NN", "0"), Token("b", "NN", "0")), true),
      QueryMatch(Seq(Token("a", "NN", "0")), true)
    ))
  
    val leafGen = QLeafGenerator(true, true, Seq())
  
    val rOps = OpGenerator.getRepeatedOpMatch(matches, leafGen)
    assertResult(IntMap(0 -> 0))(rOps(SetRepeatedToken(1, 1, QWord("b"))))
    assertResult(IntMap(1 -> 0))(rOps(SetRepeatedToken(1, 1, QWord("a"))))
    assertResult(IntMap(0 -> 0))(rOps(SetRepeatedToken(1, 2, QWord("b"))))
    assertResult(IntMap(0 -> 0, 0 -> 0))(rOps(SetRepeatedToken(1, 2, QPos("NN"))))
    assertResult(IntMap(0 -> 0))(rOps(SetRepeatedToken(1, 2, QPos("NN"))))
    assertResult(rOps.size)(5)
  }
}
