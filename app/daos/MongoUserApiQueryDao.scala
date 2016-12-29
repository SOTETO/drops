package daos

import java.util.UUID

import api._
import models.{Pillar, Role, User}
import play.api.libs.json._
import reactivemongo.bson.BSONObjectID

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

trait UserApiQueryDao[A] {
  def filter : Future[(A, Map[String, Int])]
  def filterByPage(lastId: Option[UUID], countsPerPage: Int) : Future[(A, Map[String, Int])]
  def filterByGroups(groups: Set[Group]) : A
  def filterBySearch(keyword: String, fields: Set[String]): A

  def getSortCriteria : A
}

case class MongoUserApiQueryDao(query: ApiQuery, userDao: UserDao) extends UserApiQueryDao[JsObject] {

  def filter = query.filterBy match {
    case Some(filter) => {
      val q = (filter.groups match {
        case Some(groups) => filterByGroups(groups)
        case _ => Json.obj()
      }) ++ (filter.search match {
        case Some(search) => filterBySearch(search.keyword, search.fields)
        case None => Json.obj()
      })
      filter.page match {
        case Some(page) => {
          filterByPage(page.lastId, page.countsPerPage).map((pageQ) => (pageQ._1 ++ q, pageQ._2))
        }
        case None => Future.successful((q, Map[String, Int]()))
      }
    }
    case None => Future.successful((Json.obj(), Map[String, Int]()))
  }

  def filterByGroups(groups: Set[Group]) = groups.foldLeft(Json.obj())((json, group) => json ++ filterByGroup(group))

  override def filterBySearch(keyword: String, fields: Set[String]): JsObject = Json.obj("$or" ->
    fields.foldLeft(Json.arr())((conditions, field) =>
      conditions :+ Json.obj(field -> Json.obj("$regex" -> (".*" + keyword + ".*")))
    )
  )

  override def filterByPage(lastId: Option[UUID], countsPerPage: Int): Future[(JsObject, Map[String, Int])] =
    lastId.map((id) => userDao.getObjectId(id).map(_ match {
      case Some(userObjId) => Json.obj("_id" -> Json.obj("$gt" -> Json.toJson(userObjId._id)))
      case None => Json.obj()
    }).map(
      (queryExtension) => (queryExtension, Map("limit" -> countsPerPage))
    )).getOrElse(Future.successful((Json.obj(), Map("limit" -> countsPerPage))))

  private def filterByGroup(group: Group) = group.area match {
    case RoleArea => Json.obj(
      "roles.role" -> group.groupName
    )
    case PillarArea => Json.obj(
      "profiles.supporter.pillars.pillar" -> group.groupName
    )
  }

  override def getSortCriteria: JsObject = query.sortBy match {
    case Some(sortation) => sortation.foldLeft[JsObject](Json.obj())(
      (res, field) => res ++ Json.obj(field.field -> (field.dir match {
        case Asc => 1
        case Desc => -1
      }))
    )
    case None => Json.obj()
  }
}
