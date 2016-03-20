package indexer

import scala.util._

import org.junit.Test

import indexer.hierarchy.Hierarchy
import plugin.TestUtils

class IndexerTest {

  import TestUtils._

  case class Data(varName: String, value: String)

  def ask(modelName: String, data: Seq[(String, Seq[Hierarchy])], query: String): Seq[Data] = {
    val res = Indexer.withInMemoryDataset { dataset ⇒
      Indexer.withModel(dataset, modelName) { model ⇒
        data foreach {
          case (filename, data) ⇒
            Indexer.add(modelName, filename, data)(model)
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

  @Test
  def find_top_level_classes() = {
    val modelName = "http://test.model/"
    ask(modelName, convertToHierarchy(
      "<memory>" → """
        package a.b.c
        class C1
        class C2
        class C3
      """), s"""
        PREFIX c:<$modelName>
        SELECT * WHERE {
          ?class c:tpe "class" .
        }
      """) === Seq(
        Data("class", s"${modelName}_root_/a/b/c/C1"),
        Data("class", s"${modelName}_root_/a/b/c/C2"),
        Data("class", s"${modelName}_root_/a/b/c/C3"))
    }

  @Test
  def find_methods_in_top_level_classes() = {
    val modelName = "http://test.model/"
    ask(modelName, convertToHierarchy(
      "<memory>" → """
        package a.b.c
        class C1 {
          def m1 = 0
        }
        class C2 {
          def m2 = 0
        }
        class C3 {
          def m3 = 0
        }
      """), s"""
        PREFIX c:<$modelName>
        SELECT * WHERE {
          ?member c:tpe "member" .
        }
      """) === Seq(
        Data("member", s"${modelName}_root_/a/b/c/C1/m1"),
        Data("member", s"${modelName}_root_/a/b/c/C2/m2"),
        Data("member", s"${modelName}_root_/a/b/c/C3/m3"))
  }

  @Test
  def find_all_methods_of_single_class() = {
    val modelName = "http://test.model/"
    ask(modelName, convertToHierarchy(
      "<memory>" → """
        package a.b.c
        class C1 {
          def m11 = 0
          def m12 = 0
        }
        class C2 {
          def m2 = 0
        }
        class C3 {
          def m3 = 0
        }
      """), s"""
        PREFIX c:<$modelName>
        PREFIX s:<http://schema.org/>
        SELECT ?member WHERE {
          ?class c:tpe "class" .
          ?class s:name ?className .
          FILTER (str(?className) = "C1") .
          ?member c:parent ?class .
        }
      """) === Seq(
        Data("member", s"${modelName}_root_/a/b/c/C1/m11"),
        Data("member", s"${modelName}_root_/a/b/c/C1/m12"))
  }

  @Test
  def find_classes_of_single_file() = {
    val modelName = "http://test.model/"
    ask(modelName, convertToHierarchy(
      "f1.scala" → """
        package a.b.c
        class C1
        class C2
      """,
      "f2.scala" → """
        package d.e.f
        class D1
        class D2
      """), s"""
        PREFIX c:<$modelName>
        SELECT ?class WHERE {
          ?class c:tpe "class" .
          ?class c:file "f1.scala" .
        }
      """) === Seq(
        Data("class", s"${modelName}_root_/a/b/c/C1"),
        Data("class", s"${modelName}_root_/a/b/c/C2"))
  }

  @Test
  def find_usages() = {
    val modelName = "http://test.model/"
    ask(modelName, convertToHierarchy(
      "f1.scala" → """
        package a.b.c
        import d.e.f.Y
        class X {
          def m: Y = null
        }
      """,
      "f2.scala" → """
        package d.e.f
        class Y
      """), s"""
        PREFIX c:<$modelName>
        PREFIX s:<http://schema.org/>
        SELECT ?usage WHERE {
          ?class c:tpe "class" .
          ?class s:name ?className .
          FILTER (str(?className) = "Y") .
          ?ref c:reference ?class .
          ?ref c:usage ?usage .
        }
      """) === Seq(
        Data("usage", s"${modelName}_root_/a/b/c/X/m"),
        Data("usage", s"${modelName}_root_/d/e/f"))
  }
}