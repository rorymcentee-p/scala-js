/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package org.scalajs.nscplugin

import scala.collection.{immutable, mutable}

import scala.tools.nsc._
import scala.math.PartialOrdering
import scala.reflect.{ClassTag, classTag}
import scala.reflect.internal.Flags

import org.scalajs.ir
import org.scalajs.ir.{Trees => js, Types => jstpe}
import org.scalajs.ir.Names.LocalName
import org.scalajs.ir.OriginalName.NoOriginalName
import org.scalajs.ir.Trees.OptimizerHints

import org.scalajs.nscplugin.util.ScopedVar
import ScopedVar.withScopedVars

/** Generation of exports for JavaScript
 *
 *  @author Sébastien Doeraene
 */
trait GenJSExports[G <: Global with Singleton] extends SubComponent {
  self: GenJSCode[G] =>

  import global._
  import jsAddons._
  import definitions._
  import jsDefinitions._
  import jsInterop.{jsNameOf, JSName}

  trait JSExportsPhase { this: JSCodePhase =>

    /** Generates exported methods and properties for a class.
     *
     *  @param classSym symbol of the class we export for
     */
    def genMemberExports(classSym: Symbol): List[js.MemberDef] = {
      val allExports = classSym.info.members.filter(jsInterop.isExport(_))

      val newlyDecldExports = if (classSym.superClass == NoSymbol) {
        allExports
      } else {
        allExports.filterNot { sym =>
          classSym.superClass.info.member(sym.name)
            .filter(_.tpe =:= sym.tpe).exists
        }
      }

      val newlyDecldExportNames =
        newlyDecldExports.map(_.name.toTermName).toList.distinct

      newlyDecldExportNames map { genMemberExport(classSym, _) }
    }

    def genJSClassDispatchers(classSym: Symbol,
        dispatchMethodsNames: List[JSName]): List[js.MemberDef] = {
      dispatchMethodsNames
        .map(genJSClassDispatcher(classSym, _))
    }

    private sealed trait ExportKind

    private object ExportKind {
      case object Module extends ExportKind
      case object JSClass extends ExportKind
      case object Constructor extends ExportKind
      case object Method extends ExportKind
      case object Property extends ExportKind
      case object Field extends ExportKind

      def apply(sym: Symbol): ExportKind = {
        if (isStaticModule(sym)) Module
        else if (sym.isClass) JSClass
        else if (sym.isConstructor) Constructor
        else if (!sym.isMethod) Field
        else if (jsInterop.isJSProperty(sym)) Property
        else Method
      }
    }

    private def checkSameKind(tups: List[(jsInterop.ExportInfo, Symbol)]): Option[ExportKind] = {
      assert(tups.nonEmpty, "must have at least one export")

      val firstSym = tups.head._2
      val overallKind = ExportKind(firstSym)
      var bad = false

      for ((info, sym) <- tups.tail) {
        val kind = ExportKind(sym)

        if (kind != overallKind) {
          bad = true
          reporter.error(info.pos, "export overload conflicts with export of " +
              s"$firstSym: they are of different types ($kind / $overallKind)")
        }
      }

      if (bad) None
      else Some(overallKind)
    }

    private def checkSingleField(tups: List[(jsInterop.ExportInfo, Symbol)]): Symbol = {
      assert(tups.nonEmpty, "must have at least one export")

      val firstSym = tups.head._2

      for ((info, _) <- tups.tail) {
        reporter.error(info.pos, "export overload conflicts with export of " +
            s"$firstSym: a field may not share its exported name with another export")
      }

      firstSym
    }

    def genTopLevelExports(classSym: Symbol): List[js.TopLevelExportDef] = {
      val exports = for {
        sym <- List(classSym) ++ classSym.info.members
        info <- jsInterop.topLevelExportsOf(sym)
      } yield {
        (info, sym)
      }

      (for {
        (info, tups) <- exports.groupBy(_._1)
        kind <- checkSameKind(tups)
      } yield {
        import ExportKind._

        implicit val pos = info.pos

        kind match {
          case Module =>
            js.TopLevelModuleExportDef(info.moduleID, info.jsName)

          case JSClass =>
            assert(isNonNativeJSClass(classSym), "found export on non-JS class")
            js.TopLevelJSClassExportDef(info.moduleID, info.jsName)

          case Constructor | Method =>
            val methodDef = withNewLocalNameScope {
              genExportMethod(tups.map(_._2), JSName.Literal(info.jsName), static = true)
            }

            js.TopLevelMethodExportDef(info.moduleID, methodDef)

          case Property =>
            throw new AssertionError("found top-level exported property")

          case Field =>
            val sym = checkSingleField(tups)
            js.TopLevelFieldExportDef(info.moduleID, info.jsName, encodeFieldSym(sym))
        }
      }).toList
    }

