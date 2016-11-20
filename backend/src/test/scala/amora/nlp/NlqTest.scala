package amora.nlp

import org.junit.Test

import amora.backend.indexer.RestApiTest
import amora.converter.protocol.Artifact
import amora.converter.protocol.Project

class NlqTest extends RestApiTest {
  import amora.TestUtils._

  @Test
  def list_classes(): Unit = {
    indexData(Artifact(Project("p"), "o", "n", "v1"),
      "x.scala" → """
        class A
        class B
        class C
      """)
    nlqRequest("list classes") === Seq(
      "http://amora.center/kb/amora/Class/p/o/n/v1/A",
      "http://amora.center/kb/amora/Class/p/o/n/v1/B",
      "http://amora.center/kb/amora/Class/p/o/n/v1/C"
    )
  }

  @Test
  def list_traits(): Unit = {
    indexData(Artifact(Project("p"), "o", "n", "v1"),
      "x.scala" → """
        trait A
        class B
        trait C
      """)
    nlqRequest("list traits") === Seq(
      "http://amora.center/kb/amora/Trait/p/o/n/v1/A",
      "http://amora.center/kb/amora/Trait/p/o/n/v1/C"
    )
  }
}