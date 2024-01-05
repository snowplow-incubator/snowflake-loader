/*
 * Copyright (c) 2023-present Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.snowflake.processing

import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Semaphore
import cats.Functor
import cats.implicits._

/**
 * Manages swapping of Resources
 *
 * Inspired by `cats.effect.std.Hotswap` but with differences. A Hotswap is "hot" because a `swap`
 * acquires the next resource before closing the previous one. Whereas this Coldswap is "cold"
 * because it always closes any previous Resources before acquiring the next one.
 *
 * * '''Note''': The resource cannot be simultaneously open and closed, and so
 * `coldswap.opened.surround(coldswap.closed.use_)` will deadlock.
 */
final class Coldswap[F[_]: Sync, A] private (
  sem: Semaphore[F],
  ref: Ref[F, Coldswap.State[F, A]],
  resource: Resource[F, A]
) {
  import Coldswap._

  /**
   * Gets the current resource, or opens a new one if required. The returned `A` is guaranteed to be
   * available for the duration of the `Resource.use` block.
   */
  def opened: Resource[F, A] =
    (sem.permit *> Resource.eval[F, State[F, A]](ref.get)).flatMap {
      case Opened(a, _) => Resource.pure(a)
      case Closed =>
        for {
          _ <- releaseHeldPermit(sem)
          _ <- acquireAllPermits(sem)
          a <- Resource.eval(doOpen(ref, resource))
        } yield a
    }

  /**
   * Closes the resource if it was open. The resource is guaranteed to remain closed for the
   * duration of the `Resource.use` block.
   */
  def closed: Resource[F, Unit] =
    (sem.permit *> Resource.eval(ref.get)).flatMap {
      case Closed => Resource.unit
      case Opened(_, _) =>
        for {
          _ <- releaseHeldPermit(sem)
          _ <- acquireAllPermits(sem)
          _ <- Resource.eval(doClose(ref))
        } yield ()
    }

}

object Coldswap {

  private sealed trait State[+F[_], +A]
  private case object Closed extends State[Nothing, Nothing]
  private case class Opened[F[_], A](value: A, close: F[Unit]) extends State[F, A]

  def make[F[_]: Async, A](resource: Resource[F, A]): Resource[F, Coldswap[F, A]] =
    for {
      sem <- Resource.eval(Semaphore[F](Long.MaxValue))
      ref <- Resource.eval(Ref.of[F, State[F, A]](Closed))
      _ <- Resource.onFinalize(acquireAllPermits(sem).use(_ => doClose(ref)))
    } yield new Coldswap(sem, ref, resource)

  private def releaseHeldPermit[F[_]: Functor](sem: Semaphore[F]): Resource[F, Unit] =
    Resource.makeFull[F, Unit](poll => poll(sem.release))(_ => sem.acquire)

  private def acquireAllPermits[F[_]: Functor](sem: Semaphore[F]): Resource[F, Unit] =
    Resource.makeFull[F, Unit](poll => poll(sem.acquireN(Long.MaxValue)))(_ => sem.releaseN(Long.MaxValue))

  private def doClose[F[_]: Sync, A](ref: Ref[F, State[F, A]]): F[Unit] =
    ref.get.flatMap {
      case Closed => Sync[F].unit
      case Opened(_, close) =>
        Sync[F].uncancelable { _ =>
          close *> ref.set(Closed)
        }
    }

  private def doOpen[F[_]: Sync, A](ref: Ref[F, State[F, A]], resource: Resource[F, A]): F[A] =
    ref.get.flatMap {
      case Opened(a, _) => Sync[F].pure(a)
      case Closed =>
        Sync[F].uncancelable { poll =>
          for {
            (a, close) <- poll(resource.allocated)
            _ <- ref.set(Opened(a, close))
          } yield a
        }
    }

}
