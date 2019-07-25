// Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf
package archive

import com.digitalasset.daml.lf.archive.Decode.ParseError
import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.data.{Numeric, ImmArray, Time}
import ImmArray.ImmArraySeq
import com.digitalasset.daml.lf.language.Ast._
import com.digitalasset.daml.lf.language.LanguageMajorVersion.V1
import com.digitalasset.daml.lf.language.Util._
import com.digitalasset.daml.lf.language.{LanguageMinorVersion, LanguageVersion}
import com.digitalasset.daml_lf.{DamlLf1 => PLF}
import com.google.protobuf.CodedInputStream

import scala.collection.JavaConverters._
import scala.collection.{breakOut, mutable}

private[archive] class DecodeV1(minor: LanguageMinorVersion) extends Decode.OfPackage[PLF.Package] {

  import Decode._, DecodeV1._, LanguageMinorVersion.Implicits._

  private val languageVersion = LanguageVersion(V1, minor)

  private def name(s: String): Name = eitherToParseError(Name.fromString(s))

  override def decodePackage(packageId: PackageId, lfPackage: PLF.Package): Package = {
    val interned = decodeInternedPackageIds(lfPackage.getInternedPackageIdsList.asScala)
    Package(lfPackage.getModulesList.asScala.map(ModuleDecoder(packageId, interned, _).decode))
  }

  type ProtoModule = PLF.Module

  override def protoModule(cis: CodedInputStream): ProtoModule =
    PLF.Module.parser().parseFrom(cis)

  override def decodeScenarioModule(packageId: PackageId, lfModule: ProtoModule): Module =
    ModuleDecoder(packageId, ImmArraySeq.empty, lfModule).decode()

  private[this] def eitherToParseError[A](x: Either[String, A]): A =
    x.fold(err => throw new ParseError(err), identity)

  private[this] def decodeInternedPackageIds(internedList: Seq[String]): ImmArraySeq[PackageId] = {
    if (internedList.nonEmpty)
      assertSince(internedIdsVersion, "interned package ID table")
    internedList.map(s => eitherToParseError(PackageId.fromString(s)))(breakOut)
  }

  private[this] def decodeSegments(segments: ImmArray[String]): DottedName =
    DottedName.fromSegments(segments.toSeq) match {
      case Left(err) => throw new ParseError(err)
      case Right(x) => x
    }

  case class ModuleDecoder(
      packageId: PackageId,
      internedPackageIds: ImmArraySeq[PackageId],
      lfModule: PLF.Module) {

    val moduleName = eitherToParseError(
      ModuleName.fromSegments(lfModule.getName.getSegmentsList.asScala))

    // FIXME(JM): rewrite.
    var currentDefinitionRef: Option[DefinitionRef] = None

    def decode(): Module = {
      val defs = mutable.ArrayBuffer[(DottedName, Definition)]()
      val templates = mutable.ArrayBuffer[(DottedName, Template)]()

      // collect data types
      lfModule.getDataTypesList.asScala.foreach { defn =>
        val defName =
          eitherToParseError(DottedName.fromSegments(defn.getName.getSegmentsList.asScala))
        currentDefinitionRef = Some(DefinitionRef(packageId, QualifiedName(moduleName, defName)))
        val d = decodeDefDataType(defn)
        defs += (defName -> d)
      }

      // collect values
      lfModule.getValuesList.asScala.foreach { defn =>
        val defName =
          decodeSegments(ImmArray(defn.getNameWithType.getNameList.asScala))
        currentDefinitionRef = Some(DefinitionRef(packageId, QualifiedName(moduleName, defName)))
        val d = decodeDefValue(defn)
        defs += (defName -> d)
      }

      // collect templates
      lfModule.getTemplatesList.asScala.foreach { defn =>
        val defName =
          eitherToParseError(DottedName.fromSegments(defn.getTycon.getSegmentsList.asScala))
        currentDefinitionRef = Some(DefinitionRef(packageId, QualifiedName(moduleName, defName)))
        templates += ((defName, decodeTemplate(defn)))
      }

      Module(moduleName, defs, templates, languageVersion, decodeFeatureFlags(lfModule.getFlags))
    }

    // -----------------------------------------------------------------------

    private[this] def decodeFeatureFlags(flags: PLF.FeatureFlags): FeatureFlags = {
      // NOTE(JM, #157): We disallow loading packages with these flags because they impact the Ledger API in
      // ways that would currently make it quite complicated to support them.
      if (!flags.getDontDivulgeContractIdsInCreateArguments || !flags.getDontDiscloseNonConsumingChoicesToObservers) {
        throw new ParseError("Deprecated feature flag settings detected, refusing to parse package")
      }
      FeatureFlags(
        forbidPartyLiterals = flags.getForbidPartyLiterals,
      )
    }

    private[this] def decodeDefDataType(lfDataType: PLF.DefDataType): DDataType = {
      val params = ImmArray(lfDataType.getParamsList.asScala).map(decodeTypeVarWithKind)
      DDataType(
        lfDataType.getSerializable,
        params,
        lfDataType.getDataConsCase match {
          case PLF.DefDataType.DataConsCase.RECORD =>
            DataRecord(decodeFields(ImmArray(lfDataType.getRecord.getFieldsList.asScala)), None)
          case PLF.DefDataType.DataConsCase.VARIANT =>
            DataVariant(decodeFields(ImmArray(lfDataType.getVariant.getFieldsList.asScala)))
          case PLF.DefDataType.DataConsCase.ENUM =>
            assertSince(enumVersion, "DefDataType.DataCons.Enum")
            assertEmpty(params.toSeq, "params")
            DataEnum(decodeEnumCons(ImmArray(lfDataType.getEnum.getConstructorsList.asScala)))
          case PLF.DefDataType.DataConsCase.DATACONS_NOT_SET =>
            throw ParseError("DefDataType.DATACONS_NOT_SET")

        }
      )
    }

    private[this] def decodeFields(lfFields: ImmArray[PLF.FieldWithType]): ImmArray[(Name, Type)] =
      lfFields.map(field => name(field.getField) -> decodeType(field.getType))

    private[this] def decodeEnumCons(cons: ImmArray[String]): ImmArray[EnumConName] =
      cons.map(name)

    private[this] def decodeDefValue(lfValue: PLF.DefValue): DValue =
      DValue(
        typ = decodeType(lfValue.getNameWithType.getType),
        noPartyLiterals = lfValue.getNoPartyLiterals,
        body = decodeExpr(lfValue.getExpr),
        isTest = lfValue.getIsTest
      )

    private def decodeLocation(lfExpr: PLF.Expr): Option[Location] =
      if (lfExpr.hasLocation && lfExpr.getLocation.hasRange) {
        val loc = lfExpr.getLocation
        val (pkgId, module) =
          if (loc.hasModule)
            decodeModuleRef(loc.getModule)
          else
            (packageId, moduleName)

        val range = loc.getRange
        Some(
          Location(
            pkgId,
            module,
            (range.getStartLine, range.getStartCol),
            (range.getEndLine, range.getEndCol)))
      } else {
        None
      }

    private[this] def decodeTemplateKey(
        key: PLF.DefTemplate.DefKey,
        tplVar: ExprVarName): TemplateKey = {
      assertSince("3", "DefTemplate.DefKey")
      val keyExpr = key.getKeyExprCase match {
        case PLF.DefTemplate.DefKey.KeyExprCase.KEY =>
          decodeKeyExpr(key.getKey, tplVar)
        case PLF.DefTemplate.DefKey.KeyExprCase.COMPLEX_KEY => {
          assertSince("4", "DefTemplate.DefKey.complex_key")
          decodeExpr(key.getComplexKey)
        }
        case PLF.DefTemplate.DefKey.KeyExprCase.KEYEXPR_NOT_SET =>
          throw ParseError("DefKey.KEYEXPR_NOT_SET")
      }
      TemplateKey(
        decodeType(key.getType),
        keyExpr,
        decodeExpr(key.getMaintainers)
      )
    }

    private[this] def decodeKeyExpr(expr: PLF.KeyExpr, tplVar: ExprVarName): Expr = {
      expr.getSumCase match {
        case PLF.KeyExpr.SumCase.RECORD =>
          val recCon = expr.getRecord
          ERecCon(
            tycon = decodeTypeConApp(recCon.getTycon),
            fields = ImmArray(recCon.getFieldsList.asScala).map(field =>
              name(field.getField) -> decodeKeyExpr(field.getExpr, tplVar))
          )

        case PLF.KeyExpr.SumCase.PROJECTIONS =>
          val lfProjs = expr.getProjections.getProjectionsList.asScala
          lfProjs.foldLeft(EVar(tplVar): Expr)((acc, lfProj) =>
            ERecProj(decodeTypeConApp(lfProj.getTycon), name(lfProj.getField), acc))

        case PLF.KeyExpr.SumCase.SUM_NOT_SET =>
          throw ParseError("KeyExpr.SUM_NOT_SET")
      }
    }

    private[this] def decodeTemplate(lfTempl: PLF.DefTemplate): Template =
      Template(
        param = name(lfTempl.getParam),
        precond = if (lfTempl.hasPrecond) decodeExpr(lfTempl.getPrecond) else ETrue,
        signatories = decodeExpr(lfTempl.getSignatories),
        agreementText = decodeExpr(lfTempl.getAgreement),
        choices = lfTempl.getChoicesList.asScala
          .map(decodeChoice)
          .map(ch => (ch.name, ch)),
        observers = decodeExpr(lfTempl.getObservers),
        key =
          if (lfTempl.hasKey) Some(decodeTemplateKey(lfTempl.getKey, name(lfTempl.getParam)))
          else None
      )

    private[this] def decodeChoice(lfChoice: PLF.TemplateChoice): TemplateChoice = {
      val (v, t) = decodeBinder(lfChoice.getArgBinder)
      TemplateChoice(
        name = name(lfChoice.getName),
        consuming = lfChoice.getConsuming,
        controllers = decodeExpr(lfChoice.getControllers),
        selfBinder = name(lfChoice.getSelfBinder),
        argBinder = Some(v) -> t,
        returnType = decodeType(lfChoice.getRetType),
        update = decodeExpr(lfChoice.getUpdate)
      )
    }

    // package visible for testing purpose. Do not use outside the class ModuleDecoder.
    private[lf] def decodeKind(lfKind: PLF.Kind): Kind =
      lfKind.getSumCase match {
        case PLF.Kind.SumCase.STAR => KStar
        case PLF.Kind.SumCase.ARROW =>
          val kArrow = lfKind.getArrow
          val params = kArrow.getParamsList.asScala
          assertNonEmpty(params, "params")
          (params :\ decodeKind(kArrow.getResult))((param, kind) => KArrow(decodeKind(param), kind))
        case PLF.Kind.SumCase.NAT =>
          assertSince(numericVersion, "nat kind")
          KNat
        case PLF.Kind.SumCase.SUM_NOT_SET =>
          throw ParseError("Kind.SUM_NOT_SET")
      }

    // package visible for testing purpose. Do not use outside the class ModuleDecoder.
    private[lf] def decodeType(lfType: PLF.Type): Type =
      lfType.getSumCase match {
        case PLF.Type.SumCase.VAR =>
          val tvar = lfType.getVar
          tvar.getArgsList.asScala
            .foldLeft[Type](TVar(name(tvar.getVar)))((typ, arg) => TApp(typ, decodeType(arg)))
        case PLF.Type.SumCase.CON =>
          val tcon = lfType.getCon
          (TTyCon(decodeTypeConName(tcon.getTycon)) /: [Type] tcon.getArgsList.asScala)(
            (typ, arg) => TApp(typ, decodeType(arg)))
        case PLF.Type.SumCase.PRIM =>
          val prim = lfType.getPrim
          val (tPrim, minVersion) = primTypeTable(prim.getPrim)
          assertSince(minVersion, prim.getPrim.getValueDescriptor.getFullName)
          val typ =
            if (tPrim == BTNumeric && V1.minorVersionOrdering.lt(minor, numericVersion))
              TNumeric(legacyDecimalScale)
            else
              TBuiltin(tPrim)
          (typ /: [Type] prim.getArgsList.asScala)((typ, arg) => TApp(typ, decodeType(arg)))
        case PLF.Type.SumCase.FUN =>
          assertUntil("0", "Type.Fun")
          val tFun = lfType.getFun
          val params = tFun.getParamsList.asScala
          assertNonEmpty(params, "params")
          (params :\ decodeType(tFun.getResult))((param, res) => TFun(decodeType(param), res))
        case PLF.Type.SumCase.FORALL =>
          val tForall = lfType.getForall
          val vars = tForall.getVarsList.asScala
          assertNonEmpty(vars, "vars")
          (vars :\ decodeType(tForall.getBody))((binder, acc) =>
            TForall(decodeTypeVarWithKind(binder), acc))
        case PLF.Type.SumCase.TUPLE =>
          val tuple = lfType.getTuple
          val fields = tuple.getFieldsList.asScala
          assertNonEmpty(fields, "fields")
          TTuple(
            ImmArray(fields.map(ft => name(ft.getField) -> decodeType(ft.getType)))
          )
        case PLF.Type.SumCase.NAT =>
          assertSince(numericVersion, "nat types")
          val n = lfType.getNat
          if (n < 0 || 38 < n)
            throw ParseError(s"Nat types must be between 0 and 38")
          TNat(n.toInt)

        case PLF.Type.SumCase.SUM_NOT_SET =>
          throw ParseError("Type.SUM_NOT_SET")
      }

    private[this] def decodeModuleRef(lfRef: PLF.ModuleRef): (PackageId, ModuleName) = {
      val modName = eitherToParseError(
        ModuleName.fromSegments(lfRef.getModuleName.getSegmentsList.asScala))
      import PLF.PackageRef.{SumCase => SC}
      val pkgId = lfRef.getPackageRef.getSumCase match {
        case SC.SELF =>
          this.packageId
        case SC.PACKAGE_ID =>
          val rawPid = lfRef.getPackageRef.getPackageId
          PackageId
            .fromString(rawPid)
            .getOrElse(throw ParseError(s"invalid packageId '$rawPid'"))
        case SC.INTERNED_ID =>
          assertSince(internedIdsVersion, "interned package ID")
          val iidl = lfRef.getPackageRef.getInternedId
          def outOfRange = ParseError(s"invalid package ID table index $iidl")
          val iid = iidl.toInt
          if (iidl != iid.toLong) throw outOfRange
          internedPackageIds.lift(iid).getOrElse(throw outOfRange)
        case SC.SUM_NOT_SET =>
          throw ParseError("PackageRef.SUM_NOT_SET")
      }
      (pkgId, modName)
    }

    private[this] def decodeValName(lfVal: PLF.ValName): ValueRef = {
      val (packageId, module) = decodeModuleRef(lfVal.getModule)
      val name = decodeSegments(ImmArray(lfVal.getNameList.asScala))
      ValueRef(packageId, QualifiedName(module, name))
    }

    private[this] def decodeTypeConName(lfTyConName: PLF.TypeConName): TypeConName = {
      val (packageId, module) = decodeModuleRef(lfTyConName.getModule)
      val name = eitherToParseError(
        DottedName.fromSegments(lfTyConName.getName.getSegmentsList.asScala))
      Identifier(packageId, QualifiedName(module, name))
    }

    private[this] def decodeTypeConApp(lfTyConApp: PLF.Type.Con): TypeConApp =
      TypeConApp(
        decodeTypeConName(lfTyConApp.getTycon),
        ImmArray(lfTyConApp.getArgsList.asScala.map(decodeType))
      )

    // package visible for testing purpose. Do not use outside the class ModuleDecoder
    private[lf] def decodeExpr(lfExpr: PLF.Expr): Expr =
      decodeLocation(lfExpr) match {
        case None => decodeExprBody(lfExpr)
        case Some(loc) => ELocation(loc, decodeExprBody(lfExpr))
      }

    private[this] def decodeExprBody(lfExpr: PLF.Expr): Expr =
      lfExpr.getSumCase match {
        case PLF.Expr.SumCase.VAR =>
          EVar(name(lfExpr.getVar))

        case PLF.Expr.SumCase.VAL =>
          EVal(decodeValName(lfExpr.getVal))

        case PLF.Expr.SumCase.PRIM_LIT =>
          EPrimLit(decodePrimLit(lfExpr.getPrimLit))

        case PLF.Expr.SumCase.PRIM_CON =>
          lfExpr.getPrimCon match {
            case PLF.PrimCon.CON_UNIT => EUnit
            case PLF.PrimCon.CON_FALSE => EFalse
            case PLF.PrimCon.CON_TRUE => ETrue
            case PLF.PrimCon.UNRECOGNIZED =>
              throw ParseError("PrimCon.UNRECOGNIZED")
          }

        case PLF.Expr.SumCase.BUILTIN =>
          val bInfo = DecodeV1.builtinFunctionMap(lfExpr.getBuiltin)
          assertSince(bInfo.minVersion, lfExpr.getBuiltin.getValueDescriptor.getFullName)
          val e = EBuiltin(bInfo.lfFunction)
          if (bInfo.decimalLegacy && V1.minorVersionOrdering.lt(minor, numericVersion))
            ETyApp(e, legacyDecimalScale)
          else
            e

        case PLF.Expr.SumCase.REC_CON =>
          val recCon = lfExpr.getRecCon
          ERecCon(
            tycon = decodeTypeConApp(recCon.getTycon),
            fields = ImmArray(recCon.getFieldsList.asScala).map(field =>
              name(field.getField) -> decodeExpr(field.getExpr))
          )

        case PLF.Expr.SumCase.REC_PROJ =>
          val recProj = lfExpr.getRecProj
          ERecProj(
            tycon = decodeTypeConApp(recProj.getTycon),
            field = name(recProj.getField),
            record = decodeExpr(recProj.getRecord))

        case PLF.Expr.SumCase.REC_UPD =>
          val recUpd = lfExpr.getRecUpd
          ERecUpd(
            tycon = decodeTypeConApp(recUpd.getTycon),
            field = name(recUpd.getField),
            record = decodeExpr(recUpd.getRecord),
            update = decodeExpr(recUpd.getUpdate))

        case PLF.Expr.SumCase.VARIANT_CON =>
          val varCon = lfExpr.getVariantCon
          EVariantCon(
            decodeTypeConApp(varCon.getTycon),
            name(varCon.getVariantCon),
            decodeExpr(varCon.getVariantArg))

        case PLF.Expr.SumCase.ENUM_CON =>
          assertSince(enumVersion, "Expr.Enum")
          val enumCon = lfExpr.getEnumCon
          EEnumCon(
            decodeTypeConName(enumCon.getTycon),
            name(enumCon.getEnumCon)
          )

        case PLF.Expr.SumCase.TUPLE_CON =>
          val tupleCon = lfExpr.getTupleCon
          ETupleCon(
            ImmArray(tupleCon.getFieldsList.asScala).map(field =>
              name(field.getField) -> decodeExpr(field.getExpr))
          )

        case PLF.Expr.SumCase.TUPLE_PROJ =>
          val tupleProj = lfExpr.getTupleProj
          ETupleProj(name(tupleProj.getField), decodeExpr(tupleProj.getTuple))

        case PLF.Expr.SumCase.TUPLE_UPD =>
          val tupleUpd = lfExpr.getTupleUpd
          ETupleUpd(
            field = name(tupleUpd.getField),
            tuple = decodeExpr(tupleUpd.getTuple),
            update = decodeExpr(tupleUpd.getUpdate))

        case PLF.Expr.SumCase.APP =>
          val app = lfExpr.getApp
          val args = app.getArgsList.asScala
          assertNonEmpty(args, "args")
          (decodeExpr(app.getFun) /: args)((e, arg) => EApp(e, decodeExpr(arg)))

        case PLF.Expr.SumCase.ABS =>
          val lfAbs = lfExpr.getAbs
          val params = lfAbs.getParamList.asScala
          assertNonEmpty(params, "params")
          // val params = lfAbs.getParamList.asScala.map(decodeBinder)
          (params :\ decodeExpr(lfAbs.getBody))((param, e) =>
            EAbs(decodeBinder(param), e, currentDefinitionRef))

        case PLF.Expr.SumCase.TY_APP =>
          val tyapp = lfExpr.getTyApp
          val args = tyapp.getTypesList.asScala
          assertNonEmpty(args, "args")
          (decodeExpr(tyapp.getExpr) /: args)((e, arg) => ETyApp(e, decodeType(arg)))

        case PLF.Expr.SumCase.TY_ABS =>
          val lfTyAbs = lfExpr.getTyAbs
          val params = lfTyAbs.getParamList.asScala
          assertNonEmpty(params, "params")
          (params :\ decodeExpr(lfTyAbs.getBody))((param, e) =>
            ETyAbs(decodeTypeVarWithKind(param), e))

        case PLF.Expr.SumCase.LET =>
          val lfLet = lfExpr.getLet
          val bindings = lfLet.getBindingsList.asScala
          assertNonEmpty(bindings, "bindings")
          (bindings :\ decodeExpr(lfLet.getBody))((binding, e) => {
            val (v, t) = decodeBinder(binding.getBinder)
            ELet(Binding(Some(v), t, decodeExpr(binding.getBound)), e)
          })

        case PLF.Expr.SumCase.NIL =>
          ENil(decodeType(lfExpr.getNil.getType))

        case PLF.Expr.SumCase.CONS =>
          val cons = lfExpr.getCons
          val front = cons.getFrontList.asScala
          assertNonEmpty(front, "front")
          val typ = decodeType(cons.getType)
          ECons(typ, ImmArray(front.map(decodeExpr)), decodeExpr(cons.getTail))

        case PLF.Expr.SumCase.CASE =>
          val case_ = lfExpr.getCase
          ECase(
            decodeExpr(case_.getScrut),
            ImmArray(case_.getAltsList.asScala).map(decodeCaseAlt)
          )

        case PLF.Expr.SumCase.UPDATE =>
          EUpdate(decodeUpdate(lfExpr.getUpdate))

        case PLF.Expr.SumCase.SCENARIO =>
          EScenario(decodeScenario(lfExpr.getScenario))

        case PLF.Expr.SumCase.OPTIONAL_NONE =>
          assertSince(optionalVersion, "Expr.OptionalNone")
          ENone(decodeType(lfExpr.getOptionalNone.getType))

        case PLF.Expr.SumCase.OPTIONAL_SOME =>
          assertSince(optionalVersion, "Expr.OptionalSome")
          val some = lfExpr.getOptionalSome
          ESome(decodeType(some.getType), decodeExpr(some.getBody))

        case PLF.Expr.SumCase.SUM_NOT_SET =>
          throw ParseError("Expr.SUM_NOT_SET")
      }

    private[this] def decodeCaseAlt(lfCaseAlt: PLF.CaseAlt): CaseAlt = {
      val pat: CasePat = lfCaseAlt.getSumCase match {
        case PLF.CaseAlt.SumCase.DEFAULT =>
          CPDefault
        case PLF.CaseAlt.SumCase.VARIANT =>
          val variant = lfCaseAlt.getVariant
          CPVariant(
            decodeTypeConName(variant.getCon),
            name(variant.getVariant),
            name(variant.getBinder))
        case PLF.CaseAlt.SumCase.ENUM =>
          assertSince(enumVersion, "CaseAlt.Enum")
          val enum = lfCaseAlt.getEnum
          CPEnum(decodeTypeConName(enum.getCon), name(enum.getConstructor))
        case PLF.CaseAlt.SumCase.PRIM_CON =>
          CPPrimCon(decodePrimCon(lfCaseAlt.getPrimCon))
        case PLF.CaseAlt.SumCase.NIL =>
          CPNil
        case PLF.CaseAlt.SumCase.CONS =>
          val cons = lfCaseAlt.getCons
          CPCons(name(cons.getVarHead), name(cons.getVarTail))

        case PLF.CaseAlt.SumCase.OPTIONAL_NONE =>
          assertSince(optionalVersion, "CaseAlt.OptionalNone")
          CPNone

        case PLF.CaseAlt.SumCase.OPTIONAL_SOME =>
          assertSince(optionalVersion, "CaseAlt.OptionalSome")
          CPSome(name(lfCaseAlt.getOptionalSome.getVarBody))

        case PLF.CaseAlt.SumCase.SUM_NOT_SET =>
          throw ParseError("CaseAlt.SUM_NOT_SET")
      }
      CaseAlt(pat, decodeExpr(lfCaseAlt.getBody))
    }

    private[this] def decodeRetrieveByKey(value: PLF.Update.RetrieveByKey): RetrieveByKey = {
      RetrieveByKey(
        decodeTypeConName(value.getTemplate),
        decodeExpr(value.getKey),
      )
    }

    private[this] def decodeUpdate(lfUpdate: PLF.Update): Update =
      lfUpdate.getSumCase match {

        case PLF.Update.SumCase.PURE =>
          val pure = lfUpdate.getPure
          UpdatePure(decodeType(pure.getType), decodeExpr(pure.getExpr))

        case PLF.Update.SumCase.BLOCK =>
          val block = lfUpdate.getBlock
          UpdateBlock(
            bindings = ImmArray(block.getBindingsList.asScala.map(decodeBinding)),
            body = decodeExpr(block.getBody))

        case PLF.Update.SumCase.CREATE =>
          val create = lfUpdate.getCreate
          UpdateCreate(
            templateId = decodeTypeConName(create.getTemplate),
            arg = decodeExpr(create.getExpr))

        case PLF.Update.SumCase.EXERCISE =>
          val exercise = lfUpdate.getExercise
          UpdateExercise(
            templateId = decodeTypeConName(exercise.getTemplate),
            choice = name(exercise.getChoice),
            cidE = decodeExpr(exercise.getCid),
            actorsE =
              if (exercise.hasActor)
                Some(decodeExpr(exercise.getActor))
              else {
                assertSince("5", "Update.Exercise.actors optional")
                None
              },
            argE = decodeExpr(exercise.getArg)
          )

        case PLF.Update.SumCase.GET_TIME =>
          UpdateGetTime

        case PLF.Update.SumCase.FETCH =>
          val fetch = lfUpdate.getFetch
          UpdateFetch(
            templateId = decodeTypeConName(fetch.getTemplate),
            contractId = decodeExpr(fetch.getCid))

        case PLF.Update.SumCase.FETCH_BY_KEY =>
          assertSince("2", "fetchByKey")
          UpdateFetchByKey(decodeRetrieveByKey(lfUpdate.getFetchByKey))

        case PLF.Update.SumCase.LOOKUP_BY_KEY =>
          assertSince("2", "lookupByKey")
          UpdateLookupByKey(decodeRetrieveByKey(lfUpdate.getLookupByKey))

        case PLF.Update.SumCase.EMBED_EXPR =>
          val embedExpr = lfUpdate.getEmbedExpr
          UpdateEmbedExpr(decodeType(embedExpr.getType), decodeExpr(embedExpr.getBody))

        case PLF.Update.SumCase.SUM_NOT_SET =>
          throw ParseError("Update.SUM_NOT_SET")
      }

    private[this] def decodeScenario(lfScenario: PLF.Scenario): Scenario =
      lfScenario.getSumCase match {
        case PLF.Scenario.SumCase.PURE =>
          val pure = lfScenario.getPure
          ScenarioPure(decodeType(pure.getType), decodeExpr(pure.getExpr))

        case PLF.Scenario.SumCase.COMMIT =>
          val commit = lfScenario.getCommit
          ScenarioCommit(
            decodeExpr(commit.getParty),
            decodeExpr(commit.getExpr),
            decodeType(commit.getRetType))

        case PLF.Scenario.SumCase.MUSTFAILAT =>
          val commit = lfScenario.getMustFailAt
          ScenarioMustFailAt(
            decodeExpr(commit.getParty),
            decodeExpr(commit.getExpr),
            decodeType(commit.getRetType))

        case PLF.Scenario.SumCase.BLOCK =>
          val block = lfScenario.getBlock
          ScenarioBlock(
            bindings = ImmArray(block.getBindingsList.asScala).map(decodeBinding(_)),
            body = decodeExpr(block.getBody))

        case PLF.Scenario.SumCase.GET_TIME =>
          ScenarioGetTime

        case PLF.Scenario.SumCase.PASS =>
          ScenarioPass(decodeExpr(lfScenario.getPass))

        case PLF.Scenario.SumCase.GET_PARTY =>
          ScenarioGetParty(decodeExpr(lfScenario.getGetParty))

        case PLF.Scenario.SumCase.EMBED_EXPR =>
          val embedExpr = lfScenario.getEmbedExpr
          ScenarioEmbedExpr(decodeType(embedExpr.getType), decodeExpr(embedExpr.getBody))

        case PLF.Scenario.SumCase.SUM_NOT_SET =>
          throw ParseError("Scenario.SUM_NOT_SET")
      }

    private[this] def decodeTypeVarWithKind(
        lfTypeVarWithKind: PLF.TypeVarWithKind): (TypeVarName, Kind) =
      name(lfTypeVarWithKind.getVar) -> decodeKind(lfTypeVarWithKind.getKind)

    private[this] def decodeBinding(lfBinding: PLF.Binding): Binding = {
      val (binder, typ) = decodeBinder(lfBinding.getBinder)
      Binding(Some(binder), typ, decodeExpr(lfBinding.getBound))
    }

    private[this] def decodeBinder(lfBinder: PLF.VarWithType): (ExprVarName, Type) =
      name(lfBinder.getVar) -> decodeType(lfBinder.getType)

    private[this] def decodePrimCon(lfPrimCon: PLF.PrimCon): PrimCon =
      lfPrimCon match {
        case PLF.PrimCon.CON_UNIT =>
          PCUnit
        case PLF.PrimCon.CON_FALSE =>
          PCFalse
        case PLF.PrimCon.CON_TRUE =>
          PCTrue
        case _ => throw ParseError("Unknown PrimCon: " + lfPrimCon.toString)
      }

    private[this] def decodePrimLit(lfPrimLit: PLF.PrimLit): PrimLit =
      lfPrimLit.getSumCase match {
        case PLF.PrimLit.SumCase.INT64 =>
          PLInt64(lfPrimLit.getInt64)
        case PLF.PrimLit.SumCase.NUMERIC =>
          val d =
            if (V1.minorVersionOrdering.lt(minor, numericVersion)) {
              checkDecimal(lfPrimLit.getNumeric)
              Numeric.unscaledFromString(lfPrimLit.getNumeric).map(_.setScale(10))
            } else {
              checkNumeric(lfPrimLit.getNumeric)
              Numeric.scaledFromString(lfPrimLit.getNumeric)
            }
          d.fold(e => throw ParseError("error parsing numeric: " + e), PLNumeric)
        case PLF.PrimLit.SumCase.TEXT =>
          PLText(lfPrimLit.getText)
        case PLF.PrimLit.SumCase.PARTY =>
          val p = Party
            .fromString(lfPrimLit.getParty)
            .getOrElse(throw ParseError(s"invalid party '${lfPrimLit.getParty}'"))
          PLParty(p)
        case PLF.PrimLit.SumCase.TIMESTAMP =>
          val t = Time.Timestamp.fromLong(lfPrimLit.getTimestamp)
          t.fold(e => throw ParseError("error decoding timestamp: " + e), PLTimestamp)
        case PLF.PrimLit.SumCase.DATE =>
          val d = Time.Date.fromDaysSinceEpoch(lfPrimLit.getDate)
          d.fold(e => throw ParseError("error decoding date: " + e), PLDate)
        case unknown =>
          throw ParseError("Unknown PrimLit: " + unknown.toString)
      }
  }

  private def assertUntil(maxMinorVersion: LanguageMinorVersion, description: String): Unit =
    if (V1.minorVersionOrdering.gt(minor, maxMinorVersion))
      throw ParseError(s"$description is not supported by DAML-LF 1.$minor")

  private def assertSince(minMinorVersion: LanguageMinorVersion, description: String): Unit =
    if (V1.minorVersionOrdering.lt(minor, minMinorVersion))
      throw ParseError(s"$description is not supported by DAML-LF 1.$minor")

  private def assertNonEmpty(s: Seq[_], description: String): Unit =
    if (s.isEmpty) throw ParseError(s"Unexpected empty $description")

  private def assertEmpty(s: Seq[_], description: String): Unit =
    if (s.nonEmpty) throw ParseError(s"Unexpected non-empty $description")

  private val legacyDecimalScale = TNat(Numeric.legacyDecimalScale)

}