    def genStaticExports(classSym: Symbol): List[js.MemberDef] = {
      val exports = (for {
        sym <- classSym.info.members
        info <- jsInterop.staticExportsOf(sym)
      } yield {
        (info, sym)
      }).toList

      (for {
        (info, tups) <- exports.groupBy(_._1)
        kind <- checkSameKind(tups)
      } yield {
        def alts = tups.map(_._2)

        implicit val pos = info.pos

        import ExportKind._

        kind match {
          case Method =>
            genMemberExportOrDispatcher(
                JSName.Literal(info.jsName), isProp = false, alts, static = true)

          case Property =>
            genMemberExportOrDispatcher(
                JSName.Literal(info.jsName), isProp = true, alts, static = true)

          case Field =>
            val sym = checkSingleField(tups)

            // static fields must always be mutable
            val flags = js.MemberFlags.empty
              .withNamespace(js.MemberNamespace.PublicStatic)
              .withMutable(true)
            val name = js.StringLiteral(info.jsName)
            val irTpe = genExposedFieldIRType(sym)
            js.JSFieldDef(flags, name, irTpe)

          case kind =>
            throw new AssertionError(s"unexpected static export kind: $kind")
        }
      }).toList
    }

    private def genMemberExport(classSym: Symbol, name: TermName): js.MemberDef = {
      /* This used to be `.member(name)`, but it caused #3538, since we were
       * sometimes selecting mixin forwarders, whose type history does not go
       * far enough back in time to see varargs. We now explicitly exclude
       * mixed-in members in addition to bridge methods (the latter are always
       * excluded by `.member(name)`).
       */
      val alts = classSym.info.memberBasedOnName(name,
          excludedFlags = Flags.BRIDGE | Flags.MIXEDIN).alternatives

      assert(!alts.isEmpty,
          s"Ended up with no alternatives for ${classSym.fullName}::$name. " +
          s"Original set was ${alts} with types ${alts.map(_.tpe)}")

      val (jsName, isProp) = jsInterop.jsExportInfo(name)

      // Check if we have a conflicting export of the other kind
      val conflicting =
        classSym.info.member(jsInterop.scalaExportName(jsName, !isProp))

      if (conflicting != NoSymbol) {
        val kind = if (isProp) "property" else "method"
        val alts = conflicting.alternatives

        reporter.error(alts.head.pos,
            s"Exported $kind $jsName conflicts with ${alts.head.fullName}")
      }

      genMemberExportOrDispatcher(JSName.Literal(jsName), isProp, alts, static = false)
    }

    private def genJSClassDispatcher(classSym: Symbol, name: JSName): js.MemberDef = {
      val alts = classSym.info.members.toList.filter { sym =>
        sym.isMethod &&
        !sym.isBridge &&
        /* #3939: Object is not a "real" superclass of JS types.
         * as such, its methods do not participate in overload resolution.
         * An exception is toString, which is handled specially in
         * genExportMethod.
         */
        sym.owner != ObjectClass &&
        jsNameOf(sym) == name
      }

      assert(!alts.isEmpty,
          s"Ended up with no alternatives for ${classSym.fullName}::$name.")

      val (propSyms, methodSyms) = alts.partition(jsInterop.isJSProperty(_))
      val isProp = propSyms.nonEmpty

      if (isProp && methodSyms.nonEmpty) {
        reporter.error(alts.head.pos,
            s"Conflicting properties and methods for ${classSym.fullName}::$name.")
        implicit val pos = alts.head.pos
        js.JSPropertyDef(js.MemberFlags.empty, genExpr(name), None, None)
      } else {
        genMemberExportOrDispatcher(name, isProp, alts, static = false)
      }
    }

    def genMemberExportOrDispatcher(jsName: JSName, isProp: Boolean,
        alts: List[Symbol], static: Boolean): js.MemberDef = {
      withNewLocalNameScope {
        if (isProp)
          genExportProperty(alts, jsName, static)
        else
          genExportMethod(alts, jsName, static)
      }
    }

