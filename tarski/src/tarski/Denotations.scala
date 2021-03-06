/* Denotations: Fully interpreted intermediate representation
 *
 * Den is an essentially Java compatible intermediate representation, ready
 * for pretty printing into an actual Java token stream.  Unlike AST, which is
 * fuzzy and underspecified, nearly everything in Den is filled in, with a few
 * exception filled in my pretty printing such as qualified name references.
 *
 * The base Den class exists because AST does not distinguish between types,
 * callables, and expressions, and Semantics can simultaneously interpret a
 * piece of AST into any of the three options.  Normally, one uses
 *
 * TypeDen: A thin wrapper around a type
 * Package: A thin wrapper around a package reference
 * Callable: Something that can be falled (functions, constructors, etc.)
 * Exp: A Java expression
 * Stmt: A Java statement
 *
 * Denotations track source locations as best they can.  These locations are
 * pulled out of the corresponding AST location where possible, or filled it
 * approximately when not.
 */

package tarski

import tarski.AST.{ParenAExp, IntALit, AExp}
import utility.Utility._
import utility.Locations._
import tarski.Base._
import tarski.Environment.Env
import tarski.Items._
import tarski.Operators._
import tarski.Types._
import tarski.Scores._
import tarski.Tokens._
import tarski.Mods._
import scala.annotation.tailrec
import scala.language.implicitConversions

object Denotations {
  // The equivalent of Any in the denotation world
  // ExpOrType and friends are annoying Scala representations of union types (ExpOrType = Union(Exp,TypeDen))
  sealed trait Den
  sealed trait ParentDen extends Den
  sealed trait ExpOrType extends ParentDen {
    def item: TypeItem
  }
  sealed trait TypeOrCallable extends Den
  sealed trait TypeOrPackage extends Den
  sealed trait ExpOrCallable extends Den

  // Wrapped packages.  Items.Package inherits from this.
  trait PackageDen extends ParentDen with TypeOrPackage {
    def p: Package
  }

  // Wrapped types
  case class TypeDen(beneath: Type) extends ExpOrType with TypeOrCallable with TypeOrPackage {
    def item = beneath.item
    def array = TypeDen(ArrayType(beneath))

    def flatMap[A](f: Type => Scored[A]): Scored[A] = f(beneath)
  }

  // Classes
  sealed abstract class ClassBody extends HasRange
  case class TokClassBody(t: AnonBodyTok, r: SRange) extends ClassBody

  // Special type arguments class to avoid excessive boxing
  sealed abstract class ClassArgs
  case object NoArgs extends ClassArgs
  case class SomeArgs(args: List[TypeArg], a: SGroup, hide: Boolean) extends ClassArgs

