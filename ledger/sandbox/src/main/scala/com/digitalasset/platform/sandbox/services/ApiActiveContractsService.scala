// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.services

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.daml.ledger.participant.state.index.v2.{
  ActiveContractSetSnapshot,
  IndexActiveContractsService => ACSBackend
}
import com.digitalasset.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.ledger.api.domain.LedgerId
import com.digitalasset.ledger.api.v1.active_contracts_service.ActiveContractsServiceGrpc.ActiveContractsService
import com.digitalasset.ledger.api.v1.active_contracts_service._
import com.digitalasset.ledger.api.v1.event.CreatedEvent
import com.digitalasset.ledger.api.validation.TransactionFilterValidator
import com.digitalasset.platform.api.grpc.GrpcApiService
import com.digitalasset.platform.common.util.DirectExecutionContext
import com.digitalasset.platform.participant.util.LfEngineToApi
import com.digitalasset.platform.server.api.validation.ActiveContractsServiceValidation
import io.grpc.{BindableService, ServerServiceDefinition}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

import scalaz.syntax.tag._

class ApiActiveContractsService private (
    backend: ACSBackend,
    parallelism: Int = Runtime.getRuntime.availableProcessors)(
    implicit executionContext: ExecutionContext,
    protected val mat: Materializer,
    protected val esf: ExecutionSequencerFactory)
    extends ActiveContractsServiceAkkaGrpc
    with GrpcApiService {

  private val logger = LoggerFactory.getLogger(this.getClass)
  @SuppressWarnings(Array("org.wartremover.warts.Option2Iterable"))
  override protected def getActiveContractsSource(
      request: GetActiveContractsRequest): Source[GetActiveContractsResponse, NotUsed] = {
    logger.trace("Serving an Active Contracts request...")

    TransactionFilterValidator
      .validate(request.getFilter, "filter")
      .fold(
        Source.failed, { filter =>
          Source
            .fromFuture(backend.getActiveContractSetSnapshot(filter))
            .flatMapConcat {
              case ActiveContractSetSnapshot(offset, acsStream) =>
                acsStream
                  .map {
                    case (wfId, create) =>
                      GetActiveContractsResponse(
                        workflowId = wfId.map(_.unwrap).getOrElse(""),
                        activeContracts = List(
                          CreatedEvent(
                            create.eventId.unwrap,
                            create.contractId.coid,
                            Some(LfEngineToApi.toApiIdentifier(create.templateId)),
                            create.contractKey.map(
                              ck =>
                                LfEngineToApi.assertOrRuntimeEx(
                                  "converting stored contract",
                                  LfEngineToApi
                                    .lfVersionedValueToApiValue(verbose = request.verbose, ck))),
                            Some(
                              LfEngineToApi.assertOrRuntimeEx(
                                "converting stored contract",
                                LfEngineToApi
                                  .lfValueToApiRecord(
                                    verbose = request.verbose,
                                    create.argument.value))),
                            create.stakeholders.toSeq,
                            signatories = create.signatories.map(_.toString)(collection.breakOut),
                            observers = create.observers.map(_.toString)(collection.breakOut)
                          )
                        )
                      )
                  }
                  .concat(Source.single(GetActiveContractsResponse(offset = offset.value)))
            }
        }
      )
  }

  override def bindService(): ServerServiceDefinition =
    ActiveContractsServiceGrpc.bindService(this, DirectExecutionContext)
}

object ApiActiveContractsService {
  type TransactionId = String
  type WorkflowId = String

  def create(ledgerId: LedgerId, backend: ACSBackend)(
      implicit ec: ExecutionContext,
      mat: Materializer,
      esf: ExecutionSequencerFactory)
    : ActiveContractsService with BindableService with ActiveContractsServiceLogging =
    new ActiveContractsServiceValidation(
      new ApiActiveContractsService(backend)(ec, mat, esf),
      ledgerId
    ) with BindableService with ActiveContractsServiceLogging {
      override def bindService(): ServerServiceDefinition =
        ActiveContractsServiceGrpc.bindService(this, DirectExecutionContext)
    }
}
