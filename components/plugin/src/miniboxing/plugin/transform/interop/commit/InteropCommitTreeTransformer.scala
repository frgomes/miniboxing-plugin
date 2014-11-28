//
//     _____   .__         .__ ___.                    .__ scala-miniboxing.org
//    /     \  |__|  ____  |__|\_ |__    ____  ___  ___|__|  ____     ____
//   /  \ /  \ |  | /    \ |  | | __ \  /  _ \ \  \/  /|  | /    \   / ___\
//  /    Y    \|  ||   |  \|  | | \_\ \(  <_> ) >    < |  ||   |  \ / /_/  >
//  \____|__  /|__||___|  /|__| |___  / \____/ /__/\_ \|__||___|  / \___  /
//          \/          \/          \/               \/         \/ /_____/
// Copyright (c) 2012-2014 Scala Team, École polytechnique fédérale de Lausanne
//
// Authors:
//    * Vlad Ureche
//
package miniboxing
package plugin
package transform
package interop
package commit

import scala.tools.nsc.transform.InfoTransform
import scala.tools.nsc.transform.TypingTransformers

trait InteropCommitTreeTransformer extends TypingTransformers {
  self: InteropCommitComponent =>

  import global._
  import definitions._
  import interop._

  def newTransformer(unit: CompilationUnit) = new Transformer {
    val specTrans = new InteropTreeTransformer(unit)
    override def transform(tree: Tree): Tree = {
      val res = afterInteropCommit(checkNoMbFunction(specTrans.transform(tree)))
      // clear the two functions
      FunctionsObjectSymbol.info.decls unlink marker_fun2mbfun
      FunctionsObjectSymbol.info.decls unlink marker_mbfun2fun
      res
    }
  }

  def checkNoMbFunction(tree: Tree) = {
    for (t <- tree)
      assert(noMbFunctionAnnot(t.tpe), t + ": " + t.tpe)
    tree
  }

  def noMbFunctionAnnot(t: Type): Boolean = {
    var hasStorage = false
    new TypeMap {
      def apply(tp: Type): Type = mapOver(tp)
      override def mapOver(tp: Type): Type = tp match {
        case _ if tp hasAnnotation(mbFunctionClass) =>
          hasStorage = true
          tp
        case _ =>
          super.mapOver(tp)
      }
    }.apply(t)

//    !hasStorage
    true
  }

  class CoercionExtractor {
    def unapply(tree: Tree, sym: Symbol): Option[(Tree, Type)] = tree match {
      case Apply(TypeApply(fun, List(targ)), List(inner)) if fun.symbol == sym => Some((inner, targ.tpe))
      case _ => None
    }
  }

  object MbFunToFun extends CoercionExtractor {
    def unapply(tree: Tree): Option[(Tree, Type)] = unapply(tree, marker_mbfun2fun).map({ case (tree, tpe) => (tree, tpe) })
  }

  object FunToMbFun extends CoercionExtractor {
    def unapply(tree: Tree): Option[(Tree, Type)] = unapply(tree, marker_fun2mbfun).map({ case (tree, tpe) => (tree, tpe) })
  }

  class InteropTreeTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {

    val applyUpdate =
      Map(
        FunctionClass(0).tpe.member(newTermName("apply")) -> MiniboxedFunction0Class.tpe.member(newTermName("apply")),
        FunctionClass(1).tpe.member(newTermName("apply")) -> MiniboxedFunction1Class.tpe.member(newTermName("apply")),
        FunctionClass(2).tpe.member(newTermName("apply")) -> MiniboxedFunction2Class.tpe.member(newTermName("apply"))
      )

    val applySymbols = applyUpdate.keySet.toList

    override def transform(tree0: Tree): Tree = {
      val oldTpe = tree0.tpe
      val newTpe = deepTransformation(oldTpe)
//      println(oldTpe + " ==> " + newTpe)

      // force new info on the symbol
      if (tree0.hasSymbolField)
        tree0.symbol.info

      val tree1 =
        tree0 match {

          // update the type for transformed anonymous functions
          case cdef@ClassDef(mods, name, tparams, tpl@Template(parents, self, body)) if { cdef.symbol.info; transformedAnonFunctions(cdef.symbol) } =>
            val parents2 = cdef.symbol.info.parents.map(TypeTree(_))
            val impl2 = treeCopy.Template(tpl, parents2, self, transformStats(body, cdef.symbol))
            val cdef2 = treeCopy.ClassDef(cdef, mods, name, tparams, impl2)
            localTyper.typed(cdef2)

          // update the constructor for transformed anonymous functions
          case FunToMbFun(Typed(ctor @ Apply(Select(New(tpt), nme.CONSTRUCTOR), Nil), _), targ) if { tpt.symbol.info; transformedAnonFunctions(tpt.symbol) } =>
            val ctor2 = Apply(Select(New(tpt), nme.CONSTRUCTOR), Nil)
            localTyper.typed(ctor2)

          // update the super constructor
          case Apply(Select(Super(qual, _), nme.CONSTRUCTOR), Nil) if transformedAnonFunctions(qual.symbol) =>
            val sup2 = Apply(Select(Super(This(qual.symbol), tpnme.EMPTY), nme.CONSTRUCTOR), Nil)
            val sup3 = localTyper.typed(sup2)
            sup3

          case FunToMbFun(tree, targ) =>
            val (conversion, targs) =
              targ match {
                case TypeRef(_, Function0Class, targs) => (function0_bridge, targs)
                case TypeRef(_, Function1Class, targs) => (function1_bridge, targs)
                case TypeRef(_, Function2Class, targs) => (function2_bridge, targs)
              }

            val tree1 = gen.mkMethodCall(conversion, targs, List(transform(tree)))
//            println(tree0 + " ==> " + tree1)
            localTyper.typed(tree1)

          case MbFunToFun(tree, targ) =>
            val tree1 = gen.mkMethodCall(Select(transform(tree), newTermName("f")), Nil)
//            println(tree0 + " ==> " + tree1)
            localTyper.typed(tree1)

          case Select(MbFunToFun(fun, targ), apply) if applySymbols.contains(tree0.symbol) =>
            val tree1 = gen.mkAttributedSelect(transform(fun), applyUpdate(tree0.symbol))
            val res = localTyper.typedOperator(tree1)
//            println(tree0 + " ==> " + res)
//            println(res.symbol.defString)
//            println(res.symbol.owner)
             res

          case _ =>
            super.transform(tree0)
        }

      tree1.setType(newTpe)
    }
  }
}