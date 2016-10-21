package amora.backend.indexer

import java.io.ByteArrayOutputStream
import java.io.{ File ⇒ JFile }
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import amora.backend.Logger
import amora.backend.schema
import amora.converter.ClassfileConverter
import amora.converter.protocol._
import scalaz._
import scalaz.concurrent.Task

object ArtifactFetcher {
  sealed trait DownloadStatus {
    def isError: Boolean
  }
  final case class DownloadSuccess(artifact: Artifact, file: JFile) extends DownloadStatus {
    override def isError = false
  }
  final case class DownloadError(artifactName: String, reason: Option[String]) extends DownloadStatus {
    override def isError = true
  }
}

trait ArtifactFetcher {
  import ArtifactFetcher._

  def logger: Logger
  def sparqlUpdate(query: String, errMsg: ⇒ String): Unit
  def cacheLocation: JFile

  def handleArtifacts(artifacts: Seq[Artifact]): Seq[DownloadStatus] = {
    val res = artifacts flatMap fetchArtifact
    val (errors, succs) = res.partition(_.isError)
    val succMsgs = succs.collect {
      case DownloadSuccess(_, file) ⇒
        file.getName
    }
    val errMsgs = errors.collect {
      case DownloadError(artifactName, reasonOpt) ⇒
        if (reasonOpt.isDefined)
          artifactName+"because of: "+reasonOpt.get
        else
          artifactName
    }
    val succMsg = if (succs.isEmpty) Nil else Seq(s"Fetched artifacts:" + succMsgs.sorted.mkString("\n  ", "\n  ", ""))
    val errMsg = if (errors.isEmpty) Nil else Seq(s"Failed to fetch artifacts:" + errMsgs.sorted.mkString("\n  ", "\n  ", ""))
    val msg = Seq(succMsg, errMsg).flatten.mkString("\n")

    logger.info(msg)

    if (errors.isEmpty)
      indexArtifacts(succs.asInstanceOf[Seq[DownloadSuccess]])

    res
  }

  private def indexArtifacts(artifacts: Seq[DownloadSuccess]) = {
    logger.info(s"No errors happened during fetching of artifacts. Start indexing of ${artifacts.size} artifacts now.")
    artifacts foreach {
      case DownloadSuccess(artifact, file) ⇒
        val files = indexArtifact(artifact, file)
        val artifactQuery = schema.Schema.mkSparqlUpdate(Seq(artifact))
        logger.info(s"Indexing artifact ${artifact.organization}/${artifact.name}/${artifact.version} with ${files.size} files.")
        sparqlUpdate(artifactQuery, s"Error happened while indexing artifact ${artifact.organization}/${artifact.name}/${artifact.version}.")

        files.zipWithIndex foreach {
          case ((file @ File(_, fileName), hierarchy), i) ⇒
            // TODO
            val fileQuery = schema.Schema.mkSparqlUpdate(Seq(file))
            val dataQuery = schema.Schema.mkSparqlUpdate(file, hierarchy)
            logger.info(s"Indexing file $i ($fileName) with ${hierarchy.size} entries.")
            sparqlUpdate(fileQuery, s"Error happened while indexing $fileName.")
            sparqlUpdate(dataQuery, s"Error happened while indexing $fileName.")
        }
    }
    logger.info(s"Successfully indexed ${artifacts.size} artifacts.")
  }

  private def fetchArtifact(artifact: Artifact): Seq[DownloadStatus] = {
    import coursier.{ Artifact ⇒ _, Project ⇒ _, _ }
    val start = Resolution(Set(Dependency(Module(artifact.organization, artifact.name), artifact.version)))
    val repos = Seq(MavenRepository("https://repo1.maven.org/maven2"))
    val fetch = Fetch.from(repos, Cache.fetch(cache = cacheLocation, logger = Some(coursierLogger)))
    val resolution = start.process.run(fetch).run

    if (resolution.errors.nonEmpty)
      resolution.errors.flatMap(_._2).map(DownloadError(_, None))
    else {
      val localArtifacts = Task.gatherUnordered(
        resolution.dependencyArtifacts.map {
          case (dependency, coursierArtifact) ⇒
            Cache.file(coursierArtifact, cache = cacheLocation, logger = Some(coursierLogger)).map(f ⇒ artifact → f).run
        }
      ).run

      localArtifacts.map {
        case -\/(f) ⇒
          DownloadError(f.message, Some(f.`type`))
        case \/-((artifact, file)) ⇒
          DownloadSuccess(artifact, file)
      }
    }
  }

  private def indexArtifact(artifact: Artifact, file: JFile): Seq[(File, Seq[Hierarchy])] = {
    import scala.collection.JavaConverters._
    require(file.getName.endsWith(".jar"), "Artifact needs to be a JAR file")

    def entryToHierarchy(zip: ZipFile, entry: ZipEntry) = {
      val bytes = using(zip.getInputStream(entry))(readInputStream)
      val hierarchy = new ClassfileConverter().convert(bytes).get
      File(artifact, entry.getName) → hierarchy
    }

    def zipToHierarchy(zip: ZipFile) = {
      val entries = zip.entries().asScala
      val filtered = entries.filter(_.getName.endsWith(".class"))
      filtered.map(entry ⇒ entryToHierarchy(zip, entry)).toList
    }

    using(new ZipFile(file))(zipToHierarchy)
  }

  private def readInputStream(in: InputStream): Array[Byte] = {
    val out = new ByteArrayOutputStream

    var nRead = 0
    val bytes = new Array[Byte](1 << 12)
    while ({nRead = in.read(bytes, 0, bytes.length); nRead != -1})
      out.write(bytes, 0, nRead)

    out.toByteArray()
  }

  private def using[A <: { def close(): Unit }, B](closeable: A)(f: A ⇒ B): B =
    try f(closeable) finally closeable.close()

  private val coursierLogger = new coursier.Cache.Logger {
    override def foundLocally(url: String, file: JFile): Unit = {
      logger.info(s"[found-locally] url: $url, file: $file")
    }

    override def downloadingArtifact(url: String, file: JFile): Unit = {
      logger.info(s"[downloading-artifact] url: $url, file: $file")
    }

    override def downloadLength(url: String, totalLength: Long, alreadyDownloaded: Long): Unit = {
      logger.info(s"[download-length] url: $url, total-length: $totalLength, already-downloaded: $alreadyDownloaded")
    }

    override def downloadProgress(url: String, downloaded: Long): Unit = {
      logger.info(s"[download-progress] url: $url, downloaded: $downloaded")
    }

    override def downloadedArtifact(url: String, success: Boolean): Unit = {
      logger.info(s"[downloaded-artifact] url: $url, success: $success")
    }

    override def checkingUpdates(url: String, currentTimeOpt: Option[Long]): Unit = {
      logger.info(s"[checking-updates] url: $url, current-time-opt: $currentTimeOpt")
    }

    override def checkingUpdatesResult(url: String, currentTimeOpt: Option[Long], remoteTimeOpt: Option[Long]): Unit = {
      logger.info(s"[checking-updates-result] url: $url, current-time-opt: $currentTimeOpt, remote-time-opt: $remoteTimeOpt")
    }
  }
}
