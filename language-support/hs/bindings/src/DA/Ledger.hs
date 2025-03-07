-- Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

module DA.Ledger ( -- High level interface to the Ledger API

    Port(..), Host(..), ClientConfig(..),

    module DA.Ledger.LedgerService,
    module DA.Ledger.PastAndFuture,
    module DA.Ledger.Services,
    module DA.Ledger.Stream,
    module DA.Ledger.Types,

    configOfPort,
    configOfHostAndPort,

    getTransactionsPF,

    withGetTransactions,
    withGetTransactionTrees,

    withGetAllTransactions,
    withGetTransactionsPF,
    withGetAllTransactionTrees,

    ) where

import Network.GRPC.HighLevel.Generated(Port(..),Host(..),ClientConfig(..))
import DA.Ledger.LedgerService
import DA.Ledger.PastAndFuture
import DA.Ledger.Services
import DA.Ledger.Stream
import DA.Ledger.Types

import UnliftIO (liftIO,timeout,bracket)

configOfPort :: Port -> ClientConfig
configOfPort = configOfHostAndPort "localhost"

configOfHostAndPort :: Host -> Port -> ClientConfig
configOfHostAndPort host port =
    ClientConfig { clientServerHost = host
                 , clientServerPort = port
                 , clientArgs = []
                 , clientSSLConfig = Nothing
                 }

withTimeout :: LedgerService a -> LedgerService (Maybe a)
withTimeout ls = do
    nSecs <- askTimeout
    timeout (1_000_000 * nSecs) ls


getTransactionsPF :: LedgerId -> Party -> LedgerService (PastAndFuture [Transaction])
getTransactionsPF lid party = do
    now <- fmap LedgerAbsOffset (ledgerEnd lid)
    let filter = filterEverthingForParty party
    let verbose = Verbosity False
    let req1 = GetTransactionsRequest lid LedgerBegin (Just now) filter verbose
    let req2 = GetTransactionsRequest lid now         Nothing    filter verbose
    stream <- getTransactions req1
    future <- getTransactions req2
    Just past <- withTimeout $ liftIO $ streamToList stream
    return PastAndFuture { past, future }


closeStreamLS :: Stream a -> LedgerService ()
closeStreamLS stream = liftIO $ closeStream stream EOS


withGetTransactions
    :: GetTransactionsRequest
    -> (Stream [Transaction] -> LedgerService a)
    -> LedgerService a
withGetTransactions req =
    bracket (getTransactions req) closeStreamLS


withGetTransactionTrees
    :: GetTransactionsRequest
    -> (Stream [TransactionTree] -> LedgerService a)
    -> LedgerService a
withGetTransactionTrees req =
    bracket (getTransactionTrees req) closeStreamLS


withGetAllTransactions
    :: LedgerId -> Party -> Verbosity
    -> (Stream [Transaction] -> LedgerService a)
    -> LedgerService a
withGetAllTransactions lid party verbose act = do
    let filter = filterEverthingForParty party
    let req = GetTransactionsRequest lid LedgerBegin Nothing filter verbose
    withGetTransactions req act

withGetTransactionsPF
    :: LedgerId -> Party
    -> (PastAndFuture [Transaction] -> LedgerService a)
    -> LedgerService a
withGetTransactionsPF lid party act = do
    now <- fmap LedgerAbsOffset (ledgerEnd lid)
    let filter = filterEverthingForParty party
    let verbose = Verbosity False
    let req1 = GetTransactionsRequest lid LedgerBegin (Just now) filter verbose
    let req2 = GetTransactionsRequest lid now         Nothing    filter verbose
    withGetTransactions req1 $ \stream -> do
    withGetTransactions req2 $ \future -> do
    Just past <- withTimeout $ liftIO $ streamToList stream
    act $ PastAndFuture { past, future }


withGetAllTransactionTrees
    :: LedgerId -> Party -> Verbosity
    -> (Stream [TransactionTree] -> LedgerService a)
    -> LedgerService a
withGetAllTransactionTrees lid party verbose act = do
    let filter = filterEverthingForParty party
    let req = GetTransactionsRequest lid LedgerBegin Nothing filter verbose
    withGetTransactionTrees req act