    private def genExportProperty(alts: List[Symbol], jsName: JSName,
        static: Boolean): js.JSPropertyDef = {
      assert(!alts.isEmpty,
          s"genExportProperty with empty alternatives for $jsName")

      implicit val pos = alts.head.pos

      val namespace =
        if (static) js.MemberNamespace.PublicStatic
        else js.MemberNamespace.Public
      val flags = js.MemberFlags.empty.withNamespace(namespace)

      // Separate getters and setters. Somehow isJSGetter doesn't work here. Hence
      // we just check the parameter list length.
      val (getter, setters) = alts.partition(_.tpe.params.isEmpty)

      // We can have at most one getter
      if (getter.size > 1)
        reportCannotDisambiguateError(jsName, alts)

      val getterBody = getter.headOption.map { getterSym =>
        genApplyForSym(new FormalArgsRegistry(0, false), getterSym, static)
      }

      val setterArgAndBody = {
        if (setters.isEmpty) {
          None
        } else {
          val formalArgsRegistry = new FormalArgsRegistry(1, false)
          val (List(arg), None) = formalArgsRegistry.genFormalArgs()
          val body = genOverloadDispatchSameArgc(jsName, formalArgsRegistry,
              alts = setters.map(new ExportedSymbol(_, static)), jstpe.AnyType,
              paramIndex = 0)
          Some((arg, body))
        }
      }

      js.JSPropertyDef(flags, genExpr(jsName), getterBody, setterArgAndBody)
    }

    /** generates the exporter function (i.e. exporter for non-properties) for
     *  a given name */
    private def genExportMethod(alts0: List[Symbol], jsName: JSName,
        static: Boolean): js.JSMethodDef = {
      assert(alts0.nonEmpty,
          "need at least one alternative to generate exporter method")

      implicit val pos = alts0.head.pos

      val namespace =
        if (static) js.MemberNamespace.PublicStatic
        else js.MemberNamespace.Public
      val flags = js.MemberFlags.empty.withNamespace(namespace)

      val alts = {
        // toString() is always exported. We might need to add it here
        // to get correct overloading.
        val needsToString =
          jsName == JSName.Literal("toString") && alts0.forall(_.tpe.params.nonEmpty)

        if (needsToString)
          Object_toString :: alts0
        else
          alts0
      }

      val overloads = alts.map(new ExportedSymbol(_, static))

      val (formalArgs, restParam, body) =
        genOverloadDispatch(jsName, overloads, jstpe.AnyType)

      js.JSMethodDef(flags, genExpr(jsName), formalArgs, restParam, body)(
          OptimizerHints.empty, None)
    }

    def genOverloadDispatch(jsName: JSName, alts: List[Exported], tpe: jstpe.Type)(
        implicit pos: Position): (List[js.ParamDef], Option[js.ParamDef], js.Tree) = {
      // Factor out methods with variable argument lists. Note that they can
      // only be at the end of the lists as enforced by PrepJSExports
      val (varArgMeths, normalMeths) = alts.partition(_.hasRepeatedParam)

      // Highest non-repeated argument count
      val maxArgc = (
          // We have argc - 1, since a repeated parameter list may also be empty
          // (unlike a normal parameter)
          varArgMeths.map(_.params.size - 1) ++
          normalMeths.map(_.params.size)
      ).max

      // Calculates possible arg counts for normal method
      def argCounts(ex: Exported) = {
        val params = ex.params
        // Find default param
        val dParam = params.indexWhere(_.hasDefault)
        if (dParam == -1) Seq(params.size)
        else dParam to params.size
      }

      // Generate tuples (argc, method)
      val methodArgCounts = {
        // Normal methods
        for {
          method <- normalMeths
          argc   <- argCounts(method)
        } yield (argc, method)
      } ++ {
        // Repeated parameter methods
        for {
          method <- varArgMeths
          argc   <- method.params.size - 1 to maxArgc
        } yield (argc, method)
      }

      // Create a map: argCount -> methods (methods may appear multiple times)
      val methodByArgCount =
        methodArgCounts.groupBy(_._1).map(kv => kv._1 -> kv._2.map(_._2).toSet)

      // Create the formal args registry
      val minArgc = methodByArgCount.keys.min
      val hasVarArg = varArgMeths.nonEmpty
      val needsRestParam = maxArgc != minArgc || hasVarArg
      val formalArgsRegistry = new FormalArgsRegistry(minArgc, needsRestParam)

      // List of formal parameters
      val (formalArgs, restParam) = formalArgsRegistry.genFormalArgs()

      // Create tuples: (methods, argCounts). This will be the cases we generate
      val caseDefinitions =
        methodByArgCount.groupBy(_._2).map(kv => kv._1 -> kv._2.keySet)

      // Verify stuff about caseDefinitions
      assert({
        val argcs = caseDefinitions.values.flatten.toList
        argcs == argcs.distinct &&
        argcs.forall(_ <= maxArgc)
      }, "every argc should appear only once and be lower than max")

      // Generate a case block for each (methods, argCounts) tuple
      val cases = for {
        (methods, argcs) <- caseDefinitions
        if methods.nonEmpty && argcs.nonEmpty

        // exclude default case we're generating anyways for varargs
        if methods != varArgMeths.toSet

        // body of case to disambiguates methods with current count
        caseBody = genOverloadDispatchSameArgc(jsName, formalArgsRegistry,
            methods.toList, tpe, paramIndex = 0, Some(argcs.min))

        // argc in reverse order
        argcList = argcs.toList.sortBy(- _)
      } yield (argcList.map(argc => js.IntLiteral(argc - minArgc)), caseBody)

      def defaultCase = {
        if (!hasVarArg) {
          genThrowTypeError()
        } else {
          genOverloadDispatchSameArgc(jsName, formalArgsRegistry, varArgMeths,
              tpe, paramIndex = 0)
        }
      }

      val body = {
        if (cases.isEmpty)
          defaultCase
        else if (cases.size == 1 && !hasVarArg)
          cases.head._2
        else {
          assert(needsRestParam,
              "Trying to read rest param length but needsRestParam is false")
          val restArgRef = formalArgsRegistry.genRestArgRef()
          js.Match(
              js.AsInstanceOf(js.JSSelect(restArgRef, js.StringLiteral("length")), jstpe.IntType),
              cases.toList, defaultCase)(tpe)
        }
      }

      (formalArgs, restParam, body)
    }