  // Callables: stuff that can be called given some arguments
  sealed abstract class Callable extends ExpOrCallable with TypeOrCallable with Signature with HasRange {
    def tparams: List[TypeVar]
    def params: List[Type]
    def variadic: Boolean
    def callType(ts: List[TypeArg]): Type
  }
  sealed abstract class NormalCallable extends Callable
  sealed abstract class NotTypeApply extends NormalCallable
  case class TypeApply(c: NotTypeApply, ts: List[TypeArg], a: SGroup, hide: Boolean) extends NormalCallable {
    def r = c.r union a.r
    def tparams = Nil
    lazy val params = {
      // TODO: Map.empty may be wrong here
      implicit val env = capture(c.tparams,ts,Map.empty)._1
      c.params map (_.substitute)
    }
    def variadic = c.variadic
    lazy val result = c.callType(ts)
    def callType(ts2: List[TypeArg]) = ts2 match {
      case Nil => result
      case _ => throw new RuntimeException("TypeApply already has type arguments")
    }
  }
  case class MethodDen(x: Option[Exp], obj: Option[RefType], f: MethodItem, fr: SRange) extends NotTypeApply {
    def r = fr unionR x
    def dot = fr.before
    private lazy val parentEnv: Tenv = x match {
      case _ if f.isStatic => Map.empty
      case None => Map.empty
      case Some(x) => subItemType(x.ty,f.parent).get.env
    }
    def tparams = f.tparams
    def variadic = f.variadic
    lazy val params = f.params.map(_.substitute(parentEnv))
    lazy val result = if (f == GetClassItem) ClassObjectItem.generic(List(WildSub(obj.get.raw)))
                      else f.retVal.substitute(parentEnv) // = callType(Nil)
    def callType(ts: List[TypeArg]) = if (f == GetClassItem) if (ts.nonEmpty) impossible else result
                                      else f.retVal.substitute(capture(tparams,ts,parentEnv)._1)
  }
  object MethodDen {
    def apply(x: Option[Exp], f: MethodItem, fr: SRange)(implicit env: Env): MethodDen = {
      if (f != GetClassItem) MethodDen(x,None,f,fr)
      else x match {
        case x@Some(xx) => MethodDen(x,Some(xx.ty.asInstanceOf[RefType]),GetClassItem,fr)
        case None => MethodDen(None,Some(env.getThis.ty),GetClassItem,fr)
      }
    }
  }
  case class ForwardDen(x: ThisOrSuper, xr: SRange, f: ConstructorItem) extends NotTypeApply {
    def r = xr
    def tparams = f.tparams
    def variadic = f.variadic
    lazy val params = {
      implicit val env: Tenv = subItemType(x.ty,f.parent).get.env
      f.params map (_.substitute)
    }
    def result = VoidType
    def callType(ts: List[TypeArg]) = VoidType
  }
  // the full expression could be "parentObj.new<targs> type.Class<classArgs>", which is then a callable.
  // "parentObj." is needed only if Class is an inner class. The targs are not part of NewDen (which is why it's always
  // NotTypeApply, even if there are Some classArgs).
  case class NewDen(nr: SRange, parentObj: Option[Exp], f: ConstructorItem, fr: SRange,
                    classArgs: ClassArgs = NoArgs) extends NotTypeApply {
    def r = nr union (classArgs match { case NoArgs => fr; case SomeArgs(_,a,_) => fr union a.r })
    private lazy val parent = parentObj map (x => subItemType(x.ty,f.parent.parent.asInstanceOf[ClassItem]).get)
    private lazy val env: Tenv = {
      val parentEnv: Tenv = parent match {
        case None => Map.empty
        case Some(c) => c.env
      }
      classArgs match {
        case NoArgs => parentEnv
        case SomeArgs(ts,_,_) => capture(f.parent.tparams,ts,parentEnv)._1
      }
    }
    lazy val tparams = classArgs match {
      case NoArgs => f.parent.tparams ++ f.tparams // Try to infer both class and constructor parameters
      case _:SomeArgs => f.tparams // We already have the class type arguments
    }
    lazy val params = f.params map (_.substitute(env))
    def variadic = f.variadic
    lazy val result = f.parent.inside.substitute(env)
    def callType(ts: List[TypeArg]) = {
      val args = classArgs match {
        case NoArgs => ts.take(f.parent.arity)
        case SomeArgs(a,_,_) => a
      }
      val par = parent match {
        case Some(p) => p
        case None => f.parent.parent.raw // TODO: check that this is right
      }
      f.parent.generic(args,par)
    }
  }
  // ns: given array dimensions, ds: omitted array dimensions
  case class NewArrayDen(nr: SRange, t: Type, tr: SRange, ns: List[Grouped[Exp]], ds: List[SGroup]) extends Callable {
    def r = nr unionR ns union ds
    lazy val result = arrays(t,ns.size+ds.size)
    def callType(ts: List[TypeArg]) = { assert(ts.isEmpty); result }
    def variadic = ns.isEmpty
    def tparams = Nil
    def params = if (ns.nonEmpty) Nil
                 else List(arrays(t,ds.size)) // a single parameter which is an array initializer of the same dimension
  }

