package core.generator

import core._
import Text._

object Play2Models {
  def apply(json: String): String = {
    apply(ServiceDescription(json))
  }

  def apply(sd: ServiceDescription): String = {
    apply(new ScalaServiceDescription(sd))
  }

  def apply(ssd: ScalaServiceDescription): String = {
    val caseClasses = ScalaCaseClasses(ssd)
    val prefix = underscoreToInitCap(ssd.name)
    val enumJson: String = ssd.serviceDescription.models.flatMap { model => Play2Enums.buildJson(prefix, model) }.mkString("\n\n")
    val modelJson: String = ssd.models.map { model =>
s"""implicit def jsonReads${prefix}${model.name}: play.api.libs.json.Reads[${model.name}] =
${Play2Util.jsonReads(model).indent(2)}

implicit def jsonWrites${prefix}${model.name}: play.api.libs.json.Writes[${model.name}] =
${Play2Util.jsonWrites(model).indent(2)}"""
    }.mkString("\n\n")

s"""$caseClasses

package ${ssd.packageName}.models {
  package object json {
    import play.api.libs.json._
    import play.api.libs.functional.syntax._

    private implicit val jsonReadsUUID = __.read[String].map(java.util.UUID.fromString)

    private implicit val jsonWritesUUID = new Writes[java.util.UUID] {
      def writes(x: java.util.UUID) = JsString(x.toString)
    }

    private implicit val jsonReadsJodaDateTime = __.read[String].map { str =>
      import org.joda.time.format.ISODateTimeFormat.dateTimeParser
      dateTimeParser.parseDateTime(str)
    }

    private implicit val jsonWritesJodaDateTime = new Writes[org.joda.time.DateTime] {
      def writes(x: org.joda.time.DateTime) = {
        import org.joda.time.format.ISODateTimeFormat.dateTime
        val str = dateTime.print(x)
        JsString(str)
      }
    }

${enumJson.indent(4)}

${modelJson.indent(4)}
  }
}"""
  }
}
