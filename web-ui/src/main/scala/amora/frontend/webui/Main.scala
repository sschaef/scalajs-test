package amora.frontend.webui

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{ global ⇒ jsg }
import scala.scalajs.js.JSApp
import scala.scalajs.js.JSON
import scala.scalajs.js.annotation.JSExport
import scala.util.Failure
import scala.util.Success

import org.scalajs.dom
import org.scalajs.dom.raw.Event
import org.scalajs.dom.raw.HTMLElement
import org.scalajs.dom.raw.MouseEvent
import org.scalajs.dom.raw.XMLHttpRequest

import amora.frontend.webui.protocol._

@JSExport
object Main extends JSApp {
  private val $ = org.scalajs.jquery.jQuery
  private val log = dom.console

  implicit class AsDynamic[A](private val a: A) extends AnyVal {
    def jsg: js.Dynamic = a.asInstanceOf[js.Dynamic]
  }

  def f2[R](f: (js.Any, js.Any) ⇒ R): js.Function2[js.Any, js.Any, R] =
    js.Any.fromFunction2((arg0: js.Any, arg1: js.Any) ⇒ f(arg0, arg1))

  private val connection = new Connection(handleResponse)

  override def main(): Unit = {
    connection.setup()
  }

  def handleResponse(response: Response): Unit = response match {
    case ConnectionSuccessful ⇒
      dom.console.info(s"Connection to server established. Communication is now possible.")
      showMainPage()

    case resp: QueueItems ⇒
      handleQueueItems(resp)

    case resp: QueueItem ⇒
      handleQueueItem(resp)

    case resp: Schemas ⇒
      handleSchemas(resp)

    case resp: Schema ⇒
      handleSchema(resp)

    case resp: RequestSucceeded ⇒
      handleRequestSucceeded(resp)

    case resp: RequestFailed ⇒
      handleRequestFailed(resp)

    case msg ⇒
      dom.console.error(s"Unexpected message arrived: $msg")
  }

  def showMainPage() = {
    import scalatags.JsDom.all._

    val content = div(
        h3("Knowledge Base"),
        ul(
          li(id := "li1", a(href := "", "Show queue", onclick := "return false;")),
          li(id := "li2", a(href := "", "Show schemas", onclick := "return false;"))
        ),
        div(id := "content"),
        div(pre(code(id := "editor", """
          |class X {
          |  val xs: List[Int] = List(1)
          |  val ys: List[Int] = xs
          |}
          |class Y {
          |  val x = new X
          |  def f() = {
          |    val xs = x.xs
          |    xs
          |  }
          |  import x._
          |  val zs = xs
          |}
        """.stripMargin.trim()))),
        button(id := "editorButton", `type` := "button", "index code")
    ).render
    $("body").append(content)

    handleClickEvent("li1")(_ ⇒ connection.send(GetQueueItems))
    handleClickEvent("li2")(_ ⇒ connection.send(GetSchemas))
    handleClickEvent("editorButton") { _ ⇒
      val text = $("#editor").text()
      onSuccess(indexScalaSrc(text)) { resp ⇒
        log.log(resp)
      }
    }
    val d = htmlElem("editor")
    d.onmouseup = (e: MouseEvent) ⇒ {
      val sel = selection("editor")

      onSuccess(findDeclaration(sel.start)) { range ⇒
        log.info("findDeclaration: " + range)
      }
      onSuccess(findUsages(sel.start)) { ranges ⇒
        val text = $("#editor").text()
        val sb = new StringBuilder
        var begin = 0
        for (Range(start, end) ← ranges) {
          sb.append(text.substring(begin, start))
          sb.append("""<span style="background-color: #84C202">""")
          sb.append(text.substring(start, end))
          sb.append("</span>")
          begin = end
        }
        sb.append(text.substring(begin, text.length))
        $("#editor").html(sb.toString())
      }
    }

    def findUsages(offset: Int): Future[Seq[Range]] = {
      val n3Resp = serviceRequest(s"""
        @prefix service:<http://amora.center/kb/Schema/Service/0.1/> .
        @prefix registry:<http://amora.center/kb/Service/0.1/> .
        @prefix request:<http://amora.center/kb/ServiceRequest/0.1/> .
        <#this>
          a request: ;
          service:serviceId registry:FindUsages ;
          service:method [
            service:name "run" ;
            service:param [
              service:name "offset" ;
              service:value $offset ;
            ] ;
          ] ;
        .
      """)

      val model = n3Resp flatMap { n3Resp ⇒
        modelAsData(n3Resp, """
          prefix rdf:<http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          prefix service:<http://amora.center/kb/Schema/Service/0.1/>
          prefix decl:<http://amora.center/kb/amora/Schema/0.1/Decl/0.1/>
          select ?start ?end where {
            ?s service:result ?r .
            ?r decl:posStart ?start ; decl:posEnd ?end .
          }
          order by ?start ?end
        """)
      }

      model map { model ⇒
        val res = for (elem ← model.asInstanceOf[js.Array[js.Any]]) yield {
          val start = elem.jsg.start.value.toString.toInt
          val end = elem.jsg.end.value.toString.toInt
          Range(start, end)
        }
        res.toList
      }
    }

    def findDeclaration(offset: Int): Future[Option[Range]] = {
      val n3Resp = serviceRequest(s"""
        @prefix service:<http://amora.center/kb/Schema/Service/0.1/> .
        @prefix registry:<http://amora.center/kb/Service/0.1/> .
        @prefix request:<http://amora.center/kb/ServiceRequest/0.1/> .
        <#this>
          a request: ;
          service:serviceId registry:FindDeclaration ;
          service:method [
            service:name "run" ;
            service:param [
              service:name "offset" ;
              service:value $offset ;
            ] ;
          ] ;
        .
      """)

      val model = n3Resp flatMap { n3Resp ⇒
        modelAsData(n3Resp, """
          prefix service:<http://amora.center/kb/Schema/Service/0.1/>
          prefix decl:<http://amora.center/kb/amora/Schema/0.1/Decl/0.1/>
          select ?start ?end where {
            ?s service:result ?r .
            ?r decl:posStart ?start ; decl:posEnd ?end .
          }
        """)
      }

      model map { model ⇒
        val arr = model.asInstanceOf[js.Array[js.Any]]
        if (arr.isEmpty)
          None
        else {
          val start = arr(0).jsg.start.value.toString.toInt
          val end = arr(0).jsg.end.value.toString.toInt
          Some(Range(start, end))
        }
      }
    }
  }

