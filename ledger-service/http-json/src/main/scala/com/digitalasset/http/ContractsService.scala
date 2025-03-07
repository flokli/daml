// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.http

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.digitalasset.http.domain.TemplateId
import com.digitalasset.http.util.FutureUtil.toFuture
import com.digitalasset.http.util.IdentifierConverters.apiIdentifier
import com.digitalasset.ledger.api.refinements.{ApiTypes => lar}
import com.digitalasset.ledger.api.{v1 => lav1}
import com.digitalasset.ledger.client.services.acs.ActiveContractSetClient
import scalaz.std.string._
import scalaz.{-\/, \/-}

import scala.concurrent.{ExecutionContext, Future}

class ContractsService(
    resolveTemplateIds: PackageService.ResolveTemplateIds,
    activeContractSetClient: ActiveContractSetClient,
    parallelism: Int = 8)(implicit ec: ExecutionContext, mat: Materializer) {

  def lookup(jwtPayload: domain.JwtPayload, request: domain.ContractLookupRequest[lav1.value.Value])
    : Future[Option[domain.ActiveContract[lav1.value.Value]]] =
    request.id match {
      case -\/((templateId, contractKey)) =>
        lookup(jwtPayload.party, templateId, contractKey)
      case \/-((templateId, contractId)) =>
        lookup(jwtPayload.party, templateId, contractId)
    }

  def lookup(
      party: lar.Party,
      templateId: TemplateId.OptionalPkg,
      contractKey: lav1.value.Value): Future[Option[domain.ActiveContract[lav1.value.Value]]] =
    for {
      as <- search(party, Set(templateId))
      a = findByContractKey(contractKey)(as)
    } yield a

  private def findByContractKey(k: lav1.value.Value)(
      as: Seq[domain.GetActiveContractsResponse[lav1.value.Value]])
    : Option[domain.ActiveContract[lav1.value.Value]] =
    (as.view: Seq[domain.GetActiveContractsResponse[lav1.value.Value]])
      .flatMap(a => a.activeContracts)
      .find(isContractKey(k))

  private def isContractKey(k: lav1.value.Value)(
      a: domain.ActiveContract[lav1.value.Value]): Boolean =
    a.key.fold(false)(_ == k)

  def lookup(
      party: lar.Party,
      templateId: Option[TemplateId.OptionalPkg],
      contractId: String): Future[Option[domain.ActiveContract[lav1.value.Value]]] =
    for {
      as <- search(party, templateIds(templateId))
      a = findByContractId(contractId)(as)
    } yield a

  private def templateIds(a: Option[TemplateId.OptionalPkg]): Set[TemplateId.OptionalPkg] =
    a.toList.toSet

  private def findByContractId(k: String)(
      as: Seq[domain.GetActiveContractsResponse[lav1.value.Value]])
    : Option[domain.ActiveContract[lav1.value.Value]] =
    (as.view: Seq[domain.GetActiveContractsResponse[lav1.value.Value]])
      .flatMap(a => a.activeContracts)
      .find(x => (x.contractId: String) == k)

  def search(jwtPayload: domain.JwtPayload, request: domain.GetActiveContractsRequest)
    : Future[Seq[domain.GetActiveContractsResponse[lav1.value.Value]]] =
    search(jwtPayload.party, request.templateIds)

  def search(party: lar.Party, templateIds: Set[domain.TemplateId.OptionalPkg])
    : Future[Seq[domain.GetActiveContractsResponse[lav1.value.Value]]] =
    for {
      templateIds <- toFuture(resolveTemplateIds(templateIds))
      activeContracts <- activeContractSetClient
        .getActiveContracts(transactionFilter(party, templateIds), verbose = true)
        .mapAsyncUnordered(parallelism)(gacr =>
          toFuture(domain.GetActiveContractsResponse.fromLedgerApi(gacr)))
        .runWith(Sink.seq)
    } yield activeContracts

  private def transactionFilter(
      party: lar.Party,
      templateIds: List[TemplateId.RequiredPkg]): lav1.transaction_filter.TransactionFilter = {
    import lav1.transaction_filter._

    val filters =
      if (templateIds.isEmpty) Filters.defaultInstance
      else Filters(Some(lav1.transaction_filter.InclusiveFilters(templateIds.map(apiIdentifier))))

    TransactionFilter(Map(lar.Party.unwrap(party) -> filters))
  }
}

object ContractsService {
  final case class Error(message: String)
}
