package com.broadinstitute.dsp

import ca.mrvisser.sealerate
import io.circe.Encoder
import org.broadinstitute.dsde.workbench.google2.{DiskName, Location, ZoneName}
import org.broadinstitute.dsde.workbench.google2.GKEModels.{
  KubernetesClusterId,
  KubernetesClusterName,
  NodepoolId,
  NodepoolName
}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import org.broadinstitute.dsde.workbench.google2.JsonCodec.{googleProjectEncoder, traceIdEncoder}

sealed abstract class CloudService extends Product with Serializable {
  def asString: String
}
object CloudService {
  final case object Gce extends CloudService {
    override def asString: String = "GCE"
  }
  final case object Dataproc extends CloudService {
    override def asString: String = "DATAPROC"
  }
  final case object AzureVM extends CloudService {
    override def asString: String = "AZURE_VM"
  }
}
final case class Disk(id: Long,
                      cloudContext: CloudContext,
                      diskName: DiskName,
                      zone: ZoneName,
                      formattedBy: Option[String]
) {
  override def toString: String = s"${id}/${cloudContext.asStringWithProvider},${diskName.value},${zone.value}"
}

//init buckets are different than staging buckets because we store them with gs://[GcsBucketName]/
final case class InitBucketName(value: String) extends AnyVal {
  def asGcsBucketName: GcsBucketName = GcsBucketName(value.trim.subSequence(5, value.trim.length - 1).toString)
}

object InitBucketName {
  def withValidation(value: String): Either[String, InitBucketName] =
    if (value.startsWith("gs://") && value.endsWith("/")) Right(InitBucketName(value))
    else Left("init bucket names must start with 'gs://' and end with '/'")
}

final case class InitBucketToRemove(googleProject: GoogleProject, bucket: Option[InitBucketName]) {
  override def toString: String = s"${googleProject.value},${bucket.getOrElse("null")}"
}

final case class K8sClusterToScan(id: Long, kubernetesClusterId: KubernetesClusterId)
final case class NodepoolToScan(id: Long, nodepoolId: NodepoolId)

final case class KubernetesClusterToRemove(id: Long, googleProject: GoogleProject)

final case class KubernetesCluster(clusterName: KubernetesClusterName,
                                   googleProject: GoogleProject,
                                   location: Location
) {
  override def toString: String = s"${googleProject}/${clusterName}"
}

final case class Nodepool(nodepoolId: Long,
                          nodepoolName: NodepoolName,
                          clusterName: KubernetesClusterName,
                          googleProject: GoogleProject,
                          location: Location
) {
  override def toString: String = s"$googleProject/${nodepoolName.value}"
}

final case class DeleteNodepoolMeesage(nodepoolId: Long, googleProject: GoogleProject, traceId: Option[TraceId]) {
  val messageType: String = "deleteNodepool"
}

sealed abstract class RemovableNodepoolStatus extends Product with Serializable {
  def asString: String
}
object RemovableNodepoolStatus {
  final case object StatusUnspecified extends RemovableNodepoolStatus {
    override def asString: String = "STATUS_UNSPECIFIED"
  }
  final case object Running extends RemovableNodepoolStatus {
    override def asString: String = "RUNNING"
  }
  final case object Reconciling extends RemovableNodepoolStatus {
    override def asString: String = "RECONCILING"
  }
  final case object Error extends RemovableNodepoolStatus {
    override def asString: String = "ERROR"
  }
  final case object RunningWithError extends RemovableNodepoolStatus {
    override def asString: String = "RUNNING_WITH_ERROR"
  }
  val removableStatuses = sealerate.values[RemovableNodepoolStatus]
  val stringToStatus: Map[String, RemovableNodepoolStatus] =
    sealerate.collect[RemovableNodepoolStatus].map(a => (a.asString, a)).toMap
}

sealed abstract class CloudProvider extends Product with Serializable {
  def asString: String
}
object CloudProvider {
  final case object Gcp extends CloudProvider {
    override val asString = "GCP"
  }
  final case object Azure extends CloudProvider {
    override val asString = "AZURE"
  }

  val stringToCloudProvider = sealerate.values[CloudProvider].map(p => (p.asString, p)).toMap
}

sealed abstract class CloudContext extends Product with Serializable {
  def asString: String
  def asStringWithProvider: String
  def cloudProvider: CloudProvider
}
object CloudContext {
  final case class Gcp(value: GoogleProject) extends CloudContext {
    override val asString = value.value
    override val asStringWithProvider = s"Gcp/${value.value}"
    override def cloudProvider: CloudProvider = CloudProvider.Gcp
  }
  final case class Azure(value: String) extends CloudContext {
    override val asString = value
    override val asStringWithProvider = s"Azure/${value}"
    override def cloudProvider: CloudProvider = CloudProvider.Azure
  }
}

object JsonCodec {
  implicit val deleteNodepoolMessageEncoder: Encoder[DeleteNodepoolMeesage] =
    Encoder.forProduct4("messageType", "nodepoolId", "googleProject", "traceId")(x =>
      (x.messageType, x.nodepoolId, x.googleProject, x.traceId)
    )

  implicit val serviceDataEncoder: Encoder[ServiceData] = Encoder.forProduct2(
    "service",
    "version"
  )(x => (x.name, x.version))
}

final case class ServiceData(version: Option[String]) {
  val name = "leonardo-cron-jobs"
}