  def modelAsData(n3Model: String, query: String): Future[js.Any] = {
    val p = Promise[js.Any]

    def handleErr(err: js.Any, msg: String)(onSuccess: ⇒ Unit): Unit = {
      if (err == null)
        onSuccess
      else
        p.failure(new IllegalStateException(s"$msg\n$err"))
    }

    jsg.Bundle.rdfstore.create(f2 { (err, store) ⇒
      handleErr(err, "Error occurred while loading rdfstore.") {
        store.jsg.load("text/n3", n3Model, f2 { (err, loadedTriples) ⇒
          handleErr(err, "Error occurred while loading n3 data.") {
            // we can't inline `?r`, see https://github.com/antoniogarrote/rdfstore-js/issues/141
            store.jsg.execute(query, f2 { (err, graph) ⇒
              handleErr(err, "Error occurred while executing SPARQL query.") {
                p.success(graph)
              }
            })
          }
        })
      }
    })
    p.future
  }

  def htmlElem(id: String): HTMLElement =
    dom.document.getElementById(id).asInstanceOf[HTMLElement]

  /**
   * Returns the selection that belongs to a HTML element of a given `id` as an
   * instance of [[Range]], whose `start` and `end` are relative to the
   * beginning of the HTML element.
   */
  def selection(id: String): Range = {
    val range = dom.window.getSelection().getRangeAt(0)
    val content = range.cloneRange()
    content.selectNodeContents(htmlElem(id))
    content.setEnd(range.startContainer, range.startOffset)
    val start = content.toString().length()

    content.setStart(range.startContainer, range.startOffset)
    content.setEnd(range.endContainer, range.endOffset)
    val len = content.toString().length()
    Range(start, start+len)
  }

  case class Range(start: Int, end: Int)

  def indexScalaSrc(src: String): Future[String] = {
    val data = s"""{
      |  "tpe": "scala-sources",
      |  "files": [
      |    {
      |      "fileName": "test.scala",
      |      "src": "${src.replace("\n", "\\n").replace("\"", "\\\"")}"
      |    }
      |  ]
      |}""".stripMargin
    val p = Promise[String]
    val r = new XMLHttpRequest
    r.open("POST", "http://amora.center/add-json", async = true)
    r.setRequestHeader("Content-type", "text/plain")
    r.setRequestHeader("Charset", "UTF-8")
    r.onreadystatechange = (e: Event) ⇒ {
      if (r.readyState == XMLHttpRequest.DONE) {
        if (r.status == 200)
          p.success(r.responseText)
        else
          p.failure(new IllegalStateException(s"Server responded with an error to add-json request.\nRequest: $data\nResponse (error code: ${r.status}): ${r.responseText}"))
      }
    }
    r.send(data)
    p.future
  }

  /**
   * Sends a SPARQL request. The response is encoded in
   * `application/sparql-results+json`.
   */
  def sparqlRequest(query: String): Future[String] = {
    val p = Promise[String]
    val r = new XMLHttpRequest
    r.open("POST", "http://amora.center/sparql", async = true)
    r.setRequestHeader("Content-type", "application/sparql-query")
    r.setRequestHeader("Accept", "application/sparql-results+json")
    r.setRequestHeader("Charset", "UTF-8")
    r.onreadystatechange = (e: Event) ⇒ {
      if (r.readyState == XMLHttpRequest.DONE) {
        if (r.status == 200)
          p.success(r.responseText)
        else
          p.failure(new IllegalStateException(s"Server responded with an error to SPARQL request.\nRequest: $query\nResponse (error code: ${r.status}): ${r.responseText}"))
      }
    }
    r.send(query)
    p.future
  }

