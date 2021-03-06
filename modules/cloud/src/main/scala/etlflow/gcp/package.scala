package etlflow

import com.google.api.gax.paging.Page
import com.google.cloud.bigquery.{FieldValueList, JobInfo, Schema}
import com.google.cloud.storage.Blob
import com.google.cloud.storage.Storage.BlobListOption
import org.slf4j.{Logger, LoggerFactory}
import zio.{Has, ZIO}

package object gcp {
  val gcp_logger: Logger = LoggerFactory.getLogger(getClass.getName)

  type GCSService = Has[GCSService.Service]
  sealed trait BQInputType extends Serializable
  object BQInputType {
    final case class CSV(delimiter: String = ",", header_present: Boolean = true, parse_mode: String = "FAILFAST", quotechar: String = "\"") extends BQInputType {
      override def toString: String = s"CSV with delimiter => $delimiter header_present => $header_present parse_mode => $parse_mode"
    }
    final case class JSON(multi_line: Boolean = false) extends BQInputType {
      override def toString: String = s"Json with multiline  => $multi_line"
    }
    final case object BQ extends BQInputType
    final case object PARQUET extends BQInputType
    final case object ORC extends BQInputType
  }
  sealed trait FSType
  object FSType {
    case object LOCAL extends FSType
    case object GCS extends FSType
  }

  object GCSService {
    trait Service {
      def listObjects(bucket: String, options: List[BlobListOption]): ZIO[GCSService, Throwable, Page[Blob]]
      def listObjects(bucket: String, prefix: String): ZIO[GCSService, Throwable, List[Blob]]
      def lookupObject(bucket: String, prefix: String, key: String): ZIO[GCSService, Throwable, Boolean]
      def putObject(bucket: String, key: String, file: String): ZIO[GCSService, Throwable, Blob]
    }
    def putObject(bucket: String, key: String, file: String): ZIO[GCSService, Throwable, Blob] = ZIO.accessM(_.get.putObject(bucket,key,file))
    def lookupObject(bucket: String, prefix: String, key: String): ZIO[GCSService, Throwable, Boolean] = ZIO.accessM(_.get.lookupObject(bucket,prefix,key))
    def listObjects(bucket: String, options: List[BlobListOption]): ZIO[GCSService, Throwable, Page[Blob]] = ZIO.accessM(_.get.listObjects(bucket,options))
    def listObjects(bucket: String, prefix: String): ZIO[GCSService, Throwable, List[Blob]] = ZIO.accessM(_.get.listObjects(bucket,prefix))
  }

  type BQService = Has[BQService.Service]

  object BQService {
    trait Service {
      def executeQuery(query: String): ZIO[BQService, Throwable, Unit]
      def getDataFromBQ(query: String): ZIO[BQService, Throwable, Iterable[FieldValueList]]
      def loadIntoBQFromLocalFile(
         source_locations: Either[String, Seq[(String, String)]], source_format: BQInputType, destination_dataset: String,
         destination_table: String, write_disposition: JobInfo.WriteDisposition, create_disposition: JobInfo.CreateDisposition
         ): ZIO[BQService, Throwable, Unit]
      def loadIntoBQTable(
          source_path: String, source_format: BQInputType, destination_project: Option[String], destination_dataset: String,
          destination_table: String, write_disposition: JobInfo.WriteDisposition, create_disposition: JobInfo.CreateDisposition,
          schema: Option[Schema] = None): ZIO[BQService, Throwable, Map[String, Long]]
      def loadIntoPartitionedBQTable(
          source_paths_partitions: Seq[(String, String)], source_format: BQInputType, destination_project: Option[String],
          destination_dataset: String, destination_table: String, write_disposition: JobInfo.WriteDisposition,
          create_disposition: JobInfo.CreateDisposition, schema: Option[Schema], parallelism: Int
        ): ZIO[BQService, Throwable, Map[String, Long]]
    }
    def getDataFromBQ(query: String): ZIO[BQService, Throwable, Iterable[FieldValueList]] =
      ZIO.accessM(_.get.getDataFromBQ(query))
    def executeQuery(query: String): ZIO[BQService, Throwable, Unit] =
      ZIO.accessM(_.get.executeQuery(query))
    def loadIntoBQFromLocalFile(
       source_locations: Either[String, Seq[(String, String)]], source_format: BQInputType, destination_dataset: String,
       destination_table: String, write_disposition: JobInfo.WriteDisposition, create_disposition: JobInfo.CreateDisposition
     ): ZIO[BQService, Throwable, Unit] =
      ZIO.accessM(_.get.loadIntoBQFromLocalFile(source_locations,source_format,destination_dataset,
        destination_table, write_disposition, create_disposition)
      )
    def loadIntoBQTable(
       source_path: String, source_format: BQInputType, destination_project: Option[String], destination_dataset: String, destination_table: String,
       write_disposition: JobInfo.WriteDisposition, create_disposition: JobInfo.CreateDisposition,
       schema: Option[Schema] = None): ZIO[BQService, Throwable, Map[String, Long]] =
      ZIO.accessM(_.get.loadIntoBQTable(source_path,source_format,destination_project,destination_dataset,
        destination_table,write_disposition,create_disposition,schema)
      )
    def loadIntoPartitionedBQTable(
        source_paths_partitions: Seq[(String, String)], source_format: BQInputType, destination_project: Option[String],
        destination_dataset: String, destination_table: String, write_disposition: JobInfo.WriteDisposition,
        create_disposition: JobInfo.CreateDisposition, schema: Option[Schema], parallelism: Int
      ): ZIO[BQService, Throwable, Map[String, Long]] =
      ZIO.accessM(_.get.loadIntoPartitionedBQTable(source_paths_partitions,source_format,destination_project,destination_dataset,
        destination_table,write_disposition,create_disposition,schema,parallelism)
      )
  }

  type DPService = Has[DPService.Service]

  case class DataprocProperties (
    bucket_name: String,
    subnet_uri: Option[String] = None,
    network_tags: List[String] = List.empty,
    service_account: Option[String] = None,
    idle_deletion_duration_sec: Option[Long] = Some(1800L),
    master_machine_type_uri: String = "n1-standard-4",
    worker_machine_type_uri: String = "n1-standard-4",
    image_version: String = "1.5.4-debian10",
    boot_disk_type: String = "pd-ssd",
    master_boot_disk_size_gb: Int = 400,
    worker_boot_disk_size_gb: Int = 200,
    master_num_instance: Int = 1,
    worker_num_instance: Int = 3
  )

  object DPService {
    trait Service {
      def executeSparkJob(name: String, properties: Map[String,String], main_class: String, libs: List[String]): ZIO[DPService, Throwable, Unit]
      def executeHiveJob(query: String): ZIO[DPService, Throwable, Unit]
      def createDataproc(props: DataprocProperties): ZIO[DPService, Throwable, Unit]
      def deleteDataproc(): ZIO[DPService, Throwable, Unit]
    }
    def executeSparkJob(name: String, properties: Map[String,String], main_class: String, libs: List[String]): ZIO[DPService, Throwable, Unit] =
      ZIO.accessM(_.get.executeSparkJob(name, properties, main_class, libs))
    def executeHiveJob(query: String): ZIO[DPService, Throwable, Unit] = ZIO.accessM(_.get.executeHiveJob(query))
    def createDataproc(props: DataprocProperties): ZIO[DPService, Throwable, Unit] = ZIO.accessM(_.get.createDataproc(props))
    def deleteDataproc(): ZIO[DPService, Throwable, Unit] = ZIO.accessM(_.get.deleteDataproc())
  }
}
