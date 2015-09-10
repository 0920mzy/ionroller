package ionroller

import play.api.libs.json._

final case class DockerRepository(account: String, name: String) {
  override def toString =
    s"$account/$name"
}

object DockerRepository {
  def apply(str: String): DockerRepository = {
    val Pattern = """((?:[a-zA-Z0-9\._\-\/:]+/)?[a-zA-Z0-9\._\-]+)/([a-zA-Z0-9\._\-]+)""".r
    str match {
      case Pattern(a, n) => DockerRepository(a, n)
      case _ => throw new RuntimeException(s"Invalid docker repository string: $str")
    }

  }

  implicit object JsonFormat extends Format[DockerRepository] {
    def reads(json: JsValue): JsResult[DockerRepository] =
      json.validate[String] map DockerRepository.apply

    def writes(o: DockerRepository): JsValue =
      JsString(o.toString)
  }

}
