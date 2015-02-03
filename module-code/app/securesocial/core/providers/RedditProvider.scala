package securesocial.core.providers

import org.apache.commons.codec.binary.Base64
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc.Request
import securesocial.core._
import securesocial.core.services.{ CacheService, RoutesService }

import scala.concurrent.Future

/**
 * A Reddit provider
 *
 */
class RedditProvider(routesService: RoutesService,
  cacheService: CacheService,
  client: OAuth2Client)
    extends OAuth2Provider(routesService, client, cacheService) {
  val MeUrl = "https://oauth.reddit.com/api/v1/me"
  val Id = "id"
  val Name = "name"

  override val id = RedditProvider.Reddit

  override protected def getAccessToken[A](code: String)(implicit request: Request[A]): Future[OAuth2Info] = {
    val callbackUrl = routesService.authenticationUrl(id)
    val params = Map(
      OAuth2Constants.GrantType -> Seq(OAuth2Constants.AuthorizationCode),
      OAuth2Constants.Code -> Seq(code),
      OAuth2Constants.RedirectUri -> Seq(callbackUrl)
    ) ++ settings.accessTokenUrlParams.mapValues(Seq(_))

    val creds = s"${settings.clientId}:${settings.clientSecret}"
    val enc = new String(Base64.encodeBase64(creds.getBytes()))
    val basicAuth = s"Basic $enc"

    client.httpService.url(settings.accessTokenUrl).withHeaders("Authorization" -> basicAuth).post(params).map(buildInfo)
      .recover {
        case e =>
          logger.error("[securesocial] error trying to get an access token for provider %s".format(id), e)
          throw new AuthenticationException()
      }
  }

  val userInfoReader = (
    (__ \ Id).read[String] and
    (__ \ Name).read[String]
  ).tupled

  def fillProfile(info: OAuth2Info): Future[BasicProfile] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    client.httpService.url(MeUrl).withHeaders("Authorization" -> s"bearer ${info.accessToken}").get().map { res =>
      userInfoReader.reads(res.json).fold(
        invalid => {
          logger.error("[securesocial] got back " + res.body)
          throw new AuthenticationException()
        },
        me => {
          BasicProfile(id, me._1, None, None, Some(me._2), None, None, authMethod, oAuth2Info = Some(info))
        }
      )
    } recover {
      case e: AuthenticationException => throw e
      case e =>
        logger.error("[securesocial] error retrieving profile information from reddit", e)
        throw new AuthenticationException()
    }
  }
}

object RedditProvider {
  val Reddit = "reddit"
}