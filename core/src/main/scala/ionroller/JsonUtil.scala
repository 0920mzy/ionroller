package ionroller

import java.text.SimpleDateFormat

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import play.api.libs.json.Json

object JsonUtil {

  object Implicits {
    implicit class Unmarshallable(unMarshallMe: String) {
      def toMap: Map[String, Any] = JsonUtil.toMap(unMarshallMe)

      def toMapOf[V]()(implicit m: Manifest[V]): Map[String, V] = JsonUtil.toMap[V](unMarshallMe)

      def fromJson[T]()(implicit m: Manifest[T]): T = JsonUtil.fromJson[T](unMarshallMe)
    }

    implicit class Marshallable[T](marshallMe: T) {
      def toJson: String = JsonUtil.toJson(marshallMe)

      def toJsonValue = Json.parse(JsonUtil.toJson(marshallMe))
    }
  }

  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  mapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS"))

  def toJson(value: Map[Symbol, Any]): String = {
    toJson(value map { case (k, v) => k.name -> v })
  }

  def toJson(value: Any): String = {
    mapper.writeValueAsString(value)
  }

  def toMap[V](json: String)(implicit m: Manifest[V]) = fromJson[Map[String, V]](json)

  def fromJson[T](json: String)(implicit m: Manifest[T]): T = {
    mapper.readValue[T](json)
  }
}