  def onSuccess[A](fut: Future[A])(f: A ⇒ Unit): Unit = {
    fut onComplete {
      case Success(s) ⇒
        f(s)
      case Failure(f) ⇒
        dom.console.error(f.getMessage)
    }
  }

  /**
   * Sends a service request, which needs to be encoded in `text/n3`. The
   * response is also encoded in `text/n3`.
   */
  def serviceRequest(n3Req: String): Future[String] = {
    val p = Promise[String]
    val r = new XMLHttpRequest
    r.open("POST", "http://amora.center/service", async = true)
    r.setRequestHeader("Content-type", "text/n3")
    r.setRequestHeader("Accept", "text/n3")
    r.setRequestHeader("Charset", "UTF-8")
    r.onreadystatechange = (e: Event) ⇒ {
      if (r.readyState == XMLHttpRequest.DONE) {
        if (r.status == 200)
          p.success(r.responseText)
        else
          p.failure(new IllegalStateException(s"Server responded with an error to service request.\nRequest: $n3Req\nResponse (error code: ${r.status}): ${r.responseText}"))
      }
    }
    r.send(n3Req)
    p.future
  }

  def handleRequestSucceeded(succ: RequestSucceeded) = {
    import scalatags.JsDom.all._

    val content = div(style := "background-color: green", raw(succ.msg)).render
    $("#content").empty().append(content)
  }

  def handleRequestFailed(fail: RequestFailed) = {
    import scalatags.JsDom.all._

    val content = div(style := "background-color: red", raw(fail.msg)).render
    $("#content").empty().append(content)
  }

  def handleQueueItems(items: QueueItems) = {
    import scalatags.JsDom.all._
    val content = div(
      h4("Queue Items"),
      ul(
        if (items.items.isEmpty)
          li("No items")
        else
          for (i ← items.items) yield li(id := s"item$i", a(href := "", s"Item $i", onclick := "return false;"))
      )
    ).render
    $("#content").empty().append(content)

    for (i ← items.items) handleClickEvent(s"item$i")(_ ⇒ connection.send(GetQueueItem(i)))
  }

  def handleQueueItem(item: QueueItem) = {
    import scalatags.JsDom.all._
    if (item.appendLog) {
      val d = dom.document.getElementById(s"item${item.id}").asInstanceOf[dom.html.TextArea]
      d.value += item.log
    } else {
      val content = div(
        h4(s"Queue Item ${item.id}"),
        textarea(id := s"item${item.id}", rows := "20", cols := "150", item.log)
      ).render
      $("#content").empty().append(content)
    }
  }

  def handleSchemas(schemas: Schemas) = {
    import scalatags.JsDom.all._
    val content = div(
      h4(s"Schemas"),
      select(
        id := "schemas",
        for (schemaName ← schemas.schemaNames) yield
          if (schemaName == schemas.defaultSchema.name)
            option(selected := "", schemaName)
          else
            option(schemaName)
      ),
      div(id := "schema")
    ).render
    $("#content").empty().append(content)

    handleSchema(schemas.defaultSchema)
    val d = dom.document.getElementById("schemas").asInstanceOf[dom.html.Select]
    d.onchange = (_: Event) ⇒ {
      val selectedSchema = d.options(d.selectedIndex).textContent
      connection.send(GetSchema(selectedSchema))
    }
  }

  // TODO Get rid of this @JSExport
  // It is a hack which was needed to call the Scala code from Alpaca.js
  @JSExport
  def handleFormSubmit(elem: js.Object) = {
    val value = elem.jsg.getValue()
    val formattedJson = JSON.stringify(value, null: js.Function2[String, js.Any, js.Any], "  ")
    connection.send(IndexData(formattedJson))
  }

  def handleSchema(schema: Schema) = {
    import scalatags.JsDom.all._
    val content = div(
      div(id := "schemaForm"),
      // TODO Replace this JS script with Scala code
      // We should use `$("#schemaForm").jsg.alpaca(JSON.parse(schema.jsonSchema))`
      // but we can't yet because the json schema contains non JSON code but some
      // JS definitions which Scala.js doesn't understand. Once we got rid with the
      // JS definitions, we can also fix this issue.
      script(`type` := "text/javascript", raw(s"""
        $$("#schemaForm").alpaca(${schema.jsonSchema});
      """))
    ).render
    $("#schema").empty().append(content)
  }

  private def handleClickEvent(id: String)(f: MouseEvent ⇒ Unit) = {
    val d = htmlElem(id)
    d.onclick = (e: MouseEvent) ⇒ f(e)
  }
}
