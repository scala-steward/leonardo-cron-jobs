package com.broadinstitute.dsp
package resourceValidator

import cats.effect.Concurrent
import cats.mtl.Ask
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.google2.{DataprocClusterName, InstanceName}
import org.broadinstitute.dsde.workbench.model.TraceId
import org.typelevel.log4cats.Logger

// Implements CheckRunner[F[_], A]
object DeletedRuntimeChecker {
  def impl[F[_]](
    dbReader: DbReader[F],
    deps: RuntimeCheckerDeps[F]
  )(implicit F: Concurrent[F], logger: Logger[F], ev: Ask[F, TraceId]): CheckRunner[F, Runtime] =
    new CheckRunner[F, Runtime] {
      override def appName: String = resourceValidator.appName

      override def configs = CheckRunnerConfigs(s"deleted-runtime", shouldAlert = true)

      override def dependencies: CheckRunnerDeps[F] = deps.checkRunnerDeps

      override def resourceToScan: fs2.Stream[F, Runtime] = dbReader.getDeletedRuntimes

      override def checkResource(runtime: Runtime, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] =
        runtime match {
          case x: Runtime.Dataproc =>
            checkDataprocCluster(x, isDryRun)
          case x: Runtime.Gce =>
            checkGceRuntime(x, isDryRun)
          case x: Runtime.AzureVM =>
            // TODO: IA-3289 Implement check Azure VM
            logger.info(s"Azure VM is not supported yet").as(None)
        }

      private def checkDataprocCluster(runtime: Runtime.Dataproc, isDryRun: Boolean)(implicit
        ev: Ask[F, TraceId]
      ): F[Option[Runtime]] =
        for {
          clusterOpt <- deps.dataprocService
            .getCluster(runtime.googleProject, runtime.region, DataprocClusterName(runtime.runtimeName))
          r <- clusterOpt.flatTraverse { _ =>
            for {
              isBillingEnabled <- deps.billingService.isBillingEnabled(runtime.googleProject)
              r <-
                if (isDryRun)
                  logger
                    .warn(
                      s"$runtime still exists in Google. It needs to be deleted. isBillingEnabled: $isBillingEnabled. Project: ${runtime.googleProject}"
                    )
                    .as(runtime.some)
                else
                  isBillingEnabled match {
                    case true =>
                      logger.warn(
                        s"$runtime still exists in Google and billing is enabled. Going to delete it."
                      ) >> deps.dataprocService
                        .deleteCluster(runtime.googleProject, runtime.region, DataprocClusterName(runtime.runtimeName))
                        .as(runtime.some)
                    case false =>
                      logger
                        .info(
                          s"$runtime has been reported from getCluster, but billing is disabled so cannot perform any actions."
                        )
                        .as(none[Runtime.Dataproc])
                  }
            } yield r
          }
        } yield r

      private def checkGceRuntime(runtime: Runtime.Gce, isDryRun: Boolean): F[Option[Runtime]] =
        for {
          runtimeOpt <- deps.computeService
            .getInstance(runtime.googleProject, runtime.zone, InstanceName(runtime.runtimeName))
          _ <- runtimeOpt.traverse_ { _ =>
            if (isDryRun)
              logger.warn(s"${runtime} still exists in Google. It needs to be deleted")
            else
              logger.warn(s"${runtime} still exists in Google. Going to delete") >>
                deps.computeService
                  .deleteInstance(runtime.googleProject, runtime.zone, InstanceName(runtime.runtimeName))
                  .void
          }
        } yield runtimeOpt.fold(none[Runtime])(_ => Some(runtime))
    }
}
