package controllers

import java.util.UUID
import lib.Validation
import core.ServiceDescription
import core.generator.Target

import play.api.mvc._
import play.api.libs.json._

import db.{ Version, VersionDao }

object Code extends Controller {

  case class Code(
    target: String,
    source: String
  )

  object Code {

    implicit val codeWrites = Json.writes[Code]

  }

  def getByOrgKeyAndServiceKeyAndVersionAndTargetName(orgKey: String, serviceKey: String, version: String, targetName: String) = Authenticated { request =>
    Target.findByKey(targetName) match {
      case None => {
        Conflict(Json.toJson(Validation.error(s"Invalid target[$targetName]. Must be one of: ${Target.Implemented.mkString(" ")}")))
      }
      case Some(target: Target) => {
        VersionDao.findVersion(request.user, orgKey, serviceKey, version) match {

          case None => NotFound

          case Some(v: Version) => {
            val code = Code(
              target = targetName,
              source = Target.generate(target, ServiceDescription(v.json))
            )
            Ok(Json.toJson(code))
          }
        }
      }
    }
  }

}
