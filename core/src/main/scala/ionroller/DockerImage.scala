package ionroller

import play.api.libs.functional.syntax._
import play.api.libs.json._

final case class DockerImage(repository: DockerRepository, tag: ReleaseVersion) {
  override def toString =
    s"$repository:${tag.tag}"
}

object DockerImage {
  implicit lazy val jsonFormat: Format[DockerImage] = {
    def unapply(i: DockerImage): (String, String, ReleaseVersion) = {
      (i.repository.account, i.repository.name, i.tag)
    }

    (
      (JsPath \ "repository").format[String] and
      (JsPath \ "name").format[String] and
      (JsPath \ "tag").format[ReleaseVersion]
    )(DockerImage.apply, unapply)
  }

  def apply(repository: String, name: String, version: ReleaseVersion): DockerImage = {
    DockerImage(DockerRepository(repository, name), version)
  }

  def apply(imageString: String): DockerImage = {
    val Pattern = """((?:[a-zA-Z0-9\._\-\/:]+/)?[a-zA-Z0-9\._\-]+/[a-zA-Z0-9\._\-]+):(.+)""".r
    imageString match {
      case Pattern(r, v) => DockerImage(DockerRepository(r), ReleaseVersion(v))
      case _ => throw new RuntimeException(s"Invalid docker image string: $imageString")
    }
  }
}
