package models.database

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.impl.providers.OAuth1Info
import models.{Crew, Profile, Supporter}
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Reads, _}

/**
  * Definition of the database profile model
  * @param id is used as primary key and the identifier on database level
  * @param confirmed
  * @param email
  * @param userId foreign key to define the corresponding user
  */
case class ProfileDB(
                  id: Long,
                  confirmed: Boolean,
                  email: String,
                  userId: Long
                ) {
  def toProfile(loginInfo: LoginInfo, supporter: Supporter, passwordInfo: Option[PasswordInfo], oauthInfo: Option[OAuth1Info]): Profile =
    Profile(loginInfo, confirmed, email, supporter, passwordInfo, oauthInfo)
}
        


object ProfileDB extends ((Long, Boolean , String, Long) => ProfileDB ){
  def apply(tuple: (Long, Boolean, String, Long)): ProfileDB =
    ProfileDB(tuple._1, tuple._2, tuple._3, tuple._4)
  def apply(profile:Profile, userId:Long): ProfileDB =
    ProfileDB(0, profile.confirmed, profile.email.get, userId)
  
  // read a Database sequence and return Seq[Profile]
  def read(entries: Seq[(ProfileDB, SupporterDB, LoginInfoDB, Option[PasswordInfoDB], Option[OAuth1InfoDB], Option[SupporterCrewDB], Option[Crew], Option[AddressDB])]): Seq[Profile] = {
   //group all entries in a sequence and map it to pair. pair._1 is the Profile and pair._2 all entries they can group by profile 
    entries.groupBy(_._1).toSeq.map(pair => {
      // build Supporter by the SupporterDB.read function with (SupporterDB = row._2, SupporterCrewDB = row._6, Option[Crew] = row._7, Option[AddressDB] = row._8)
      val supporter: Seq[Supporter] = SupporterDB.read(pair._2.map(row => (row._2, row._6, row._7, row._8)))
      //create loginInfo passwordInfo and oauthInfo from pair._2
      pair._2.headOption.flatMap(head => {
        val loginInfo : LoginInfo = head._3.toLoginInfo
        val passwordInfo : Option[PasswordInfo] = head._4.map(_.toPasswordInfo)
        val oauth1Info : Option[OAuth1Info] = head._5.map(_.toOAuth1Info)

        supporter.headOption.map(supporti => pair._1.toProfile(loginInfo, supporti, passwordInfo, oauth1Info))
      })

    }).filter(_.isDefined).map(_.get)
  }

  implicit val profileWrites : OWrites[ProfileDB] = (
    (JsPath \ "id").write[Long] and
      (JsPath \ "confirmed").write[Boolean] and
      (JsPath \ "email").write[String] and
      (JsPath \ "userId").write[Long]
    )(unlift(ProfileDB.unapply))

  implicit val profileReads : Reads[ProfileDB] = (
    (JsPath \ "id").readNullable[Long] and
      (JsPath \ "confirmed").read[Boolean] and
      (JsPath \ "email").read[String] and
      (JsPath \ "userId").read[Long]
    ).tupled.map((profile) => if(profile._1.isEmpty)
    ProfileDB(0, profile._2, profile._3, profile._4)
  else ProfileDB(profile._1.get, profile._2, profile._3, profile._4))
}
