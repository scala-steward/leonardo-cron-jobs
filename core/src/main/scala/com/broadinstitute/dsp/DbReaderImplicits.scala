package com.broadinstitute.dsp

import cats.syntax.all._
import doobie.implicits.javasql.TimestampMeta
import doobie.{Get, Meta, Read}
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterName
import org.broadinstitute.dsde.workbench.google2.{DiskName, Location, RegionName, ZoneName}
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}

import java.sql.{SQLDataException, Timestamp}
import java.time.Instant

object DbReaderImplicits {
  implicit val cloudServiceGet: Get[CloudService] = Get[String].temap {
    case "DATAPROC" => CloudService.Dataproc.asRight[String]
    case "GCE"      => CloudService.Gce.asRight[String]
    case "AZURE_VM" => CloudService.AzureVM.asRight[String]
    case x          => s"invalid cloudService value $x".asLeft[CloudService]
  }

  implicit val instantMeta: Meta[Instant] = Meta[Timestamp].imap(_.toInstant)(Timestamp.from)
  implicit val gcsBucketNameGet: Get[GcsBucketName] = Get[String].map(GcsBucketName)
  implicit val initBucketNameGet: Get[InitBucketName] = Get[String].temap(s => InitBucketName.withValidation(s))
  implicit val locationGet: Get[Location] = Get[String].map(Location)
  implicit val kubernetesClusterNameGet: Get[KubernetesClusterName] = Get[String].map(KubernetesClusterName)
  implicit val diskNameMeta: Meta[DiskName] = Meta[String].imap(DiskName)(_.value)
  implicit val zoneNameMeta: Meta[ZoneName] = Meta[String].imap(ZoneName(_))(_.value)
  implicit val googleProjectMeta: Meta[GoogleProject] = Meta[String].imap(GoogleProject)(_.value)
  implicit val cloudProviderMeta: Meta[CloudProvider] = Meta[String].imap(s =>
    CloudProvider.stringToCloudProvider.get(s).getOrElse(throw new SQLDataException(s"Invalid cloud provider ${s}"))
  )(_.asString)
  implicit val k8sClusterNameMeta: Meta[KubernetesClusterName] = Meta[String].imap(KubernetesClusterName)(_.value)
  implicit val locationMeta: Meta[Location] = Meta[String].imap(Location)(_.value)
  implicit val regionNameMeta: Meta[RegionName] = Meta[String].imap(RegionName(_))(_.value)

  implicit val cloudContextRead: Read[CloudContext] = Read[(String, CloudProvider)].map { case (s, cloudProvider) =>
    cloudProvider match {
      case CloudProvider.Azure =>
        CloudContext.Azure(s)
      case CloudProvider.Gcp =>
        CloudContext.Gcp(GoogleProject(s))
    }
  }

  implicit val runtimeRead: Read[Runtime] =
    Read[(Long, String, CloudProvider, String, CloudService, String, Option[ZoneName], Option[RegionName])].map {
      case (id, cloudContextDb, cloudProvider, runtimeName, cloudService, status, zone, region) =>
        cloudProvider match {
          case CloudProvider.Azure =>
            // TODO: IA-3289 correctly implement this case in the pattern match once we support Azure
            Runtime.AzureVM(id, runtimeName, cloudService, status)
          case CloudProvider.Gcp =>
            (zone, region) match {
              case (Some(_), Some(_)) =>
                throw new RuntimeException(
                  s"${cloudService} Runtime ${id} has both zone and region defined. This is impossible. Fix this in DB"
                )
              case (Some(z), None) =>
                if (cloudService == CloudService.Gce)
                  Runtime.Gce(id, GoogleProject(cloudContextDb), runtimeName, cloudService, status, z)
                else
                  throw new RuntimeException(
                    s"Dataproc runtime ${id} has no region defined. This is impossible. Fix this in DB"
                  )
              case (None, Some(r)) =>
                if (cloudService == CloudService.Dataproc)
                  Runtime.Dataproc(id, GoogleProject(cloudContextDb), runtimeName, cloudService, status, r)
                else
                  throw new RuntimeException(
                    s"Gce runtime ${id} has no zone defined. This is impossible. Fix this in DB"
                  )
              case (None, None) =>
                throw new RuntimeException(
                  s"${cloudService} Runtime ${id} has no zone and no region defined. This is impossible. Fix this in DB"
                )
            }
        }
    }
}