private[lf] object DecodeV1 {
  import LanguageMinorVersion.Implicits._

  private[archive] val optionalVersion: LanguageMinorVersion = "1"
  private[archive] val enumVersion: LanguageMinorVersion = "6"
  private val internedIdsVersion: LanguageMinorVersion = "6"
  private[archive] val numericVersion: LanguageMinorVersion = "dev"

  val primTypeTable: Map[PLF.PrimType, (BuiltinType, LanguageMinorVersion)] = {
    import PLF.PrimType._

    Map(
      UNIT -> (BTUnit -> "0"),
      BOOL -> (BTBool -> "0"),
      TEXT -> (BTText -> "0"),
      INT64 -> (BTInt64 -> "0"),
      NUMERIC -> (BTNumeric -> "0"),
      TIMESTAMP -> (BTTimestamp -> "0"),
      PARTY -> (BTParty -> "0"),
      LIST -> (BTList -> "0"),
      UPDATE -> (BTUpdate -> "0"),
      SCENARIO -> (BTScenario -> "0"),
      CONTRACT_ID -> (BTContractId -> "0"),
      DATE -> (BTDate -> "0"),
      OPTIONAL -> (BTOptional -> "1"),
      MAP -> (BTMap -> "3"),
      ARROW -> (BTArrow -> "1"),
    )
  }

  case class BuiltinInfo(
      protoName: PLF.BuiltinFunction,
      lfFunction: BuiltinFunction,
      minVersion: LanguageMinorVersion,
      decimalLegacy: Boolean = false
  )

  val builtinInfos = {
    import PLF.BuiltinFunction._
    List(
      BuiltinInfo(ADD_NUMERIC, BAddNumeric, "0", decimalLegacy = true),
      BuiltinInfo(SUB_NUMERIC, BSubNumeric, "0", decimalLegacy = true),
      BuiltinInfo(MUL_NUMERIC, BMulNumeric, "0", decimalLegacy = true),
      BuiltinInfo(DIV_NUMERIC, BDivNumeric, "0", decimalLegacy = true),
      BuiltinInfo(ROUND_NUMERIC, BRoundNumeric, "0", decimalLegacy = true),
      BuiltinInfo(ADD_INT64, BAddInt64, "0"),
      BuiltinInfo(SUB_INT64, BSubInt64, "0"),
      BuiltinInfo(MUL_INT64, BMulInt64, "0"),
      BuiltinInfo(DIV_INT64, BDivInt64, "0"),
      BuiltinInfo(MOD_INT64, BModInt64, "0"),
      BuiltinInfo(EXP_INT64, BExpInt64, "0"),
      BuiltinInfo(INT64_TO_NUMERIC, BInt64ToNumeric, "0", decimalLegacy = true),
      BuiltinInfo(NUMERIC_TO_INT64, BNumericToInt64, "0", decimalLegacy = true),
      BuiltinInfo(FOLDL, BFoldl, "0"),
      BuiltinInfo(FOLDR, BFoldr, "0"),
      BuiltinInfo(MAP_EMPTY, BMapEmpty, "3"),
      BuiltinInfo(MAP_INSERT, BMapInsert, "3"),
      BuiltinInfo(MAP_LOOKUP, BMapLookup, "3"),
      BuiltinInfo(MAP_DELETE, BMapDelete, "3"),
      BuiltinInfo(MAP_TO_LIST, BMapToList, "3"),
      BuiltinInfo(MAP_SIZE, BMapSize, "3"),
      BuiltinInfo(APPEND_TEXT, BAppendText, "0"),
      BuiltinInfo(ERROR, BError, "0"),
      BuiltinInfo(LEQ_INT64, BLessEqInt64, "0"),
      BuiltinInfo(LEQ_NUMERIC, BLessEqNumeric, "0", decimalLegacy = true),
      BuiltinInfo(LEQ_TEXT, BLessEqText, "0"),
      BuiltinInfo(LEQ_TIMESTAMP, BLessEqTimestamp, "0"),
      BuiltinInfo(LEQ_PARTY, BLessEqParty, "1"),
      BuiltinInfo(GEQ_INT64, BGreaterEqInt64, "0"),
      BuiltinInfo(GEQ_NUMERIC, BGreaterEqNumeric, "0", decimalLegacy = true),
      BuiltinInfo(GEQ_TEXT, BGreaterEqText, "0"),
      BuiltinInfo(GEQ_TIMESTAMP, BGreaterEqTimestamp, "0"),
      BuiltinInfo(GEQ_PARTY, BGreaterEqParty, "1"),
      BuiltinInfo(LESS_INT64, BLessInt64, "0"),
      BuiltinInfo(LESS_NUMERIC, BLessNumeric, "0", decimalLegacy = true),
      BuiltinInfo(LESS_TEXT, BLessText, "0"),
      BuiltinInfo(LESS_TIMESTAMP, BLessTimestamp, "0"),
      BuiltinInfo(LESS_PARTY, BLessParty, "1"),
      BuiltinInfo(GREATER_INT64, BGreaterInt64, "0"),
      BuiltinInfo(GREATER_NUMERIC, BGreaterNumeric, "0", decimalLegacy = true),
      BuiltinInfo(GREATER_TEXT, BGreaterText, "0"),
      BuiltinInfo(GREATER_TIMESTAMP, BGreaterTimestamp, "0"),
      BuiltinInfo(GREATER_PARTY, BGreaterParty, "1"),
      BuiltinInfo(TO_TEXT_INT64, BToTextInt64, "0"),
      BuiltinInfo(TO_TEXT_NUMERIC, BToTextNumeric, "0", decimalLegacy = true),
      BuiltinInfo(TO_TEXT_TIMESTAMP, BToTextTimestamp, "0"),
      BuiltinInfo(TO_TEXT_PARTY, BToTextParty, "2"),
      BuiltinInfo(TO_TEXT_TEXT, BToTextText, "0"),
      BuiltinInfo(TO_QUOTED_TEXT_PARTY, BToQuotedTextParty, "0"),
      BuiltinInfo(TEXT_FROM_CODE_POINTS, BToTextCodePoints, "6"),
      BuiltinInfo(FROM_TEXT_PARTY, BFromTextParty, "2"),
      BuiltinInfo(FROM_TEXT_INT64, BFromTextInt64, "5"),
      BuiltinInfo(FROM_TEXT_NUMERIC, BFromTextNumeric, "5", decimalLegacy = true),
      BuiltinInfo(TEXT_TO_CODE_POINTS, BFromTextCodePoints, "6"),
      BuiltinInfo(SHA256_TEXT, BSHA256Text, "2"),
      BuiltinInfo(DATE_TO_UNIX_DAYS, BDateToUnixDays, "0"),
      BuiltinInfo(EXPLODE_TEXT, BExplodeText, "0"),
      BuiltinInfo(IMPLODE_TEXT, BImplodeText, "0"),
      BuiltinInfo(GEQ_DATE, BGreaterEqDate, "0"),
      BuiltinInfo(LEQ_DATE, BLessEqDate, "0"),
      BuiltinInfo(LESS_DATE, BLessDate, "0"),
      BuiltinInfo(TIMESTAMP_TO_UNIX_MICROSECONDS, BTimestampToUnixMicroseconds, "0"),
      BuiltinInfo(TO_TEXT_DATE, BToTextDate, "0"),
      BuiltinInfo(UNIX_DAYS_TO_DATE, BUnixDaysToDate, "0"),
      BuiltinInfo(UNIX_MICROSECONDS_TO_TIMESTAMP, BUnixMicrosecondsToTimestamp, "0"),
      BuiltinInfo(GREATER_DATE, BGreaterDate, "0"),
      BuiltinInfo(EQUAL_INT64, BEqualInt64, "0"),
      BuiltinInfo(EQUAL_NUMERIC, BEqualNumeric, "0", decimalLegacy = true),
      BuiltinInfo(EQUAL_TEXT, BEqualText, "0"),
      BuiltinInfo(EQUAL_TIMESTAMP, BEqualTimestamp, "0"),
      BuiltinInfo(EQUAL_DATE, BEqualDate, "0"),
      BuiltinInfo(EQUAL_PARTY, BEqualParty, "0"),
      BuiltinInfo(EQUAL_BOOL, BEqualBool, "0"),
      BuiltinInfo(EQUAL_LIST, BEqualList, "0"),
      BuiltinInfo(EQUAL_CONTRACT_ID, BEqualContractId, "0"),
      BuiltinInfo(TRACE, BTrace, "0"),
      BuiltinInfo(COERCE_CONTRACT_ID, BCoerceContractId, "5"),
    )
  }

  private val builtinFunctionMap =
    builtinInfos
      .map(info => info.protoName -> info)
      .toMap
      .withDefault(_ => throw ParseError("BuiltinFunction.UNRECOGNIZED"))

  private val decimalPattern = """[+-]?\d{1,28}(\.\d{1,10})?""".r.pattern

  def checkDecimal(s: String): Boolean =
    decimalPattern.matcher(s).matches()

  private val numericPattern = """-?\d{1,38}\.\d{0,38}""".r.pattern

  def checkNumeric(s: String): Boolean =
    numericPattern.matcher(s).matches()

}
