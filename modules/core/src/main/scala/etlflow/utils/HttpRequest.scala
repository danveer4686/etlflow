package etlflow.utils

import etlflow.log.ApplicationLogger
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.httpclient.zio._
import sttp.client3.logging.LogLevel
import sttp.client3.{asStringAlways, basicRequest, _}
import zio.Task
import scala.concurrent.duration.{Duration, _}
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import sttp.model.MediaType

object HttpRequest extends ApplicationLogger {

  sealed trait HttpMethod

  object HttpMethod {
    case object GET extends HttpMethod
    case object POST extends HttpMethod
    case object PUT extends HttpMethod
  }

  private def options(ms: Int) = SttpBackendOptions.connectionTimeout(ms.millisecond)

  private def logBackend(backend: SttpBackend[Task,ZioStreams with capabilities.WebSockets]): SttpBackend[Task, ZioStreams with capabilities.WebSockets] =
    Slf4jLoggingBackend(
      backend,
      beforeCurlInsteadOfShow = true,
      logRequestBody = true,
      logResponseBody = true,
      beforeRequestSendLogLevel = LogLevel.Info,
      responseLogLevel = _ => LogLevel.Info
    )

  private def logAndParseResponse(req: RequestT[Identity, String, Any], log: Boolean, connection_timeout: Int): Task[Response[String]] = {
    HttpClientZioBackend
      .managed(options(connection_timeout))
      .use(backend => if (log) req.send(logBackend(backend)) else req.send(backend))
      .map { res =>
        logger.info("#" * 50)
        if (res.code.code == 204 || res.code.code == 200 || res.code.code == 201) {
          res
        }
        else {
          logger.error(s"Failed with Response code: ${res.code}")
          throw new RuntimeException(s"Failed with Response code: ${res.code}")
        }
      }
  }

  implicit private val stringAsJson: BodySerializer[String] = { p: String =>
    StringBody(p, "UTF-8", MediaType.ApplicationJson)
  }

  def execute(method: HttpMethod, url: String, params: Either[String, Map[String,String]], headers: Map[String, String], log: Boolean, connection_timeout: Int, read_timeout: Int): Task[Response[String]] = {
    val hdrs = headers.filterKeys(key => key.toLowerCase != "content-type")

    val request: RequestT[Empty, String, Any] = method match {
      case HttpMethod.GET =>
        basicRequest
          .readTimeout(Duration(read_timeout, MILLISECONDS))
          .headers(headers)
          .response(asStringAlways)
      case HttpMethod.POST | HttpMethod.PUT => params match {
        case Left(str) =>
          basicRequest
            .body(stringAsJson(str)) // Always encoded as JSON
            .readTimeout(Duration(read_timeout, MILLISECONDS))
            .headers(hdrs)
            .response(asStringAlways)
        case Right(map) =>
          basicRequest
            .body(map) // Always encoded as FORM
            .readTimeout(Duration(read_timeout, MILLISECONDS))
            .headers(hdrs)
            .response(asStringAlways)
      }
    }

    method match {
      case HttpMethod.GET => params match {
        case Left(_) => Task.fail(new RuntimeException("params for get request as Left(..) is not supported"))
        case Right(map) => logAndParseResponse(request.get(uri"$url?$map"),log,connection_timeout)
      }
      case HttpMethod.POST => logAndParseResponse(request.post(uri"$url"),log,connection_timeout)
      case HttpMethod.PUT => logAndParseResponse(request.put(uri"$url"),log,connection_timeout)
    }
  }
}