    /**
     * Resolve method calls to [[alts]] while assuming they have the same
     * parameter count.
     * @param minArgc The minimum number of arguments that must be given
     * @param alts Alternative methods
     * @param paramIndex Index where to start disambiguation
     * @param maxArgc only use that many arguments
     */
    private def genOverloadDispatchSameArgc(jsName: JSName,
        formalArgsRegistry: FormalArgsRegistry, alts: List[Exported],
        tpe: jstpe.Type, paramIndex: Int, maxArgc: Option[Int] = None): js.Tree = {

      implicit val pos = alts.head.sym.pos

      if (alts.size == 1) {
        alts.head.genBody(formalArgsRegistry)
      } else if (maxArgc.exists(_ <= paramIndex) ||
        !alts.exists(_.params.size > paramIndex)) {
        // We reach here in three cases:
        // 1. The parameter list has been exhausted
        // 2. The optional argument count restriction has triggered
        // 3. We only have (more than once) repeated parameters left
        // Therefore, we should fail
        reportCannotDisambiguateError(jsName, alts.map(_.sym))
        js.Undefined()
      } else {
        val altsByTypeTest = groupByWithoutHashCode(alts) { exported =>
          typeTestForTpe(exported.exportArgTypeAt(paramIndex))
        }

        if (altsByTypeTest.size == 1) {
          // Testing this parameter is not doing any us good
          genOverloadDispatchSameArgc(jsName, formalArgsRegistry, alts, tpe,
              paramIndex + 1, maxArgc)
        } else {
          // Sort them so that, e.g., isInstanceOf[String]
          // comes before isInstanceOf[Object]
          val sortedAltsByTypeTest = topoSortDistinctsWith(altsByTypeTest) { (lhs, rhs) =>
            (lhs._1, rhs._1) match {
              // NoTypeTest is always last
              case (_, NoTypeTest) => true
              case (NoTypeTest, _) => false

              case (PrimitiveTypeTest(_, rank1), PrimitiveTypeTest(_, rank2)) =>
                rank1 <= rank2

              case (InstanceOfTypeTest(t1), InstanceOfTypeTest(t2)) =>
                t1 <:< t2

              case (_: PrimitiveTypeTest, _: InstanceOfTypeTest) => true
              case (_: InstanceOfTypeTest, _: PrimitiveTypeTest) => false
            }
          }

          val defaultCase = genThrowTypeError()

          sortedAltsByTypeTest.foldRight[js.Tree](defaultCase) { (elem, elsep) =>
            val (typeTest, subAlts) = elem
            implicit val pos = subAlts.head.sym.pos

            val paramRef = formalArgsRegistry.genArgRef(paramIndex)
            val genSubAlts = genOverloadDispatchSameArgc(jsName, formalArgsRegistry,
                subAlts, tpe, paramIndex + 1, maxArgc)

            def hasDefaultParam = subAlts.exists { exported =>
              val params = exported.params
              params.size > paramIndex &&
              params(paramIndex).hasDefault
            }

            val optCond = typeTest match {
              case PrimitiveTypeTest(tpe, _) =>
                Some(js.IsInstanceOf(paramRef, tpe))

              case InstanceOfTypeTest(tpe) =>
                Some(genIsInstanceOf(paramRef, tpe))

              case NoTypeTest =>
                None
            }

            optCond.fold[js.Tree] {
              genSubAlts // note: elsep is discarded, obviously
            } { cond =>
              val condOrUndef = if (!hasDefaultParam) cond else {
                js.If(cond, js.BooleanLiteral(true),
                    js.BinaryOp(js.BinaryOp.===, paramRef, js.Undefined()))(
                    jstpe.BooleanType)
              }
              js.If(condOrUndef, genSubAlts, elsep)(tpe)
            }
          }
        }
      }
    }

