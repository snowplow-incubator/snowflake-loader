package com.snowplowanalytics.snowplow.snowflake.processing

import cats.effect.Sync
import cats.implicits._
import com.snowplowanalytics.snowplow.snowflake.Config
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._
import retry.implicits.retrySyntaxError

object SnowflakeRetrying {

  private implicit def logger[F[_]: Sync] = Slf4jLogger.getLogger[F]

  def retryIndefinitely[F[_]: Sync: Sleep, A](snowflakeHealth: SnowflakeHealth[F], config: Config.Retries)(action: F[A]): F[A] =
    retryUntilSuccessful(snowflakeHealth, config, action) <*
      snowflakeHealth.setHealthy()

  private def retryUntilSuccessful[F[_]: Sync: Sleep, A](
    snowflakeHealth: SnowflakeHealth[F],
    config: Config.Retries,
    action: F[A]
  ): F[A] =
    action
      .onError(_ => snowflakeHealth.setUnhealthy())
      .retryingOnAllErrors(
        policy  = RetryPolicies.exponentialBackoff[F](config.backoff),
        onError = (error, details) => Logger[F].error(error)(s"Executing Snowflake command failed. ${extractRetryDetails(details)}")
      )

  private def extractRetryDetails(details: RetryDetails): String = details match {
    case RetryDetails.GivingUp(totalRetries, totalDelay) =>
      s"Giving up on retrying, total retries: $totalRetries, total delay: ${totalDelay.toSeconds} seconds"
    case RetryDetails.WillDelayAndRetry(nextDelay, retriesSoFar, cumulativeDelay) =>
      s"Will retry in ${nextDelay.toSeconds} seconds, retries so far: $retriesSoFar, total delay so far: ${cumulativeDelay.toSeconds} seconds"
  }
}
