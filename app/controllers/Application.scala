package controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.util.PasswordHasher

import scala.concurrent.Future
import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.CookieAuthenticator
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import play.api._
import play.api.mvc._
import play.api.i18n.{I18nSupport, Messages, MessagesApi}
import models._
import play.api.data.Form
import play.api.data.Forms._
import services.UserService
import daos.{CrewDao, OauthClientDao}
import play.api.libs.json.{JsPath, JsValue, Json, Reads}
import play.api.libs.ws._
import utils.{WithAlternativeRoles, WithRole}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global

class Application @Inject() (
  oauth2ClientDao: OauthClientDao,
  userService: UserService,
  crewDao: CrewDao,
  ws: WSClient,
  passwordHasher: PasswordHasher,
  val messagesApi: MessagesApi,
  val env:Environment[User,CookieAuthenticator],
  configuration: Configuration,
  socialProviderRegistry: SocialProviderRegistry) extends Silhouette[User,CookieAuthenticator] {

  def index = UserAwareAction.async { implicit request =>
    Future.successful(Ok(views.html.index(request.identity, request.authenticator.map(_.loginInfo))))
  }

  def profile = SecuredAction.async { implicit request =>
    crewDao.list.map(l =>
      Ok(views.html.profile(request.identity, request.authenticator.loginInfo, socialProviderRegistry, CrewForms.geoForm, l.toSet, PillarForms.define))
    )
  }

  def updateCrew = SecuredAction.async { implicit request =>
    CrewForms.geoForm.bindFromRequest.fold(
      bogusForm => crewDao.list.map(l => BadRequest(views.html.profile(request.identity, request.authenticator.loginInfo, socialProviderRegistry, bogusForm, l.toSet, PillarForms.define))),
      crewData => {
        request.identity.profileFor(request.authenticator.loginInfo) match {
          case Some(profile) => {
            crewDao.find(crewData.crewName).map( _ match {
              case Some(crew) => {
                val updatedSupporter = profile.supporter.copy(crew = Some(CrewSupporter(crew, crewData.active)))
                val updatedProfile = profile.copy(supporter = updatedSupporter)
                userService.update(request.identity.updateProfile(updatedProfile))
                Redirect("/profile")
              }
              case None => Redirect("/profile")
            })

          }
          case None =>  Future.successful(InternalServerError(Messages("crew.update.noProfileForLogin")))
        }
      }
    )
  }

  def updatePillar = SecuredAction.async { implicit request =>
    PillarForms.define.bindFromRequest.fold(
      bogusForm => crewDao.list.map(l => BadRequest(views.html.profile(request.identity, request.authenticator.loginInfo, socialProviderRegistry, CrewForms.geoForm, l.toSet, bogusForm))),
      pillarData => request.identity.profileFor(request.authenticator.loginInfo) match {
        case Some(profile) => {
          val pillars = pillarData.toMap.foldLeft[Set[Pillar]](Set())((pillars, data) => data._2 match {
            case true => pillars + Pillar(data._1)
            case false => pillars
          })
          val supporter = profile.supporter.copy(pillars = pillars)
          val updatedProfile = profile.copy(supporter = supporter)
          userService.update(request.identity.updateProfile(updatedProfile)).map((u) => Redirect("/"))
        }
        case _ => Future.successful(Redirect("/"))
      }
    )
  }

  def initCrews = Action.async { request =>
    configuration.getConfigList("crews").map(_.toList.map(c =>
      crewDao.find(c.getString("name").get).map(_ match {
        case Some(crew) => crew
        case _ => crewDao.save(Crew(c.getString("name").get, c.getString("country").get, c.getStringList("cities").get.toSet))
      })
    ))
    Future.successful(Redirect("/"))
  }

  def initUsers(number: Int, specialRoleUsers : Int = 1) = SecuredAction(WithRole(RoleAdmin)).async { request => {
    val wsRequest = ws.url("https://randomuser.me/api/")
      .withHeaders("Accept" -> "application/json")
      .withRequestTimeout(10000)
      .withQueryString("nat" -> "de", "results" -> number.toString, "noinfo" -> "noinfo")

    import play.api.libs.functional.syntax._

    case class UserWsResults(results : List[JsValue])
    implicit val resultsReader : Reads[UserWsResults] = (
      (JsPath \ "results").read[List[JsValue]]
    ).map(UserWsResults(_))

    implicit val pw : PasswordHasher = passwordHasher
    crewDao.ws.list(Json.obj(),150,Json.obj()).flatMap((crews) =>
      wsRequest.get().flatMap((response) => {
        val users = (response.json.as[UserWsResults]).results.zipWithIndex.foldLeft[List[DummyUser]](List())(
          (l, userJsonIndex) => l :+ DummyUser(
            userJsonIndex._1,
            (userJsonIndex._2 <= specialRoleUsers)
          )
        ).map((dummy) => {
          // add crew and extract the user
          val crewSupporter = DummyUser.setRandomCrew(dummy, crews.toSet).user
          // save user in database and return a future of the saved user
          userService.save(crewSupporter)
        })
        Future.sequence(users).map((list) => Ok(Json.toJson(list)))
      })
    )
  }}

  def registration = SecuredAction(WithAlternativeRoles(RoleAdmin, RoleEmployee)) { implicit request =>
    Ok(views.html.oauth2.register(request.identity, request.authenticator.loginInfo, socialProviderRegistry, OAuth2ClientForms.register))
  }

  def registerOAuth2Client = SecuredAction(WithAlternativeRoles(RoleAdmin, RoleEmployee)).async { implicit request =>
    OAuth2ClientForms.register.bindFromRequest.fold(
      bogusForm => Future.successful(BadRequest(views.html.oauth2.register(request.identity, request.authenticator.loginInfo, socialProviderRegistry, bogusForm))),
      registerData => {
        oauth2ClientDao.save(registerData.toClient)
        Future.successful(Redirect("/"))
      }
    )
  }
}

object OAuth2ClientForms {
  case class OAuth2ClientRegister(id:String, secret: String, redirectUri: Option[String], codeRedirectUri: String, grantTypes: Set[String]) {
    def toClient = OauthClient(id, secret, redirectUri, codeRedirectUri, grantTypes)
  }
  def register = Form(mapping(
    "id" -> nonEmptyText,
    "secret" -> nonEmptyText,
    "redirectUri" -> optional(text),
    "codeRedirectUri" -> nonEmptyText,
    "grantTypes" -> nonEmptyText
  )
  ((id, secret, redirectUri, codeRedirectUri, grantTypes) => OAuth2ClientRegister(id, secret, redirectUri, codeRedirectUri, grantTypes.split(",").toSet))
  ((rawData) => Some((rawData.id, rawData.secret, rawData.redirectUri, rawData.codeRedirectUri, rawData.grantTypes.mkString(",")))))
}
