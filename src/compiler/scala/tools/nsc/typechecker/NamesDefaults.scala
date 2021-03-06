/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author  Martin Odersky
 */

package scala.tools.nsc
package typechecker

import symtab.Flags._
import scala.collection.mutable

/**
 *  @author Lukas Rytz
 *  @version 1.0
 */
trait NamesDefaults { self: Analyzer =>

  import global._
  import definitions._

  val defaultParametersOfMethod =
    perRunCaches.newWeakMap[Symbol, Set[Symbol]]() withDefaultValue Set()

  case class NamedApplyInfo(qual: Option[Tree], targs: List[Tree],
                            vargss: List[List[Tree]], blockTyper: Typer)
  val noApplyInfo = NamedApplyInfo(None, Nil, Nil, null)

  def nameOf(arg: Tree) = arg match {
    case AssignOrNamedArg(Ident(name), rhs) => Some(name)
    case _ => None
  }
  def isNamed(arg: Tree) = nameOf(arg).isDefined

  /** @param pos maps indicies from old to new */ 
  def reorderArgs[T: ClassManifest](args: List[T], pos: Int => Int): List[T] = {
    val res = new Array[T](args.length)
    // (hopefully) faster than zipWithIndex
    (0 /: args) { case (index, arg) => res(pos(index)) = arg; index + 1 }
    res.toList
  }
  
  /** @param pos maps indicies from new to old (!) */
  def reorderArgsInv[T: ClassManifest](args: List[T], pos: Int => Int): List[T] = {
    val argsArray = args.toArray
    val res = new mutable.ListBuffer[T]
    for (i <- 0 until argsArray.length)
      res += argsArray(pos(i))
    res.toList
  }

  /** returns `true` if every element is equal to its index */
  def isIdentity(a: Array[Int]) = (0 until a.length).forall(i => a(i) == i)