  // Add type arguments to a Callable without checking for correctness
  def uncheckedAddTypeArgs(f: Callable, ts: List[TypeArg], a: SGroup, hide: Boolean): Callable = f match {
    case _ if ts.isEmpty => f
    case _:TypeApply => impossible
    case NewDen(nr,p,f,fr,NoArgs) => val (ts0,ts1) = ts splitAt f.parent.arity
                                     uncheckedAddTypeArgs(NewDen(nr,p,f,fr,SomeArgs(ts0,a,hide)),ts1,a,hide)
    case f:NotTypeApply => TypeApply(f,ts,a,hide)
  }

  // Make either ApplyExp or an appropriate array creation
  def makeApply(f: Callable, args: List[Exp], a: SGroup, auto: Boolean): Exp = f match {
    case f:NormalCallable => ApplyExp(f,args,a,auto)
    case NewArrayDen(nr,t,tr,Nil,Nil) => impossible
    case NewArrayDen(nr,t,tr,Nil,ds) => ArrayExp(nr,arrays(t,ds.size-1),tr union ds.last.r,args,a)
    case NewArrayDen(nr,t,tr,ns,ds) => assert(args.isEmpty); EmptyArrayExp(nr,arrays(t,ds.size),tr,ns)
  }

  // Variable declarations.  The env is the environment *before* the declaration.
  type Dims = List[SGroup]
  case class VarDecl(x: Local, xr: SRange, d: Dims, i: Option[(SRange,Exp)], env: Env) extends HasRange {
    def r = i match { case None => xr; case Some((_,i)) => xr union i.r }

    // because when we're created, the variable we're declaring is made, we can't compare the local by reference.
    override def equals(o: Any) = o match {
      case o: VarDecl => (o.x isSame x) && xr == o.xr && d == o.d && i == o.i && env == o.env
      case _ => false
    }
  }