    private def reportCannotDisambiguateError(jsName: JSName,
        alts: List[Symbol]): Unit = {
      val currentClass = currentClassSym.get

      /* Find a position that is in the current class for decent error reporting.
       * If there are more than one, always use the "highest" one (i.e., the
       * one coming last in the source text) so that we reliably display the
       * same error in all compilers.
       */
      val validPositions = alts.collect {
        case alt if alt.owner == currentClass => alt.pos
      }
      val pos =
        if (validPositions.isEmpty) currentClass.pos
        else validPositions.maxBy(_.point)

      val kind =
        if (jsInterop.isJSGetter(alts.head)) "getter"
        else if (jsInterop.isJSSetter(alts.head)) "setter"
        else "method"

      val fullKind =
        if (isNonNativeJSClass(currentClass)) kind
        else "exported " + kind

      val displayName = jsName.displayName
      val altsTypesInfo = alts.map(_.tpe.toString).sorted.mkString("\n  ")

      reporter.error(pos,
          s"Cannot disambiguate overloads for $fullKind $displayName with types\n" +
          s"  $altsTypesInfo")
    }

    /**
     * Generate a call to the method [[sym]] while using the formalArguments
     * and potentially the argument array. Also inserts default parameters if
     * required.
     */
    private def genApplyForSym(formalArgsRegistry: FormalArgsRegistry,
        sym: Symbol, static: Boolean): js.Tree = {
      if (isNonNativeJSClass(currentClassSym) &&
          sym.owner != currentClassSym.get) {
        assert(!static, s"nonsensical JS super call in static export of $sym")
        genApplyForSymJSSuperCall(formalArgsRegistry, sym)
      } else {
        genApplyForSymNonJSSuperCall(formalArgsRegistry, sym, static)
      }
    }

    private def genApplyForSymJSSuperCall(
        formalArgsRegistry: FormalArgsRegistry, sym: Symbol): js.Tree = {
      implicit val pos = sym.pos

      assert(!sym.isClassConstructor,
          "Trying to genApplyForSymJSSuperCall for the constructor " +
          sym.fullName)

      val allArgs = formalArgsRegistry.genAllArgsRefsForForwarder()

      val superClass = {
        val superClassSym = currentClassSym.superClass
        if (isNestedJSClass(superClassSym)) {
          js.VarRef(js.LocalIdent(JSSuperClassParamName))(jstpe.AnyType)
        } else {
          js.LoadJSConstructor(encodeClassName(superClassSym))
        }
      }

      val receiver = js.This()(jstpe.AnyType)
      val nameString = genExpr(jsNameOf(sym))

      if (jsInterop.isJSGetter(sym)) {
        assert(allArgs.isEmpty,
            s"getter symbol $sym does not have a getter signature")
        js.JSSuperSelect(superClass, receiver, nameString)
      } else if (jsInterop.isJSSetter(sym)) {
        assert(allArgs.size == 1 && allArgs.head.isInstanceOf[js.Tree],
            s"setter symbol $sym does not have a setter signature")
        js.Assign(js.JSSuperSelect(superClass, receiver, nameString),
            allArgs.head.asInstanceOf[js.Tree])
      } else {
        js.JSSuperMethodCall(superClass, receiver, nameString, allArgs)
      }
    }

