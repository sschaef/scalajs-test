package backend.indexer

import scala.util.Failure
import scala.util.Success

import org.junit.Test

import backend.TestUtils
import backend.actors.IndexerMessage._

class ModelIndexerTest {
  import TestUtils._

  case class Data(varName: String, value: String)

  def ask(modelName: String, rawQuery: String, data: Indexable*): Seq[Data] = {
    val query = rawQuery.replaceFirst("""\?MODEL\?""", modelName)
    val res = Indexer.withInMemoryDataset { dataset ⇒
      Indexer.withModel(dataset, modelName) { model ⇒
        data foreach {
          case artifact: Artifact ⇒ Indexer.addArtifact(modelName, artifact)(model).get
          case file: File ⇒ Indexer.addFile(modelName, file)(model).get
        }

        if (debugTests) {
          Indexer.queryResultAsString(modelName, "select * { ?s ?p ?o }", model) foreach println
          Indexer.queryResultAsString(modelName, query, model) foreach println
        }
        Indexer.queryResult(modelName, query, model) { (v, q) ⇒
          val res = q.get(v)
          require(res != null, s"The variable `$v` does not exist in the result set.")
          Data(v, res.toString)
        }
      }.flatten
    }.flatten
    res match {
      case Success(res) ⇒
        res.sortBy(d ⇒ (d.varName, d.value))
      case Failure(f) ⇒
        throw new RuntimeException("An error happened during the test.", f)
    }
  }

  def modelName = "http://test.model/"

  @Test
  def find_single_project() = {
    val project = Project("project")
    val artifact = Artifact(project, "organization", "name", "v1")

    ask(modelName, """
        PREFIX c:<?MODEL?>
        SELECT * WHERE {
          [a c:Project] c:name ?name .
        }
      """, artifact) === Seq(Data("name", "project"))
  }

  @Test
  def files_with_same_name_of_different_artifacts() = {
    val project = Project("project")
    val artifact1 = Artifact(project, "organization", "name", "v1")
    val artifact2 = Artifact(project, "organization", "name", "v2")
    val file1 = File(artifact1, "a/b/c/Test.scala", Seq())
    val file2 = File(artifact2, "a/b/c/Test.scala", Seq())

    ask(modelName, """
        PREFIX c:<?MODEL?>
        SELECT * WHERE {
          [a c:File] c:name ?name .
        }
      """, artifact1, artifact2, file1, file2) === Seq(Data("name", "a/b/c/Test.scala"), Data("name", "a/b/c/Test.scala"))
  }
}