  // Statements
  sealed abstract class Stmt extends HasRange {
    def env: Env // The environment *before* the statement
    def envAfter: Env // The environment *after* the statement
    def isBlock: Boolean = false // Are we a block?
    def flatten: List[Stmt] = List(this) // Flatten MultipleStmts
  }
  sealed trait ForInit {
    def env: Env // The environment *before* the for initializer
    def envAfter: Env // The environment *after* the for initializer
  }
  case class SemiStmt(s: Stmt, sr: SRange) extends Stmt {
    def env = s.env
    override def envAfter = s.envAfter
    def r = s.r union sr
  }
  case class EmptyStmt(r: SRange, env: Env) extends Stmt {
    def envAfter = env
  }
  case class HoleStmt(r: SRange, env: Env) extends Stmt {
    def envAfter = env
  }
  case class VarStmt(m: Mods, t: Type, tr: SRange, vs: List[VarDecl], envAfter: Env) extends Stmt with ForInit {
    var env = vs.head.env
    def r = tr unionR vs unionR m
  }
  case class ExpStmt(e: StmtExp, env: Env) extends Stmt {
    def envAfter = env
    def r = e.r
  }
  case class BlockStmt(b: List[Stmt], a: SGroup, env: Env) extends Stmt {
    assert(b forall (!_.isInstanceOf[MultipleStmt]))
    override def isBlock = true
    def envAfter = env
    def r = a.lr
  }
  // Like a block statement, but local variables remain in scope afterwards.  Used by effect discarding.
  case class MultipleStmt(b: List[Stmt]) extends Stmt {
    assert(b forall (!_.isInstanceOf[MultipleStmt]))
    def env = b.head.env
    def envAfter = b.last.envAfter
    def r = b.head.r union b.last.r
    override def flatten = b
  }
  case class AssertStmt(ar: SRange, c: Exp, m: Option[(SRange,Exp)], env: Env) extends Stmt {
    def envAfter = env
    def r = ar union c.r union m.map(_._2.r)
  }
  case class BreakStmt(br: SRange, label: Option[Loc[Label]], env: Env) extends Stmt {
    def envAfter = env
    def r = br unionR label
  }
  case class ContinueStmt(cr: SRange, label: Option[Loc[Label]], env: Env) extends Stmt {
    def envAfter = env
    def r = cr unionR label
  }
  case class ReturnStmt(rr: SRange, e: Option[Exp], env: Env) extends Stmt {
    def envAfter = env
    def r = rr unionR e
  }
  case class ThrowStmt(tr: SRange, e: Exp, env: Env) extends Stmt {
    def envAfter = env
    def r = tr union e.r
  }
  case class IfStmt(ir: SRange, c: Exp, a: SGroup, x: Stmt) extends Stmt {
    def env = x.env
    def envAfter = env
    def r = ir union x.r
  }
  case class IfElseStmt(ir: SRange, c: Exp, a: SGroup, x: Stmt, er: SRange, y: Stmt) extends Stmt {
    assert(!x.isInstanceOf[IfStmt])
    def env = x.env
    def envAfter = env
    def r = ir union y.r
  }
  case class WhileStmt(wr: SRange, c: Exp, a: SGroup, s: Stmt) extends Stmt {
    def env = s.env
    def envAfter = env
    def r = wr union s.r
  }
  case class DoStmt(dr: SRange, s: Stmt, wr: SRange, c: Exp, a: SGroup) extends Stmt {
    def env = s.env
    def envAfter = env
    def r = dr union a.r
  }
  case class ForStmt(fr: SRange, i: ForInit, c: Option[Exp], sr: SRange, u: List[Exp], a: SGroup, s: Stmt) extends Stmt {
    def env = i.env
    def envAfter = env
    def r = fr union s.r
  }
  case class ForExps(i: List[Exp], sr: SRange, env: Env) extends ForInit {
    def envAfter = env
  }
  case class ForeachStmt(fr: SRange, m: Mods, t: Type, tr: SRange, v: Local, vr: SRange,
                         e: Exp, a: SGroup, s: Stmt, env: Env) extends Stmt {
    def envAfter = env
    def r = fr union s.r
  }
  case class SyncStmt(sr: SRange, e: Exp, a: SGroup, s: Stmt) extends Stmt {
    assert(s.isBlock)
    def env = s.env
    def envAfter = env
    def r = sr union s.r
  }
  case class CatchBlock(m: Mods, tr: SRange, v: Local, vr: SRange, a: SGroup, s: Stmt) extends HasRange {
    assert(s.isBlock)
    def r = s.r unionR m union tr union vr
  }
  case class TryStmt(tr: SRange, s: Stmt, cs: List[CatchBlock], f: Option[(SRange,Stmt)]) extends Stmt {
    f foreach {case (_,f) => assert(f.isBlock)}
    def env = s.env
    def envAfter = env
    def r = tr union s.r unionR cs unionR (f map (_._2))
  }
  case class TokStmt(t: StmtTok, r: SRange, env: Env) extends Stmt {
    override def isBlock = t.isBlock
    def envAfter = env
  }

  // It's all expressions from here
  sealed abstract class Exp extends ExpOrType with ExpOrCallable with HasRange {
    def ty: Type
    def item: TypeItem // Faster version of ty.item
  }
  sealed trait StmtExp extends Exp

