-- Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE DerivingStrategies #-}

-- | Monoid with which to render documentation.
module DA.Daml.Doc.Render.Monoid
    ( module DA.Daml.Doc.Render.Monoid
    ) where

import DA.Daml.Doc.Types
import Control.Monad
import Data.Foldable
import Data.Maybe
import Data.List.Extra
import System.FilePath
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as T

data RenderOut
    = RenderSpaced [RenderOut]
    | RenderModuleHeader T.Text
    | RenderSectionHeader T.Text
    | RenderAnchor Anchor
    | RenderBlock RenderOut
    | RenderList [RenderOut]
    | RenderRecordFields [(RenderText, RenderText, RenderText)]
    | RenderParagraph RenderText
    | RenderDocs DocText

data RenderText
    = RenderConcat [RenderText]
    | RenderPlain T.Text
    | RenderStrong T.Text
    | RenderLink Anchor T.Text
    | RenderDocsInline DocText

chunks :: RenderOut -> [RenderOut]
chunks (RenderSpaced xs) = concatMap chunks xs
chunks x = [x]

unchunks :: [RenderOut] -> RenderOut
unchunks [x] = x
unchunks xs = RenderSpaced xs

instance Semigroup RenderOut where
    a <> b = unchunks (chunks a ++ chunks b)

instance Monoid RenderOut where
    mempty = RenderSpaced []
    mconcat = unchunks . concatMap chunks

instance Semigroup RenderText where
    a <> b = RenderConcat [a, b]

instance Monoid RenderText where
    mempty = RenderConcat []
    mconcat = RenderConcat

renderIntercalate :: T.Text -> [RenderText] -> RenderText
renderIntercalate t xs = mconcat (intersperse (RenderPlain t) xs)

renderUnwords :: [RenderText] -> RenderText
renderUnwords = renderIntercalate " "

-- | Environment in which to generate final documentation.
data RenderEnv = RenderEnv
    { lookupAnchor :: Anchor -> Maybe AnchorLocation
        -- ^ get location of anchor relative to render output, if available
    }

-- | Location of an anchor relative to the output being rendered. An anchor
-- that lives on the same page may be rendered differently from an anchor
-- that lives in the same folder but a different page, and that may be
-- rendered differently from an anchor that is external. Thus we can
-- handle every case correctly.
data AnchorLocation
    = SameFile -- ^ anchor is in same file
    | SameFolder FilePath -- ^ anchor is in a file within same folder
    -- TODO: | External URL -- ^ anchor is in on a page at the given URL


-- | Build relative hyperlink from anchor and anchor location.
anchorRelativeHyperlink :: AnchorLocation -> Anchor -> T.Text
anchorRelativeHyperlink anchorLoc (Anchor anchor) =
    case anchorLoc of
        SameFile -> "#" <> anchor
        SameFolder fileName -> T.concat [T.pack fileName, "#", anchor]



type RenderFormatter = RenderEnv -> RenderOut -> [T.Text]

getRenderAnchors :: RenderOut -> Set.Set Anchor
getRenderAnchors = \case
    RenderSpaced xs -> mconcatMap getRenderAnchors xs
    RenderModuleHeader _ -> Set.empty
    RenderSectionHeader _ -> Set.empty
    RenderAnchor anchor -> Set.singleton anchor
    RenderBlock x -> getRenderAnchors x
    RenderList xs -> mconcatMap getRenderAnchors xs
    RenderRecordFields _ -> Set.empty
    RenderParagraph _ -> Set.empty
    RenderDocs _ -> Set.empty

renderPage :: RenderFormatter -> RenderOut -> T.Text
renderPage formatter output =
    T.unlines (formatter renderEnv output)
  where
    localAnchors = getRenderAnchors output

    lookupAnchor :: Anchor -> Maybe AnchorLocation
    lookupAnchor anchor
        | Set.member anchor localAnchors = Just SameFile
        | otherwise = Nothing

    renderEnv = RenderEnv {..}

-- | Render a folder of modules.
renderFolder ::
    RenderFormatter
    -> Map.Map Modulename RenderOut
    -> Map.Map Modulename T.Text
renderFolder formatter fileMap =
    let moduleAnchors = Map.map getRenderAnchors fileMap
        globalAnchors = Map.fromList
            [ (anchor, moduleNameToFileName moduleName <.> "html")
            | (moduleName, anchors) <- Map.toList moduleAnchors
            , anchor <- Set.toList anchors
            ]
    in flip Map.mapWithKey fileMap $ \moduleName output ->
        let localAnchors = fromMaybe Set.empty $
                Map.lookup moduleName moduleAnchors
            lookupAnchor anchor = asum
                [ SameFile <$ guard (Set.member anchor localAnchors)
                , SameFolder <$> Map.lookup anchor globalAnchors
                ]
            renderEnv = RenderEnv {..}
        in T.unlines (formatter renderEnv output)

moduleNameToFileName :: Modulename -> FilePath
moduleNameToFileName =
    T.unpack . T.replace "." "-" . unModulename