  /**
   * Transform a function application into a Block, and assigns typer.context
   * .namedApplyBlockInfo to the new block as side-effect. If tree has the form
   *    Apply(fun, args)
   * first the function "fun" (which might be an application itself!) is transformed into a
   * block of the form
   *   {
   *     val qual$1 = qualifier_of_fun
   *     val x$1 = arg_1_of_fun
   *     ...
   *     val x$n = arg_n_of_fun
   *     qual$1.fun[targs](x$1, ...)...(..., x$n)
   *   }
   * then for each argument in args, a value is created and entered into the block. finally
   * the application expression of the block is updated.
   *   {
   *     val qual$1 = ..
   *     ...
   *     val x$n = ...
   *  >  val qual$n+1 = arg(1)
   *  >  ...
   *  >  val qual$n+m = arg(m)
   *  >  qual$1.fun[targs](x$1, ...)...(..., x$n)(x$n+1, ..., x$n+m)
   *   }
   * 
   * @param typer the typer calling this method; this method calls
   *    typer.doTypedApply
   * @param mode the mode to use for calling typer.doTypedApply
   * @param pt the expected type for calling typer.doTypedApply
   * 
   * @param tree: the function application tree
   * @argPos: a function mapping arguments from their current position to the
   *   position specified by the method type. example:
   *    def foo(a: Int, b: String)
   *    foo(b = "1", a = 2)
   *  calls
   *    transformNamedApplication(Apply(foo, List("1", 2), { 0 => 1, 1 => 0 })
   * 
   *  @return the transformed application (a Block) together with the NamedApplyInfo.
   *     if isNamedApplyBlock(tree), returns the existing context.namedApplyBlockInfo
   */
  def transformNamedApplication(typer: Typer, mode: Int, pt: Type)
                               (tree: Tree, argPos: Int => Int): Tree = {
    import typer._
    import typer.infer._
    val context = typer.context
    import context.unit

    /**
     * Transform a function into a block, and passing context.namedApplyBlockInfo to
     * the new block as side-effect.
     *
     * `baseFun` is typed, the resulting block must be typed as well.
     *
     * Fun is transformed in the following way:
     *  - Ident(f)                                    ==>  Block(Nil, Ident(f))
     *  - Select(qual, f) if (qual is stable)         ==>  Block(Nil, Select(qual, f))
     *  - Select(qual, f) otherwise                   ==>  Block(ValDef(qual$1, qual), Select(qual$1, f))
     *  - TypeApply(fun, targs)                       ==>  Block(Nil or qual$1, TypeApply(fun, targs))
     *  - Select(New(TypeTree()), <init>)             ==>  Block(Nil, Select(New(TypeTree()), <init>))
     *  - Select(New(Select(qual, typeName)), <init>) ==>  Block(Nil, Select(...))     NOTE: qual must be stable in a `new`
     */
    def baseFunBlock(baseFun: Tree): Tree = {
      val isConstr = baseFun.symbol.isConstructor
      val blockTyper = newTyper(context.makeNewScope(tree, context.owner))

      // baseFun1: extract the function from a potential TypeApply
      // funTargs: type arguments on baseFun, used to reconstruct TypeApply in blockWith(Out)Qualifier
      // defaultTargs: type arguments to be used for calling defaultGetters. If the type arguments are given
      //   in the source code, re-use them for default getter. Otherwise infer the default getter's t-args.
      val (baseFun1, funTargs, defaultTargs) = baseFun match {
        case TypeApply(fun, targs) =>
          val targsInSource =
            if (targs.forall(a => context.undetparams contains a.symbol)) Nil
            else targs
          (fun, targs, targsInSource)

        case Select(New(tpt @ TypeTree()), _) if isConstr =>
          val targsInSource = tpt.tpe match {
            case TypeRef(pre, sym, args)
            if (!args.forall(a => context.undetparams contains a.typeSymbol)) =>
              args.map(TypeTree(_))
            case _ =>
              Nil
          }
          (baseFun, Nil, targsInSource)

        case Select(TypeApply(New(TypeTree()), targs), _) if isConstr =>
          val targsInSource =
            if (targs.forall(a => context.undetparams contains a.symbol)) Nil
            else targs
          (baseFun, Nil, targsInSource)

        case _ => (baseFun, Nil, Nil)
      }

      // never used for constructor calls, they always have a stable qualifier
      def blockWithQualifier(qual: Tree, selected: Name) = {
        val sym = blockTyper.context.owner.newValue(qual.pos, unit.freshTermName("qual$"))
                            .setInfo(qual.tpe)
        blockTyper.context.scope.enter(sym)
        val vd = atPos(sym.pos)(ValDef(sym, qual).setType(NoType))

        var baseFunTransformed = atPos(baseFun.pos.makeTransparent) {
          // don't use treeCopy: it would assign opaque position.
          val f = Select(gen.mkAttributedRef(sym), selected)
                   .setType(baseFun1.tpe).setSymbol(baseFun1.symbol)
          if (funTargs.isEmpty) f
          else TypeApply(f, funTargs).setType(baseFun.tpe)
        }

        val b = Block(List(vd), baseFunTransformed)
                  .setType(baseFunTransformed.tpe).setPos(baseFun.pos)

        val defaultQual = Some(atPos(qual.pos.focus)(gen.mkAttributedRef(sym)))
        context.namedApplyBlockInfo =
          Some((b, NamedApplyInfo(defaultQual, defaultTargs, Nil, blockTyper)))
        b
      }

      def blockWithoutQualifier(defaultQual: Option[Tree]) = {
        val b = atPos(baseFun.pos)(Block(Nil, baseFun).setType(baseFun.tpe))
        context.namedApplyBlockInfo =
          Some((b, NamedApplyInfo(defaultQual, defaultTargs, Nil, blockTyper)))
        b
      }

      def moduleQual(pos: Position, classType: Type) = {
        // prefix does 'normalize', which fixes #3384
        val pre = classType.prefix
        if (pre == NoType) {
          None
        } else {
          val module = companionModuleOf(baseFun.symbol.owner, context)
          if (module == NoSymbol) None
          else {
            val ref = atPos(pos.focus)(gen.mkAttributedRef(pre, module))
            if (module.isStable && pre.isStable)    // fixes #4524. the type checker does the same for
              ref.setType(singleType(pre, module))  // typedSelect, it calls "stabilize" on the result.
            Some(ref)
          }
        }
      }

      baseFun1 match {
        // constructor calls

        case Select(New(tp @ TypeTree()), _) if isConstr =>
          // 'moduleQual' fixes #3338. Same qualifier for selecting the companion object as for the class.
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))
        case Select(TypeApply(New(tp @ TypeTree()), _), _) if isConstr =>
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))

        case Select(New(tp @ Ident(_)), _) if isConstr =>
          // 'moduleQual' fixes #3344
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))
        case Select(TypeApply(New(tp @ Ident(_)), _), _) if isConstr =>
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))

        case Select(New(tp @ Select(qual, _)), _) if isConstr =>
          // in `new q.C()', q is always stable
          assert(treeInfo.isPureExpr(qual), qual)
          // 'moduleQual' fixes #2057
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))
        case Select(TypeApply(New(tp @ Select(qual, _)), _), _) if isConstr =>
          assert(treeInfo.isPureExpr(qual), qual)
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))

        // super constructor calls
        case Select(sp @ Super(_, _), _) if isConstr =>
          // 'moduleQual' fixes #3207. selection of the companion module of the
          // superclass needs to have the same prefix as the the superclass.
          blockWithoutQualifier(moduleQual(baseFun.pos, sp.symbol.tpe.parents.head))

        // self constructor calls (in secondary constructors)
        case Select(tp, name) if isConstr =>
          assert(treeInfo.isPureExpr(tp), tp)
          blockWithoutQualifier(moduleQual(tp.pos, tp.tpe))

        // other method calls

        case Ident(_) =>
          blockWithoutQualifier(None)

        case Select(qual, name) =>
          if (treeInfo.isPureExpr(qual))
            blockWithoutQualifier(Some(qual.duplicate))
          else
            blockWithQualifier(qual, name)
      }
    }

    /**
     * For each argument (arg: T), create a local value
     *  x$n: T = arg
     *
     * assumes "args" are typed. owner of the definitions in the block is the owner of
     * the block (see typedBlock), but the symbols have to be entered into the block's scope.
     *
     * For by-name parameters, create a value
     *  x$n: () => T = () => arg
     */
    def argValDefs(args: List[Tree], paramTypes: List[Type], blockTyper: Typer): List[ValDef] = {
      val context = blockTyper.context
      val symPs = (args, paramTypes).zipped map ((arg, tpe) => {
        val byName = isByNameParamType(tpe)
        val (argTpe, repeated) =
          if (isScalaRepeatedParamType(tpe)) arg match {
            case Typed(expr, Ident(tpnme.WILDCARD_STAR)) =>
              (expr.tpe, true)
            case _ =>
              (seqType(arg.tpe), true)
          } else (arg.tpe, false)
        val s = context.owner.newValue(arg.pos, unit.freshTermName("x$"))
        val valType = if (byName) functionType(List(), argTpe)
                      else if (repeated) argTpe
                      else argTpe
        s.setInfo(valType)
        (context.scope.enter(s), byName, repeated)
      })
      (symPs, args).zipped map {
        case ((sym, byName, repeated), arg) =>
          val body =
            if (byName) {
              val res = blockTyper.typed(Function(List(), arg))
              new ChangeOwnerTraverser(context.owner, res.symbol) traverse arg // fixes #2290
              res
            } else {
              new ChangeOwnerTraverser(context.owner, sym) traverse arg // fixes #4502
              if (repeated) arg match {
                case Typed(expr, Ident(tpnme.WILDCARD_STAR)) =>
                  expr
                case _ =>
                  val factory = Select(gen.mkAttributedRef(SeqModule), nme.apply)
                  blockTyper.typed(Apply(factory, List(resetLocalAttrs(arg))))
              } else arg
            }
          atPos(body.pos)(ValDef(sym, body).setType(NoType))
      }
    }

    // begin transform
    if (isNamedApplyBlock(tree)) {
      context.namedApplyBlockInfo.get._1
    } else tree match {
      // `fun` is typed. `namelessArgs` might be typed or not, if they are types are kept.
      case Apply(fun, namelessArgs) =>
        val transformedFun = transformNamedApplication(typer, mode, pt)(fun, x => x)
        if (transformedFun.isErroneous) setError(tree)
        else {
          assert(isNamedApplyBlock(transformedFun), transformedFun)
          val NamedApplyInfo(qual, targs, vargss, blockTyper) =
            context.namedApplyBlockInfo.get._2
          val existingBlock @ Block(stats, funOnly) = transformedFun

          // type the application without names; put the arguments in definition-site order
          val typedApp = doTypedApply(tree, funOnly, reorderArgs(namelessArgs, argPos), mode, pt)

          if (typedApp.tpe.isError) setError(tree)
          else typedApp match {
            // Extract the typed arguments, restore the call-site evaluation order (using
            // ValDef's in the block), change the arguments to these local values.
            case Apply(expr, typedArgs) =>
              // typedArgs: definition-site order
              val formals = formalTypes(expr.tpe.paramTypes, typedArgs.length, false, false)
              // valDefs: call-site order
              val valDefs = argValDefs(reorderArgsInv(typedArgs, argPos),
                                       reorderArgsInv(formals, argPos),
                                       blockTyper)
              // refArgs: definition-site order again
              val refArgs = (reorderArgs(valDefs, argPos), formals).zipped map ((vDef, tpe) => {
                val ref = gen.mkAttributedRef(vDef.symbol)
                atPos(vDef.pos.focus) {
                  // for by-name parameters, the local value is a nullary function returning the argument
                  if (isByNameParamType(tpe)) Apply(ref, List())
                  else if (isScalaRepeatedParamType(tpe)) Typed(ref, Ident(tpnme.WILDCARD_STAR))
                  else ref
                }
              })
              // cannot call blockTyper.typedBlock here, because the method expr might be partially applied only
              val res = blockTyper.doTypedApply(tree, expr, refArgs, mode, pt)
              res.setPos(res.pos.makeTransparent)
              val block = Block(stats ::: valDefs, res).setType(res.tpe).setPos(tree.pos)
              context.namedApplyBlockInfo =
                Some((block, NamedApplyInfo(qual, targs, vargss ::: List(refArgs), blockTyper)))
              block
          }
        }

      case baseFun => // also treats "case TypeApply(fun, targs)" and "case Select(New(..), <init>)"
        baseFunBlock(baseFun)

    }
  }

  def missingParams[T](args: List[T], params: List[Symbol], argName: T => Option[Name] = nameOf _): (List[Symbol], Boolean) = {
    val namedArgs = args.dropWhile(arg => {
      val n = argName(arg)
      n.isEmpty || params.forall(p => p.name != n.get)
    })
    val namedParams = params.drop(args.length - namedArgs.length)
    // missing: keep those with a name which doesn't exist in namedArgs
    val missingParams = namedParams.filter(p => namedArgs.forall(arg => {
      val n = argName(arg)
      n.isEmpty || n.get != p.name
    }))
    val allPositional = missingParams.length == namedParams.length
    (missingParams, allPositional)
  }

  /**
   * Extend the argument list `givenArgs` with default arguments. Defaults are added
   * as named arguments calling the corresponding default getter.
   *
   * Example: given
   *   def foo(x: Int = 2, y: String = "def")
   *   foo(y = "lt")
   * the argument list (y = "lt") is transformed to (y = "lt", x = foo$default$1())
   */
  def addDefaults(givenArgs: List[Tree], qual: Option[Tree], targs: List[Tree],
                  previousArgss: List[List[Tree]], params: List[Symbol],
                  pos: util.Position, context: Context): (List[Tree], List[Symbol]) = {
    if (givenArgs.length < params.length) {
      val (missing, positional) = missingParams(givenArgs, params)
      if (missing forall (_.hasDefaultFlag)) {
        val defaultArgs = missing flatMap (p => {
          val defGetter = defaultGetter(p, context)
          if (defGetter == NoSymbol) None // prevent crash in erroneous trees, #3649
          else {
            var default1 = qual match {
              case Some(q) => gen.mkAttributedSelect(q.duplicate, defGetter)
              case None    => gen.mkAttributedRef(defGetter)

            }
            default1 = if (targs.isEmpty) default1
                       else TypeApply(default1, targs.map(_.duplicate))
            val default2 = (default1 /: previousArgss)((tree, args) =>
              Apply(tree, args.map(_.duplicate)))
            Some(atPos(pos) {
              if (positional) default2
              else AssignOrNamedArg(Ident(p.name), default2)
            })
          }
        })
        (givenArgs ::: defaultArgs, Nil)
      } else (givenArgs, missing filterNot (_.hasDefaultFlag))
    } else (givenArgs, Nil)
  }

  /**
   * For a parameter with default argument, find the method symbol of
   * the default getter.
   */
  def defaultGetter(param: Symbol, context: Context): Symbol = {
    val i = param.owner.paramss.flatten.indexWhere(p => p.name == param.name) + 1
    if (i > 0) {
      val defGetterName = nme.defaultGetterName(param.owner.name, i)
      if (param.owner.isConstructor) {
        val mod = companionModuleOf(param.owner.owner, context)
        mod.info.member(defGetterName)
      }
      else {
        // isClass also works for methods in objects, owner is the ModuleClassSymbol
        if (param.owner.owner.isClass) {
          // .toInterface: otherwise we get the method symbol of the impl class
          param.owner.owner.toInterface.info.member(defGetterName)
        } else {
          // the owner of the method is another method. find the default
          // getter in the context.
          context.lookup(defGetterName, param.owner.owner)
        }
      }
    } else NoSymbol
  }

  /**
   * Removes name assignments from args. Additionally, returns an array mapping
   * argument indicies from call-site-order to definition-site-order.
   *
   * Verifies that names are not specified twice, positional args don't appear
   * after named ones.
   */
  def removeNames(typer: Typer)(args: List[Tree], params: List[Symbol]): (List[Tree], Array[Int]) = {
    import typer.infer.errorTree

    // maps indicies from (order written by user) to (order of definition)
    val argPos = (new Array[Int](args.length)) map (x => -1)
    var positionalAllowed = true
    val namelessArgs = for ((arg, index) <- (args.zipWithIndex)) yield arg match {
      case a @ AssignOrNamedArg(Ident(name), rhs) =>
        val (pos, newName) = paramPos(params, name)
        newName.foreach(n => {
          typer.context.unit.deprecationWarning(arg.pos, "the parameter name "+ name +" has been deprecated. Use "+ n +" instead.")
        })
        if (pos == -1) {
          if (positionalAllowed) {
            argPos(index) = index
            // prevent isNamed from being true when calling doTypedApply recursively,
            // treat the arg as an assignment of type Unit
            Assign(a.lhs, rhs).setPos(arg.pos)
          } else {
            errorTree(arg, "unknown parameter name: "+ name)
          }
        } else if (argPos contains pos) {
          errorTree(arg, "parameter specified twice: "+ name)
        } else {
          // for named arguments, check whether the assignment expression would
          // typecheck. if it does, report an ambiguous error.
          val param = params(pos)
          val paramtpe = params(pos).tpe.cloneInfo(param)
          // replace type parameters by wildcard. in the below example we need to
          // typecheck (x = 1) with wildcard (not T) so that it succeeds.
          //   def f[T](x: T) = x
          //   var x = 0
          //   f(x = 1)   <<  "x = 1" typechecks with expected type WildcardType
          val udp = typer.context.extractUndetparams()
          val subst = new SubstTypeMap(udp, udp map (_ => WildcardType)) {
            override def apply(tp: Type): Type = tp match {
              case TypeRef(_, ByNameParamClass, List(arg))  => super.apply(arg)
              case _ => super.apply(tp)
            }
          }
          val reportAmbiguousErrors = typer.context.reportAmbiguousErrors
          typer.context.reportAmbiguousErrors = false

          var variableNameClash = false
          val typedAssign = try {
            typer.silent(_.typed(arg, subst(paramtpe)))
          } catch {
            // `silent` only catches and returns TypeErrors which are not
            // CyclicReferences.  Fix for #3685
            case cr @ CyclicReference(sym, info) if sym.name == param.name =>
              if (sym.isVariable || sym.isGetter && sym.accessed.isVariable) {
                // named arg not allowed
                variableNameClash = true
                typer.context.error(sym.pos, 
                  "%s definition needs %s because '%s' is used as a named argument in its body.".format(
                    "variable",   // "method"
                    "type",       // "result type"
                    sym.name
                  )
                )
                typer.infer.setError(arg)
              }
              else cr
          }

          def applyNamedArg = {
            // if the named argument is on the original parameter
            // position, positional after named is allowed.
            if (index != pos)
              positionalAllowed = false
            argPos(index) = pos
            rhs
          }

          val res = typedAssign match {
            case _: TypeError => applyNamedArg

            case t: Tree =>
              if (t.isErroneous && !variableNameClash) {
                applyNamedArg
              } else if (t.isErroneous) {
                t // name clash with variable. error was already reported above.
              } else {
                // This throws an exception which is caught in `tryTypedApply` (as it
                // uses `silent`) - unfortunately, tryTypedApply recovers from the
                // exception if you use errorTree(arg, ...) and conforms is allowed as
                // a view (see tryImplicit in Implicits) because it tries to produce a
                // new qualifier (if the old one was P, the new one will be
                // conforms.apply(P)), and if that works, it pretends nothing happened.
                //
                // To make sure tryTypedApply fails, we would like to pass EmptyTree
                // instead of arg, but can't do that because eventually setType(ErrorType)
                // is called, and EmptyTree can only be typed NoType.  Thus we need to
                // disable conforms as a view...
                errorTree(arg, "reference to "+ name +" is ambiguous; it is both, a parameter\n"+
                               "name of the method and the name of a variable currently in scope.")
              }
          }

          typer.context.reportAmbiguousErrors = reportAmbiguousErrors
          //@M note that we don't get here when an ambiguity was detected (during the computation of res),
          // as errorTree throws an exception
          typer.context.undetparams = udp
          res
        }
      case _ =>
        argPos(index) = index
        if (positionalAllowed) arg
        else errorTree(arg, "positional after named argument.")
    }
    (namelessArgs, argPos)
  }

  /**
   * Returns
   *  - the position of the parameter named `name`
   *  - optionally, if `name` is @deprecatedName, the new name
   */
  def paramPos(params: List[Symbol], name: Name): (Int, Option[Name]) = {
    var i = 0
    var rest = params
    while (!rest.isEmpty) {
      val p = rest.head
      if (!p.isSynthetic) {
        if (p.name == name) return (i, None)
        if (deprecatedName(p) == Some(name)) return (i, Some(p.name))
      }
      i += 1
      rest = rest.tail
    }
    (-1, None)
  }

  def deprecatedName(sym: Symbol): Option[Name] =
    sym.getAnnotation(DeprecatedNameAttr).map(ann => (ann.args(0): @unchecked) match {
      case Apply(fun, Literal(str) :: Nil) if (fun.symbol == Symbol_apply) =>
        newTermName(str.stringValue)
    })
}
