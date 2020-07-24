package etlflow.etljobs

import etlflow.LoggerResource
import etlflow.log.EtlLogger.JobLogger
import etlflow.log.{DbLogManager, SlackLogManager}
import etlflow.utils.{UtilityFunctions => UF}
import zio.internal.Platform
import zio.{UIO, ZIO, Managed}

trait GenericEtlJob extends EtlJob {

  val job: ZIO[LoggerResource, Throwable, Unit]
  def printJobInfo(level: String = "info"): Unit = {}
  def getJobInfo(level: String = "info"): List[(String,Map[String,String])] = List.empty

  final def execute(): ZIO[Any, Throwable, Unit] = {
    (for {
      job_status_ref  <- job_status.toManaged_
      resource        <- logger_resource
      log             = JobLogger.live(resource)
      job_start_time  <- UIO.succeed(UF.getCurrentTimestamp).toManaged_
      _               <- (job_status_ref.set("started") *> log.logInit(job_start_time)).toManaged_
      _               <- job.provide(resource).foldM(
                            ex => job_status_ref.set("failed") *> log.logError(job_start_time,ex),
                            _  => job_status_ref.set("success") *> log.logSuccess(job_start_time)
                          ).toManaged_
    } yield ()).use_(ZIO.unit)
  }

  private[etljobs] lazy val logger_resource: Managed[Throwable, LoggerResource] = for {
    db         <- DbLogManager.createOptionDbTransactorManagedGP(
                      global_properties, Platform.default.executor.asEC,
                      job_name + "-Pool", job_name, job_properties
                  )
    slack      <- SlackLogManager.createSlackLogger(job_name, job_properties, global_properties).toManaged_
  } yield LoggerResource(db,slack)
}