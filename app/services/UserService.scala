package services

import java.util.UUID
import javax.inject._

import scala.concurrent.Future
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfile
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.modules.reactivemongo.ReactiveMongoApi
import play.modules.reactivemongo.json.collection.JSONCollection
import daos.{AccessRightDao, TaskDao, UserDao}
import models.AccessRight
import models.{Profile, ProfileImage, User}
import utils.Nats

class UserService @Inject() (userDao:UserDao, taskDao: TaskDao, accessRightDao: AccessRightDao, nats: Nats) extends IdentityService[User] {
  def retrieve(loginInfo:LoginInfo):Future[Option[User]] = userDao.find(loginInfo)
  def save(user:User) = userDao.save(user)
  def saveImage(profile: Profile, avatar: ProfileImage) = userDao.saveProfileImage(profile, avatar)
  def update(updatedUser: User) = {
    nats.publishUpdate("USER", updatedUser.id) 
    userDao.replace(updatedUser)
  }
  def find(id:UUID) = userDao.find(id)
  def confirm(loginInfo:LoginInfo) = userDao.confirm(loginInfo)
  def link(user:User, socialProfile:CommonSocialProfile) = {
    val profile = Profile(socialProfile)
    if (user.profiles.exists(_.loginInfo == profile.loginInfo)) Future.successful(user) else userDao.link(user, profile)
  }

  def save(socialProfile:CommonSocialProfile) = {
    val profile = Profile(socialProfile)
    userDao.find(profile.loginInfo).flatMap {
      case None => userDao.save(User(UUID.randomUUID(), List(profile), System.currentTimeMillis(), System.currentTimeMillis()))
      case Some(user) => userDao.update(profile)
    }
  }

  def list = userDao.list
  def listOfStubs = userDao.listOfStubs

  def accessRights(userId: UUID) : Future[Seq[AccessRight]] = {
    taskDao.idsForUser(userId).flatMap(taskIds => accessRightDao.forTaskList(taskIds))
  }
  //todo
  def delete(userId: UUID) = {
    nats.publishDelete("USER", userId)
  }

  def getProfile(email: String) = userDao.getProfile(email)
  def profileListByRole(id: UUID, role: String) = userDao.profileListByRole(id, role)

//  def accessRightsForService(userId : UUID, service: String) : Future[Seq[AccessRight]] = {
//    taskDao.idsForUser(userId).flatMap(taskIds => accessRightDao.forTaskListAndService(taskIds, service))
//  }
}