    private def genApplyForSymNonJSSuperCall(
        formalArgsRegistry: FormalArgsRegistry, sym: Symbol,
        static: Boolean): js.Tree = {
      implicit val pos = sym.pos

      val varDefs = new mutable.ListBuffer[js.VarDef]

      for ((param, i) <- jsParamInfos(sym).zipWithIndex) {
        val rhs = genScalaArg(sym, i, formalArgsRegistry, param, static, captures = Nil)(
            prevArgsCount => varDefs.take(prevArgsCount).toList.map(_.ref))

        varDefs += js.VarDef(freshLocalIdent("prep" + i), NoOriginalName,
            rhs.tpe, mutable = false, rhs)
      }

      val builtVarDefs = varDefs.result()

      val jsResult = genResult(sym, builtVarDefs.map(_.ref), static)

      js.Block(builtVarDefs :+ jsResult)
    }

    /** Generates a Scala argument from dispatched JavaScript arguments
     *  (unboxing and default parameter handling).
     */
    def genScalaArg(methodSym: Symbol, paramIndex: Int,
        formalArgsRegistry: FormalArgsRegistry, param: JSParamInfo,
        static: Boolean, captures: List[js.Tree])(
        previousArgsValues: Int => List[js.Tree])(
        implicit pos: Position): js.Tree = {

      if (param.repeated) {
        genJSArrayToVarArgs(formalArgsRegistry.genVarargRef(paramIndex))
      } else {
        val jsArg = formalArgsRegistry.genArgRef(paramIndex)
        // Unboxed argument (if it is defined)
        val unboxedArg = fromAny(jsArg, param.tpe)

        if (param.hasDefault) {
          // If argument is undefined and there is a default getter, call it
          val default = genCallDefaultGetter(methodSym, paramIndex,
              param.sym.pos, static, captures)(previousArgsValues)
          js.If(js.BinaryOp(js.BinaryOp.===, jsArg, js.Undefined()),
              default, unboxedArg)(unboxedArg.tpe)
        } else {
          // Otherwise, it is always the unboxed argument
          unboxedArg
        }
      }
    }

    private def genCallDefaultGetter(sym: Symbol, paramIndex: Int,
        paramPos: Position, static: Boolean, captures: List[js.Tree])(
        previousArgsValues: Int => List[js.Tree])(
        implicit pos: Position): js.Tree = {

      val trgSym = {
        if (sym.isClassConstructor) {
          /* Get the companion module class.
           * For inner classes the sym.owner.companionModule can be broken,
           * therefore companionModule is fetched at uncurryPhase.
           *
           * #4465: If owner is a nested class, the linked class is *not* a
           * module value, but another class. In this case we need to call the
           * module accessor on the enclosing class to retrieve this.
           */
          val companionModule = enteringPhase(currentRun.uncurryPhase) {
            sym.owner.companionModule
          }
          companionModule.moduleClass
        } else {
          sym.owner
        }
      }
      val defaultGetter = trgSym.tpe.member(
          nme.defaultGetterName(sym.name, paramIndex + 1))

      assert(defaultGetter.exists,
          s"need default getter for method ${sym.fullName}")
      assert(!defaultGetter.isOverloaded,
          s"found overloaded default getter $defaultGetter")

      val trgTree = {
        if (sym.isClassConstructor || static) {
          if (!trgSym.isLifted) {
            assert(captures.isEmpty, "expected empty captures")
            genLoadModule(trgSym)
          } else {
            assert(captures.size == 1, "expected exactly one capture")

            // Find the module accessor.
            val outer = trgSym.originalOwner
            val name = enteringPhase(currentRun.typerPhase)(trgSym.unexpandedName)

            val modAccessor = outer.info.members.lookupModule(name)
            val receiver = captures.head
            if (isJSType(outer)) {
              genApplyJSClassMethod(receiver, modAccessor, Nil)
            } else {
              genApplyMethodMaybeStatically(receiver, modAccessor, Nil)
            }
          }
        } else {
          js.This()(encodeClassType(trgSym))
        }
      }

      // Pass previous arguments to defaultGetter
      val defaultGetterArgs = previousArgsValues(defaultGetter.tpe.params.size)

      if (isJSType(trgSym)) {
        if (isNonNativeJSClass(defaultGetter.owner)) {
          if (defaultGetter.hasAnnotation(JSOptionalAnnotation))
            js.Undefined()
          else
            genApplyJSClassMethod(trgTree, defaultGetter, defaultGetterArgs)
        } else if (defaultGetter.owner == trgSym) {
          /* We get here if a non-native constructor has a native companion.
           * This is reported on a per-class level.
           */
          assert(sym.isClassConstructor,
              s"got non-constructor method $sym with default method in JS native companion")
          js.Undefined()
        } else {
          reporter.error(paramPos, "When overriding a native method " +
              "with default arguments, the overriding method must " +
              "explicitly repeat the default arguments.")
          js.Undefined()
        }
      } else {
        genApplyMethod(trgTree, defaultGetter, defaultGetterArgs)
      }
    }