  // Literals
  sealed abstract class Lit extends Exp with HasRange {
    def show: String
  }
  case class ByteLit(b: Byte, show: String, r: SRange) extends Lit {
    def ty = ByteType
    def item = ubByteItem
  }
  case class ShortLit(s: Short, show: String, r: SRange) extends Lit {
    def ty = ShortType
    def item = ubShortItem
  }
  case class IntLit(i: Int, show: String, r: SRange) extends Lit {
    def ty = IntType
    def item = ubIntItem
  }
  case class LongLit(l: Long, show: String, r: SRange) extends Lit {
    def ty = LongType
    def item = ubLongItem
  }
  case class StringLit(s: String, show: String, r: SRange) extends Lit {
    def ty = StringType
    def item = StringItem
  }
  case class FloatLit(f: Float, show: String, r: SRange) extends Lit {
    def ty = FloatType
    def item = ubFloatItem
  }
  case class DoubleLit(d: Double, show: String, r: SRange) extends Lit {
    def ty = DoubleType
    def item = ubDoubleItem
  }
  case class CharLit(c: Char, show: String, r: SRange) extends Lit {
    def ty = CharType
    def item = ubCharItem
  }
  case class BooleanLit(b: Boolean, r: SRange) extends Lit {
    def ty = BooleanType
    def item = ubBooleanItem
    def show = if (b) "true" else "false"
  }
  case class NullLit(r: SRange) extends Lit {
    def ty = NullType
    def item = NullType.item
    def show = "null"
  }

  // Expressions
  case class LocalExp(x: Local, r: SRange) extends Exp {
    def item = x.item
    def ty = x.ty
  }
  case class FieldExp(x: Option[Exp], f: FieldItem, fr: SRange) extends Exp {
    def r = fr unionR x
    def dot = fr.before
    def item = f.item
    def ty = if (f.isStatic || x.isEmpty) f.inside else {
      val t = x.get.ty
      val fp = f.parent
      collectOne(supers(t)){
        case t:ClassOrArrayType if t.item==fp => f.inside.substitute(t.env)
      }.getOrElse(throw new RuntimeException(s"Field $f not found in $t"))
    }
  }
  case class ThisOrSuperExp(i: ThisOrSuper, r: SRange) extends Exp {
    def item = i.item
    def ty = i.ty
  }
  case class CastExp(ty: Type, a: SGroup, e: Exp, gen: Boolean = false) extends Exp {
    def r = a.l union e.r
    def item = ty.item
  }
  sealed abstract class UnaryExp extends Exp {
    def op: UnaryOp
    def opr: SRange
    def e: Exp
    def r = opr union e.r
    def ty = unaryType(op,e.ty) getOrElse (throw new RuntimeException("type error"))
    def item = ty.item
  }
  case class ImpExp(op: ImpOp, opr: SRange, e: Exp) extends UnaryExp with StmtExp
  case class NonImpExp(op: NonImpOp, opr: SRange, e: Exp) extends UnaryExp
  case class BinaryExp(op: BinaryOp, opr: SRange, e0: Exp, e1: Exp) extends Exp {
    def r = e0.r union e1.r
    def ty = binaryType(op,e0.ty,e1.ty) getOrElse (throw new RuntimeException("type error: " + e0.ty + " " + op + " " + e1.ty))
    def item = ty.item
  }
  case class InstanceofExp(e: Exp, ir: SRange, t: Type, tr: SRange) extends Exp {
    def r = e.r union tr
    def ty = BooleanType
    def item = ubBooleanItem
  }
  case class AssignExp(op: Option[AssignOp], opr: SRange, left: Exp, right: Exp) extends StmtExp {
    def r = left.r union right.r
    def item = left.item
    def ty = left.ty
  }
  case class ParenExp(e: Exp, a: SGroup) extends Exp {
    def r = a.lr
    def item = e.item
    def ty = e.ty
  }
  case class ApplyExp(f: NormalCallable, args: List[Exp], a: SGroup, auto: Boolean) extends StmtExp {
    def r = f.r union a.r
    lazy val item = ty.item
    lazy val ty = f.callType(Nil)
  }
  case class IndexExp(e: Exp, i: Exp, a: SGroup) extends Exp {
    def r = e.r union a.r
    def item = e.ty match {
      case ArrayType(t) => t.item
      case _ => throw new RuntimeException("type error")
    }
    def ty = e.ty match {
      case ArrayType(t) => t
      case _ => throw new RuntimeException("type error")
    }
  }
  case class CondExp(c: Exp, qr: SRange, x: Exp, cr: SRange, y: Exp, ty: Type) extends Exp {
    def r = c.r union y.r
    def item = ty.item
  }
  case class ArrayExp(nr: SRange, t: Type, tr: SRange, i: List[Exp], a: SGroup) extends Exp { // t is the inner type
    def r = nr union a.lr
    def item = ArrayItem
    def ty = ArrayType(t)
  }
  case class EmptyArrayExp(nr: SRange, t: Type, tr: SRange, i: List[Grouped[Exp]]) extends Exp { // new t[i]
    def r = nr union i.head.r union i.last.r
    def item = ArrayItem
    def ty = i.foldLeft(t)((t,_) => ArrayType(t))
  }
  case class WhateverExp(ty: Type, r: SRange, s: Best[Exp]) extends Exp {
    def item = ty.item
  }
  case class AnonClassExp(c: Callable, as: List[Exp], ar: SGroup, body: ClassBody) extends StmtExp {
    def r = c.r union body.r
    def item = c.result.item
    def ty = c.result
  }

