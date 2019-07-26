// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.index

import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.actor.Scheduler
import akka.pattern.after
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import com.daml.ledger.participant.state.v1.ReadService
import com.digitalasset.platform.common.util.{DirectExecutionContext => DEC}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object RecoveringPostgresIndexer {
  def create(factory: IndexerFactory)(
      implicit mat: Materializer,
      scheduler: Scheduler): Future[RecoveringPostgresIndexer] = {
    val indexer = new RecoveringPostgresIndexer(factory)
    indexer.ready.future.map(_ => indexer)(DEC)
  }

  type IndexerFactory = () => Future[PostgresIndexer]
}

sealed abstract class CurrentState
final case class CurrentStateInitial() extends CurrentState
final case class CurrentStateCreated(indexer: PostgresIndexer) extends CurrentState
final case class CurrentStateSubscribed(indexer: PostgresIndexer, handle: IndexFeedHandle)
    extends CurrentState

sealed abstract class TargetState
final case class TargetStateCreated(done: Promise[Done]) extends TargetState
final case class TargetStateSubscribed(
    readService: ReadService,
    onError: Throwable => Unit,
    onComplete: () => Unit,
    done: Promise[IndexFeedHandle])
    extends TargetState

sealed abstract class Action
final case class ActionStep() extends Action
final case class ActionIStarted(indexer: PostgresIndexer) extends Action
final case class ActionISubscribed(indexer: PostgresIndexer, handle: IndexFeedHandle) extends Action
final case class ActionIStopped(indexer: PostgresIndexer) extends Action
final case class ActionIError(exception: Throwable) extends Action
final case class ActionEClose() extends Action
final case class ActionEStop(done: Promise[Done]) extends Action
final case class ActionESubscribe(
    readService: ReadService,
    onError: Throwable => Unit,
    onComplete: () => Unit,
    done: Promise[IndexFeedHandle])
    extends Action

/**
  A wrapper around [[PostgresIndexer]] that automatically recovers from errors.

  This class separately tracks target state (what the indexer should do)
  and current state (what the indexer does), and tries to always make progress such that
  the current state follows the target state.

  When an error is encountered, the entire PostgresIndexer is stopped and discarded,
  and a new one is started.
  This should be safe, as the PostgresIndexer itself should be resilient against random crashes
  in the middle of any database operation.

  Errors can happen at any stage, including during initialization, retries, or while waiting for an async result.
  To avoid concurrency issues, all asynchronous actions are serialized through an Akka queue.
  The queue is processed using [[processAction]], which is the only method allowed to alter state.

  [[Future]] results of wrapped methods and the underlying PostgresIndexer are linked using [[Promise]]s.
  */