    /** Generate the final forwarding call to the exported method. */
    private def genResult(sym: Symbol, args: List[js.Tree],
        static: Boolean)(implicit pos: Position): js.Tree = {
      def receiver = {
        if (static)
          genLoadModule(sym.owner)
        else if (sym.owner == ObjectClass)
          js.This()(jstpe.ClassType(ir.Names.ObjectClass))
        else
          js.This()(encodeClassType(sym.owner))
      }

      if (isNonNativeJSClass(currentClassSym)) {
        assert(sym.owner == currentClassSym.get, sym.fullName)
        ensureResultBoxed(genApplyJSClassMethod(receiver, sym, args), sym)
      } else {
        if (sym.isClassConstructor)
          genNew(currentClassSym, sym, args)
        else if (sym.isPrivate)
          ensureResultBoxed(genApplyMethodStatically(receiver, sym, args), sym)
        else
          ensureResultBoxed(genApplyMethod(receiver, sym, args), sym)
      }
    }

    abstract class Exported(val sym: Symbol,
      // Parameters participating in overload resolution.
      val params: immutable.IndexedSeq[JSParamInfo]) {

      assert(!params.exists(_.capture), "illegal capture params in Exported")

      final def exportArgTypeAt(paramIndex: Int): Type = {
        if (paramIndex < params.length) {
          params(paramIndex).tpe
        } else {
          assert(hasRepeatedParam,
              s"$sym does not have varargs nor enough params for $paramIndex")
          params.last.tpe
        }
      }

      def genBody(formalArgsRegistry: FormalArgsRegistry): js.Tree

      lazy val hasRepeatedParam = params.lastOption.exists(_.repeated)
    }

    private class ExportedSymbol(sym: Symbol, static: Boolean)
        extends Exported(sym, jsParamInfos(sym).toIndexedSeq) {
      def genBody(formalArgsRegistry: FormalArgsRegistry): js.Tree =
        genApplyForSym(formalArgsRegistry, sym, static)
    }
  }

  private sealed abstract class RTTypeTest

  private case class PrimitiveTypeTest(tpe: jstpe.Type, rank: Int)
      extends RTTypeTest

  // scalastyle:off equals.hash.code
  private case class InstanceOfTypeTest(tpe: Type) extends RTTypeTest {
    override def equals(that: Any): Boolean = {
      that match {
        case InstanceOfTypeTest(thatTpe) => tpe =:= thatTpe
        case _ => false
      }
    }
  }
  // scalastyle:on equals.hash.code

  private case object NoTypeTest extends RTTypeTest

  // Very simple O(n²) topological sort for elements assumed to be distinct
  private def topoSortDistinctsWith[A <: AnyRef](coll: List[A])(
      lteq: (A, A) => Boolean): List[A] = {
    @scala.annotation.tailrec
    def loop(coll: List[A], acc: List[A]): List[A] = {
      if (coll.isEmpty) acc
      else if (coll.tail.isEmpty) coll.head :: acc
      else {
        val (lhs, rhs) = coll.span(x => !coll.forall(
            y => (x eq y) || !lteq(x, y)))
        assert(!rhs.isEmpty, s"cycle while ordering $coll")
        loop(lhs ::: rhs.tail, rhs.head :: acc)
      }
    }

    loop(coll, Nil)
  }

