package lila.forum

import scala.concurrent.duration.Duration

import spray.caching.{ LruCache, Cache }

import lila.security.{ Permission, Granter => MasterGranter }
import lila.user.User

private[forum] final class Recent(
  postApi: PostApi, 
  ttl: Duration,
  nb: Int) {

  private type GetTeams = String => Fu[List[String]]

  def apply(user: Option[User], getTeams: GetTeams): Fu[List[PostLiteView]] =
    userCacheKey(user, getTeams) flatMap { key =>
      cache(key)(fetch(key))
    }

  def team(teamId: String): Fu[List[PostLiteView]] =
    cache(teamSlug(teamId))(fetch(teamSlug(teamId)))

  def invalidate: Funit = fuccess(cache.clear)

  import makeTimeout.large

  private def userCacheKey(user: Option[User], getTeams: GetTeams): Fu[String] =
    user.map(_.id) ?? getTeams map { teams =>
      user.fold("en")(_.langs.mkString(",")) :: {
      (user.??(_.troll) ?? List("[troll]")) :::
        (user ?? MasterGranter(Permission.StaffForum)).fold(staffCategIds, publicCategIds) :::
        (teams map teamSlug)
      }
    } map (_ mkString ";")

  private lazy val publicCategIds =
    CategRepo.withTeams(Nil).await.map(_.slug) filterNot ("staff" ==)

  private lazy val staffCategIds = "staff" :: publicCategIds

  private val cache: Cache[List[PostLiteView]] = LruCache(timeToLive = ttl)

  private def parseLangs(langStr: String) = langStr.split(",").toList

  private def fetch(key: String): Fu[List[PostLiteView]] =
    (key.split(";").toList match {
      case langs :: "[troll]" :: categs => PostRepoTroll.recentInCategs(nb)(categs, parseLangs(langs))
      case langs :: categs              => PostRepo.recentInCategs(nb)(categs, parseLangs(langs))
    }) flatMap postApi.liteViews
}