class RecoveringPostgresIndexer private (factory: RecoveringPostgresIndexer.IndexerFactory)(
    implicit mat: Materializer,
    scheduler: Scheduler)
    extends Indexer
    with AutoCloseable {

  private[this] val logger = LoggerFactory.getLogger(classOf[RecoveringPostgresIndexer])

  /** A promise that completes when the underlying indexer was successfully created for the first time */
  val ready: Promise[Done] = Promise[Done]()

  private[this] val state: AtomicReference[(TargetState, CurrentState)] =
    new AtomicReference[(TargetState, CurrentState)](
      (TargetStateCreated(ready), CurrentStateInitial()))

  private[this] val queue: SourceQueueWithComplete[Action] =
    Source
      .queue[Action](100, OverflowStrategy.fail)
      // Not using getAndUpdate since the function is not side effect free
      // This is the only place where [[state]] is written
      .map(action => state.set(processAction(action, state.get)))
      .toMat(Sink.ignore)(Keep.left[SourceQueueWithComplete[Action], Future[Done]])
      .run()

  private[this] object FeedHandle extends IndexFeedHandle {
    def stop(): Future[Done] = {
      val promise = Promise[Done]()
      queue.offer(ActionEStop(promise))
      promise.future
    }
  }

  override def subscribe(
      readService: ReadService,
      onError: Throwable => Unit,
      onComplete: () => Unit): Future[IndexFeedHandle] = {

    val promise = Promise[IndexFeedHandle]()
    queue.offer(ActionESubscribe(readService, onError, onComplete, promise))
    promise.future
  }

  override def close(): Unit = {
    queue.offer(ActionEClose())
    ()
  }

  /** Used as callback in Indexer.subscribe.
    * This class retries on errors, the original callback is not used.
    */
  private[this] def internalOnError(err: Throwable): Unit = {
    queue.offer(ActionIError(err))
    ()
  }

  private[this] def processAction(
      action: Action,
      state: (TargetState, CurrentState)): (TargetState, CurrentState) = this.synchronized {
    (action, state) match {
      // --------------------------------------------------------------------------------------------------------------
      // External actions (generated by calls to public methods)
      // --------------------------------------------------------------------------------------------------------------
      case (ActionESubscribe(readService, onError, onComplete, done), (_: TargetStateCreated, _)) =>
        queue.offer(ActionStep())
        (TargetStateSubscribed(readService, onError, onComplete, done), state._2)
      case (_: ActionESubscribe, (_: TargetStateSubscribed, _)) =>
        sys.error("Can't subscribe, already subscribed")

      case (ActionEStop(done), (_: TargetStateSubscribed, _)) =>
        queue.offer(ActionStep())
        (TargetStateCreated(done), state._2)
      case (_: ActionEStop, (_: TargetStateCreated, _)) =>
        sys.error("Can't stop, not subscribed")

      case (ActionEClose(), (_, CurrentStateInitial())) =>
        queue.complete()
        state

      case (ActionEClose(), (_, CurrentStateCreated(indexer))) =>
        indexer.close()
        queue.complete()
        state

      case (ActionEClose(), (_, CurrentStateSubscribed(indexer, _))) =>
        indexer.close()
        queue.complete()
        state

      // --------------------------------------------------------------------------------------------------------------
      // Internal actions (generated by callbacks from the wrapped Indexer)
      // --------------------------------------------------------------------------------------------------------------
      case (ActionIStarted(indexer), (_, _)) =>
        queue.offer(ActionStep())
        (state._1, CurrentStateCreated(indexer))

      case (ActionISubscribed(indexer, handle), (_, _)) =>
        queue.offer(ActionStep())
        (state._1, CurrentStateSubscribed(indexer, handle))

      case (ActionIStopped(indexer), (_, _)) =>
        queue.offer(ActionStep())
        (state._1, CurrentStateCreated(indexer))

      case (ActionIError(error), (_, _)) =>
        logger.warn("Error in wrapped indexer, retry scheduled", error)
        // TODO (RA): exponential backoff
        val waitTime = 1.seconds
        after(waitTime, scheduler)(Future.successful(Done))(DEC).map(_ =>
          queue.offer(ActionStep()))(DEC)
        (state._1, CurrentStateInitial())

      // --------------------------------------------------------------------------------------------------------------
      // Step (making progress towards target state)
      // --------------------------------------------------------------------------------------------------------------
      case (ActionStep(), (TargetStateCreated(done), CurrentStateCreated(_))) =>
        done.trySuccess(Done)
        state

      case (ActionStep(), (TargetStateSubscribed(_, _, _, done), CurrentStateSubscribed(_, _))) =>
        done.trySuccess(FeedHandle)
        state

      case (ActionStep(), (TargetStateCreated(_), CurrentStateInitial())) =>
        factory()
          .onComplete {
            case Success(indexer) => queue.offer(ActionIStarted(indexer))
            case Failure(err) => queue.offer(ActionIError(err))
          }(DEC)
        state

      case (
          ActionStep(),
          (TargetStateSubscribed(readService, _, onComplete, _), CurrentStateCreated(indexer))) =>
        indexer
          .subscribe(readService, internalOnError, onComplete)
          .onComplete {
            case Success(handle) => queue.offer(ActionISubscribed(indexer, handle))
            case Failure(err) => queue.offer(ActionIError(err))
          }(DEC)
        state

      case (ActionStep(), (_: TargetStateSubscribed, CurrentStateInitial())) =>
        factory()
          .onComplete {
            case Success(indexer) => queue.offer(ActionIStarted(indexer))
            case Failure(err) => queue.offer(ActionIError(err))
          }(DEC)
        state

      case (ActionStep(), (TargetStateCreated(_), CurrentStateSubscribed(indexer, handle))) =>
        handle
          .stop()
          .onComplete {
            case Success(_) => queue.offer(ActionIStopped(indexer))
            case Failure(err) => queue.offer(ActionIError(err))
          }(DEC)
        state
    }
  }
}
