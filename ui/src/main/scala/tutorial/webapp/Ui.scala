package tutorial.webapp

import org.scalajs.dom
import org.denigma.{codemirror ⇒ cm}
import org.denigma.codemirror.CodeMirror
import org.denigma.codemirror.extensions.EditorConfig
import org.scalajs.dom.raw.HTMLTextAreaElement

class Ui {
  import scalatags.JsDom.all
  import scalatags.JsDom.all._

  def editorDiv(divId: String, taId: String) = {
    div(id := divId, textarea(id := taId)).render
  }

  def editorDiv(
      divId: String,
      editorId: String,
      editorMode: String
  ): AEditor = {
    val ta = textarea(id := editorId).render.asInstanceOf[HTMLTextAreaElement]
    val d = div(id := divId, ta).render

    val params = EditorConfig.mode(editorMode).theme("solarized")
    val e = CodeMirror.fromTextArea(ta, params)
    e.setSize("50%", 50)

    val r = resultDiv(s"$divId-result")
    AEditor(d, r, e)
  }

  def resultDiv(
      divId: String) = {
    val d = div(id := divId).render
    d
  }

  def bufferDiv(buf: Buffer): DivType.DivType = {
    val divId = buf.ref.id

    def mkEditorDiv(editorMode: String) = {
      val editorId = s"$divId-ta"
      val ta = textarea(id := editorId, rows := 1, cols := 50).render.asInstanceOf[HTMLTextAreaElement]
      val editorDiv = div(id := divId, ta).render
      val params = EditorConfig.mode(editorMode)
          .theme("solarized")
          .autofocus(true)
      val editor = CodeMirror.fromTextArea(ta, params)

      editor.setSize("50%", "auto")
      DivType.Editor(editorDiv, editor)
    }

    def mkResultDiv = {
      DivType.Result(div(id := divId).render)
    }

    buf.tpe match {
      case BufferType.Editor(mode)      ⇒ mkEditorDiv(mode)
      case BufferType.Result(editorRef) ⇒ mkResultDiv
    }
  }

}

case class AEditor(editorDiv: dom.html.Div, resultDiv: dom.html.Div, editor: cm.Editor)

object DivType {
  sealed trait DivType {
    def div: dom.html.Div
  }
  case class Editor(override val div: dom.html.Div, editor: cm.Editor) extends DivType
  case class Result(override val div: dom.html.Div) extends DivType
}