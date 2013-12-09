package miniboxing.plugin

import scala.tools.nsc._
import scala.tools.nsc.typechecker._
import scala.tools.nsc.symtab.Flags
import scala.tools.nsc.transform.TypingTransformers
import scala.collection.{ mutable, immutable }
import scala.collection.mutable.Set
import scala.collection.mutable.{ Map => MMap }
import util.returning
import scala.collection.mutable.ListBuffer

trait MiniboxAdaptTreeTransformer extends TypingTransformers {
  self: MiniboxAdaptComponent =>

  import minibox._
  import global._

  class AdaptPhase(prev: Phase) extends StdPhase(prev) {
    override def name = MiniboxAdaptTreeTransformer.this.phaseName
    def apply(unit: CompilationUnit): Unit = {

      object TreeAdapter extends {
        val global: MiniboxAdaptTreeTransformer.this.global.type = MiniboxAdaptTreeTransformer.this.global
      } with TreeAdapters

      // boil frog, boil!
      global.addAnnotationChecker(StorageAnnotationChecker)

      val tree = TreeAdapter.adapt(unit)
      tree.foreach(node => assert(node.tpe != null, node))
    }
  }

  abstract class TreeAdapters extends Analyzer {
//    val global: Global
//    val minibox: MiniboxAdaptTreeTransformer { val global: TreeAdapters.this.global.type }
//
//    import global._
//    import minibox._
    import global._

    private def wrap[T](msg: => Any)(body: => Unit) {
      try body
      catch { case x: Throwable =>
        Console.println("Caught " + x)
        Console.println(msg)
        x.printStackTrace
      }
    }

    def adapt(unit: CompilationUnit): Tree = {
      val context = rootContext(unit)
      val checker = new TreeAdapter(context)
      unit.body = checker.typed(unit.body)
      unit.body
    }

    override def newTyper(context: Context): Typer =
      new TreeAdapter(context)

    class TreeAdapter(context0: Context) extends Typer(context0) {
      override protected def finishMethodSynthesis(templ: Template, clazz: Symbol, context: Context): Template =
        templ

      override def typed(tree: Tree, mode: Int, pt: Type): Tree =
        tree match {
          case EmptyTree | TypeTree() =>
            super.typed(tree, mode, pt)
          case _ if tree.tpe != null  =>
//            println("TREE: " + tree)
            val oldTree = tree.duplicate
            val oldTpe = tree.tpe
            tree.tpe = null
            val res: Tree = silent(_.typed(tree, mode, pt)) match {
              case SilentTypeError(err) =>
                tree.tpe = oldTpe
                println(oldTpe + " vs " + pt)
                val newTpe = pt
                val hAnnot1 = oldTpe.dealiasWiden.hasAnnotation(StorageClass.asInstanceOf[Symbol])
                val hAnnot2 = newTpe.dealiasWiden.hasAnnotation(StorageClass.asInstanceOf[Symbol])
                if (hAnnot1 && !hAnnot2) {
                  //println(marker_minibox2box.tpe)
                  super.typed(gen.mkMethodCall(marker_minibox2box.asInstanceOf[Symbol], List(oldTree.tpe.typeSymbol.tpeHK), List(oldTree)), mode, pt)
                }
                else if (!hAnnot1 && hAnnot2) {
                  //println(marker_box2minibox.tpe)
                  super.typed(gen.mkMethodCall(marker_box2minibox.asInstanceOf[Symbol], List(oldTree.tpe.typeSymbol.tpeHK), List(oldTree)), mode, pt)
                }
                else {
                  println("Don't know how to adapt:")
                  println(tree)
                  ???
                }
              case SilentResultValue(res: Tree) =>
                assert(res.tpe != null)
                res
//                res match {
//                  case tree2: Tree =>
//                    // adaptation is done here:
//                    val newTpe = if (tree2.tpe != null) tree2.tpe else NoType
//                    if (hAnnot1 != hAnnot2)
//                      println(s"Mismatch: $oldTpe vs $newTpe:\n$tree\n")
//                    tree2
//              }
//              case SilentResultValue(t: Tree) => t
//              case t: Tree => t
            }
//            println(res)
            res
          case _ =>
            val tree2 = super.typed(tree, mode, pt)
            assert(tree2.tpe != null)
            tree2
        }
    }
  }
}