  def typeOf(e: Option[Exp]): Type = e match {
    case None => VoidType
    case Some(e) => e.ty
  }

  // Extract effects.  TODO: This discards certain exceptions, such as for casts, null errors, etc.
  def effects(e: Exp)(implicit env: Env): Scored[List[Stmt]] = e match {
    case e:StmtExp => known(List(ExpStmt(e,env)))
    case _:Lit|_:LocalExp|_:ThisOrSuperExp => single(Nil,Pr.dropPure)
    case _:WhateverExp => single(Nil,Pr.dropPure) // TODO: This is technically a bug, but avoids making the whole function monadic
    case CastExp(_,_,x,_) => dropPure(effects(x))
    case IndexExp(e,i,_) => dropPure(effects(e)++effects(i))
    case FieldExp(None,_,_) => single(Nil,Pr.dropPure)
    case FieldExp(Some(x),_,_) => dropPure(effects(x))
    case NonImpExp(_,_,x) => dropPure(effects(x))
    case InstanceofExp(x,_,_,_) => dropPure(effects(x))
    case BinaryExp(_,_,x,y) => dropPure(effects(x)++effects(y))
    case CondExp(c,qr,x,er,y,_) => productWith(effects(x),effects(y)){
      case (Nil,Nil) => Nil
      case (ex,Nil) => List(IfStmt(qr,c,SGroup.approx(c.r),blocked(ex)))
      case (Nil,ey) => List(IfStmt(qr,not(c),SGroup.approx(c.r),blocked(ey)))
      case (ex,ey) => List(IfElseStmt(qr,c,SGroup.approx(c.r),notIf(blocked(ex)),er,blocked(ey)))
    }
    case ArrayExp(_,_,_,xs,_) => dropPure(thread(xs)(effects) map (_.flatten))
    case EmptyArrayExp(_,_,_,is) => dropPure(thread(is)(i => effects(i.x)) map (_.flatten))
    case ParenExp(x,_) => effects(x)
  }
  private def dropPure[A](x: => Scored[A]): Scored[A] = biased(Pr.dropPure,x)

  def multiple(ss: List[Stmt]): Stmt = ss match {
    case Nil => impossible
    case List(s) => s
    case ss => MultipleStmt(ss flatMap (_.flatten))
  }

  def blocked(s: Stmt): Stmt = blockedHelper(s.flatten)
  def blocked(ss: List[Stmt]): Stmt = blockedHelper(ss flatMap (_.flatten))
  private[this] def blockedHelper(ss: List[Stmt]): Stmt = ss match {
    case Nil => impossible
    case List(s) => s
    case ss => BlockStmt(ss,SGroup.approx(ss.head.r union ss.last.r),ss.head.env)
  }

  // Make sure we're not a bare if
  def notIf(s: Stmt): Stmt = s match {
    case _:IfStmt => needBlock(s)
    case _ => s
  }

  def needBlock(s: Stmt): Stmt =
    if (s.isBlock) s
    else BlockStmt(List(s),SGroup.approx(s.r),s.env)

