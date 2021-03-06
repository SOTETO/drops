package daos

import java.util.UUID

import daos.schema.UserTokenTableDef

import scala.concurrent.Future
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import models.UserToken
import models.database.UserTokenDB
import play.api.Play
import play.api.db.slick.DatabaseConfigProvider
import slick.driver.JdbcProfile
import slick.lifted.TableQuery
import slick.driver.MySQLDriver.api._

trait UserTokenDao {
  def find(id:UUID):Future[Option[UserToken]]
  def save(token:UserToken):Future[UserToken]
  def remove(id:UUID):Future[Unit]
}

class MariadbUserTokenDao extends UserTokenDao{
  val dbConfig = DatabaseConfigProvider.get[JdbcProfile](Play.current)

  val userTokens = TableQuery[UserTokenTableDef]

  override def find(id: UUID): Future[Option[UserToken]]= dbConfig.db.run(userTokens.filter(uT => uT.id === id).result)
    .map(r => r.headOption.map(uToken => uToken.toUserToken))

  override def save(token: UserToken):Future[UserToken] = {
    val tokenDB : UserTokenDB = UserTokenDB(token)
    dbConfig.db.run((userTokens += tokenDB).andThen(DBIO.successful(tokenDB))).flatMap(
      newDBEntry => find(newDBEntry.id).map(_.get)
    )

  }

  override def remove(id: UUID):Future[Unit] = {
    dbConfig.db.run(userTokens.filter(uT => uT.id === id).delete).map(_ => ())
  }

}
