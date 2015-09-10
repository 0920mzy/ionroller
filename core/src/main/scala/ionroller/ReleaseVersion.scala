package ionroller

import play.api.libs.json._

final case class ReleaseVersion(tag: String)

object ReleaseVersion {
  implicit object JsonFormat extends Format[ReleaseVersion] {
    def reads(json: JsValue): JsResult[ReleaseVersion] =
      json.validate[String] map ReleaseVersion.apply

    def writes(o: ReleaseVersion): JsValue =
      JsString(o.tag)
  }

}