  private def typeTestForTpe(tpe: Type): RTTypeTest = {
    tpe match {
      case tpe: ErasedValueType =>
        InstanceOfTypeTest(tpe.valueClazz.typeConstructor)

      case _ =>
        import org.scalajs.ir.Names

        (toIRType(tpe): @unchecked) match {
          case jstpe.AnyType => NoTypeTest

          case jstpe.NoType      => PrimitiveTypeTest(jstpe.UndefType, 0)
          case jstpe.BooleanType => PrimitiveTypeTest(jstpe.BooleanType, 1)
          case jstpe.CharType    => PrimitiveTypeTest(jstpe.CharType, 2)
          case jstpe.ByteType    => PrimitiveTypeTest(jstpe.ByteType, 3)
          case jstpe.ShortType   => PrimitiveTypeTest(jstpe.ShortType, 4)
          case jstpe.IntType     => PrimitiveTypeTest(jstpe.IntType, 5)
          case jstpe.LongType    => PrimitiveTypeTest(jstpe.LongType, 6)
          case jstpe.FloatType   => PrimitiveTypeTest(jstpe.FloatType, 7)
          case jstpe.DoubleType  => PrimitiveTypeTest(jstpe.DoubleType, 8)

          case jstpe.ClassType(Names.BoxedUnitClass)   => PrimitiveTypeTest(jstpe.UndefType, 0)
          case jstpe.ClassType(Names.BoxedStringClass) => PrimitiveTypeTest(jstpe.StringType, 9)
          case jstpe.ClassType(_)                      => InstanceOfTypeTest(tpe)

          case jstpe.ArrayType(_) => InstanceOfTypeTest(tpe)
        }
    }
  }

  // Group-by that does not rely on hashCode(), only equals() - O(n²)
  private def groupByWithoutHashCode[A, B](
      coll: List[A])(f: A => B): List[(B, List[A])] = {

    import scala.collection.mutable.ArrayBuffer
    val m = new ArrayBuffer[(B, List[A])]
    m.sizeHint(coll.length)

    for (elem <- coll) {
      val key = f(elem)
      val index = m.indexWhere(_._1 == key)
      if (index < 0) m += ((key, List(elem)))
      else m(index) = (key, elem :: m(index)._2)
    }

    m.toList
  }

  private def genThrowTypeError(msg: String = "No matching overload")(
      implicit pos: Position): js.Tree = {
    js.Throw(js.StringLiteral(msg))
  }

  class FormalArgsRegistry(minArgc: Int, needsRestParam: Boolean) {
    private val fixedParamNames: scala.collection.immutable.IndexedSeq[LocalName] =
      (0 until minArgc).toIndexedSeq.map(_ => freshLocalIdent("arg")(NoPosition).name)

    private val restParamName: LocalName =
      if (needsRestParam) freshLocalIdent("rest")(NoPosition).name
      else null

    def genFormalArgs()(implicit pos: Position): (List[js.ParamDef], Option[js.ParamDef]) = {
      val fixedParamDefs = fixedParamNames.toList.map { paramName =>
        js.ParamDef(js.LocalIdent(paramName), NoOriginalName, jstpe.AnyType,
            mutable = false)
      }

      val restParam = {
        if (needsRestParam) {
          Some(js.ParamDef(js.LocalIdent(restParamName),
              NoOriginalName, jstpe.AnyType, mutable = false))
        } else {
          None
        }
      }

      (fixedParamDefs, restParam)
    }

    def genArgRef(index: Int)(implicit pos: Position): js.Tree = {
      if (index < minArgc)
        js.VarRef(js.LocalIdent(fixedParamNames(index)))(jstpe.AnyType)
      else
        js.JSSelect(genRestArgRef(), js.IntLiteral(index - minArgc))
    }

    def genVarargRef(fixedParamCount: Int)(implicit pos: Position): js.Tree = {
      val restParam = genRestArgRef()
      assert(fixedParamCount >= minArgc,
          s"genVarargRef($fixedParamCount) with minArgc = $minArgc at $pos")
      if (fixedParamCount == minArgc) {
        restParam
      } else {
        js.JSMethodApply(restParam, js.StringLiteral("slice"),
            List(js.IntLiteral(fixedParamCount - minArgc)))
      }
    }

    def genRestArgRef()(implicit pos: Position): js.Tree = {
      assert(needsRestParam,
          s"trying to generate a reference to non-existent rest param at $pos")
      js.VarRef(js.LocalIdent(restParamName))(jstpe.AnyType)
    }

    def genAllArgsRefsForForwarder()(implicit pos: Position): List[js.TreeOrJSSpread] = {
      val fixedArgRefs = fixedParamNames.toList.map { paramName =>
        js.VarRef(js.LocalIdent(paramName))(jstpe.AnyType)
      }

      if (needsRestParam) {
        val restArgRef = js.VarRef(js.LocalIdent(restParamName))(jstpe.AnyType)
        fixedArgRefs :+ js.JSSpread(restArgRef)
      } else {
        fixedArgRefs
      }
    }
  }
}