  def xor(x: Boolean, y: Exp): Exp = if (x) not(y) else y

  def not(e: Exp): Exp = e match {
    case BooleanLit(x,r) => BooleanLit(!x,r)
    case NonImpExp(NotOp,_,x) => x
    case BinaryExp(op:CompareOp,r,x,y) =>
      val flip = op match {
        case EqOp => NeOp
        case NeOp => EqOp
        case LtOp => GeOp
        case GeOp => LtOp
        case GtOp => LeOp
        case LeOp => GtOp
      }
      BinaryExp(flip,r,x,y)
    case _ => NonImpExp(NotOp,e.r.before,e)
  }

  def zero(r: SRange) = IntLit(0,"0",r)
  @tailrec def isZero(e: AExp): Boolean = e match {
    case IntALit("0",_) => true
    case ParenAExp(x,_) => isZero(x)
    case _ => false
  }
  def castZero(t: Type, r: SRange): Exp = t match {
    case BooleanType => BooleanLit(false,r)
    case SimpleType(BooleanItem,_) => BooleanLit(false,r)
    case _:RefType => NullLit(r)
    case _:NumType => zero(r)
    case VoidType => impossible
  }

  def addSemi(s: Stmt, sr: SRange): Stmt = s match {
    // If we're adding a semi, the desired location overrides an existing one
    case SemiStmt(x,_) => SemiStmt(s,sr)
    // Some statements need no semicolon
    case _:BlockStmt|_:SyncStmt|_:TryStmt => s
    case TokStmt(t,_,_) if t.isBlock => s
    // For if and similar, add a semicolon to the last substatement
    case IfStmt(ir,c,a,x) => IfStmt(ir,c,a,addSemi(x,sr))
    case IfElseStmt(ir,c,a,x,er,y) => IfElseStmt(ir,c,a,x,er,addSemi(y,sr))
    case WhileStmt(wr,c,a,s) => WhileStmt(wr,c,a,addSemi(s,sr))
    case ForStmt(fr,i,c,sr,u,a,s) => ForStmt(fr,i,c,sr,u,a,addSemi(s,sr))
    case ForeachStmt(fr,m,t,tr,v,vr,e,a,s,env) => ForeachStmt(fr,m,t,tr,v,vr,e,a,addSemi(s,sr),env)
    case MultipleStmt(b) => MultipleStmt(b.init ::: List(addSemi(b.last,sr)))
    // Otherwise, add a semicolon
    case _:EmptyStmt|_:HoleStmt|_:VarStmt|_:ExpStmt|_:AssertStmt|_:BreakStmt|_:ContinueStmt
        |_:ReturnStmt|_:ThrowStmt|_:DoStmt|_:TokStmt => SemiStmt(s,sr)
  }

  // Find all locals declared somewhere in here
  def locals(s: Stmt): List[Local] = s match {
    case BlockStmt(b,_,_) => b flatMap locals
    case MultipleStmt(b) => b flatMap locals
    case VarStmt(_,_,_,vs,_) => vs map (_.x)
    case ForStmt(_,i:VarStmt,_,_,_,_,fs) => locals(i) ::: locals(fs)
    case ForStmt(_,_,_,_,_,_,fs) => locals(fs)
    case ForeachStmt(_,_,_,_,v,_,_,_,fs,_) => v :: locals(fs)
    case SemiStmt(s,_) => locals(s)
    case TryStmt(_,ts,cs,fs) => (locals(ts) ::: (cs flatMap { case CatchBlock(_,_,v,_,_,s) => v :: locals(s) })
                                            ::: fs.toList.flatMap(x => locals(x._2)))
    case _ => Nil
  }

  // Does x contain field f?
  def containsField(x: ParentDen, f: Member): Boolean = (x,f.parent) match {
    case (x:ExpOrType,p:ClassOrArrayItem) => isSubitem(x.item,p)
    case (x:Package,p) => x.p eq p
    case _ => false
  }
}
