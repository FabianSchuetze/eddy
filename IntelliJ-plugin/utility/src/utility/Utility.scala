package utility

import scala.annotation.tailrec
import scala.collection.immutable.Set
import scala.collection.mutable

object Utility {
  def notImplemented = throw new NotImplementedError("not implemented")
  def impossible = throw new InternalError("impossible")

  def binarySearch[A](s: Seq[A], key: A)(implicit ord: math.Ordering[A]): Option[Int] = {
    var left: Int = 0
    var right: Int = s.length - 1
    while (right >= left) {
      val mid = left + (right - left) / 2
      val comp = ord.compare(s(mid), key)
      if (comp == 0) // s(mid) == key
        return Some(mid)
      else if (comp > 0) // s(mid) > key
        right = mid - 1
      else if (comp < 0) // s(mid) < key
        left = mid + 1
    }
    None
  }

  def toMapList[A,B](c: Iterable[(A,B)]): Map[A,List[B]] = {
    val m = mutable.Map[A,List[B]]()
    c.foreach { case (a,b) => m.update(a, b :: m.getOrElse(a,Nil)) }
    m.toMap
  }

  def addToMapList[A,B](m0: Map[A,List[B]], bs: Iterable[(A,B)]): Map[A,List[B]] = {
    val m = mutable.Map[A,List[B]]()
    m0.foreach { case (a,bs) => m.update(a, bs ::: m.getOrElse(a,Nil)) }
    bs.foreach { case (a,b) => m.update(a, b :: m.getOrElse(a,Nil)) }
    m.toMap
  }

  def toMapSet[A,B](c: Iterable[(A,B)]): Map[A,Set[B]] =
    toMapList(c) mapValues (_.toSet)

  def mergeMapSets[A,B](t: Map[A,Set[B]], t2: Map[A,Set[B]]): Map[A,Set[B]] =
    ((t.keySet ++ t2.keySet) map { s => (s, t.getOrElse(s,Set()) ++ t2.getOrElse(s,Set())) } ).toMap

  def doWhile(f: => Boolean): Unit =
    if (f) doWhile(f)

  def splitWhitespace(s: String): List[String] =
    s.split("""\s+""").toList match {
      case "" :: x => x
      case x => x
    }

  def intersperse[A](s: A, xs: List[A]): List[A] = xs match {
    case Nil => Nil
    case List(_) => xs
    case x::xs => x :: s :: intersperse(s,xs)
  }

  def silenceNulls[A >: Null](f: => A): A =
    try f catch { case _:NullPointerException => null }

  @tailrec
  def revAppend[A](xs: List[A], ys: List[A]): List[A] = xs match {
    case Nil => ys
    case x::xs => revAppend(xs,x::ys)
  }

  // Run-length encode a list
  def runs[A](xs: List[A]): List[(A,Int)] = {
    @tailrec def loop(next: List[A], prev: List[(A,Int)]): List[(A,Int)] = next match {
      case Nil => prev.reverse
      case n::ns => loop(ns,prev match {
        case (p,i)::ps if p==n => (p,i+1)::ps
        case ps => (n,1)::ps
      })
    }
    loop(xs,Nil)
  }
  def unruns[A](xs: List[(A,Int)]): List[A] = xs flatMap {case (a,n) => List.fill(n)(a)}

  // Chop a list up into segments equal according to a predicate
  def segmentBy[A](xs: List[A])(f: (A,A) => Boolean): List[List[A]] = xs match {
    case Nil => Nil
    case x::xs =>
      @tailrec def loop(done: List[List[A]], cur: List[A], x: A, rest: List[A]): List[List[A]] = rest match {
        case Nil => (cur.reverse :: done).reverse
        case y::ys if f(x,y) => loop(done,y::cur,y,ys)
        case y::ys => loop(cur::done,List(y),y,ys)
      }
      loop(Nil,List(x),x,xs)
  }

  def escape(raw: String): String = {
    import scala.reflect.runtime.universe._
    Literal(Constant(raw)).toString
  }

  // Iterate a function until referential equality fixpoint is reached
  def fixRef[A <: AnyRef](x: A)(f: A => A): A = {
    val fx = f(x)
    if (x eq fx) x else fixRef(fx)(f)
  }

  def allSome[A](xs: List[Option[A]]): Option[List[A]] = xs match {
    case Nil => Some(Nil)
    case None::_ => None
    case Some(x)::xs => allSome(xs) match {
      case None => None
      case Some(xs) => Some(x::xs)
    }
  }
  def allSome[A](xs: Set[Option[A]]): Option[Set[A]] = allSome(xs.toList) map (_.toSet)

  def collectOne[A,B](xs: List[A])(f: PartialFunction[A,B]): Option[B] = xs match {
    case Nil => None
    case x::_ if f.isDefinedAt(x) => Some(f(x))
    case _::xs => collectOne(xs)(f)
  }
  def collectOne[A,B](xs: Set[A])(f: PartialFunction[A,B]): Option[B] = collectOne(xs.toList)(f)

  // Memoize the fixpoint of a recursive function.  Usage:
  //   lazy val f = fixpoint(base, a => b) // where b refers to f
  def fixpoint[A,B](base: B, f: A => B): A => B = {
    val done = mutable.Map[A,B]()
    val next = mutable.Map[A,B]()
    val active = mutable.Set[A]()
    var changed = false
    var outer = true
    def fix(a: A): B = done.getOrElse(a, {
      def inner = next get a match {
        case None =>
          changed = true
          active += a
          next(a) = base
          val b = f(a)
          if (b != base)
            next(a) = b
          b
        case Some(b) =>
          if (active contains a)
            b
          else {
            active += a
            val c = f(a)
            if (b != c) {
              changed = true
              next(a) = c
            }
            c
          }
      }
      if (!outer) inner
      else {
        outer = false
        def loop: B = {
          val b = inner
          if (changed) {
            changed = false
            active.clear()
            loop
          } else {
            outer = true
            done ++= next
            next.clear()
            active.clear()
            b
          }
        }
        loop
      }
    })
    fix
  }
  def fixpoint[A,B,C](base: C, f: (A,B) => C): (A,B) => C = {
    lazy val g: ((A,B)) => C = fixpoint(base, x => f(x._1,x._2))
    (a,b) => g((a,b))
  }

  // Memoization.  Usage:
  //   val f = memoize(a => b)      // b doesn't depend on f
  //   lazy val f = memoize(a => b) // b depends on f, but with laziness on all cycles
  def memoize[A,B](f: A => B): A => B = {
    val cache = mutable.Map[A,B]()
    def mem(a: A): B = cache.getOrElse(a,{
      val b = f(a)
      cache(a) = b
      b
    })
    mem
  }
  def memoize[A,B,C](f: (A,B) => C): (A,B) => C = {
    val cache = mutable.Map[(A,B),C]()
    def mem(a: A, b: B): C = cache.getOrElse((a,b),{
      val c = f(a,b)
      cache((a,b)) = c
      c
    })
    mem
  }

  // Write to a file.  From http://stackoverflow.com/questions/4604237/how-to-write-to-a-file-in-scala
  def writeTo(f: java.io.File)(op: java.io.PrintWriter => Unit) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  // Create and then destroy a temporary file
  def withTemp[A](prefix: String, suffix: String, delete: Boolean = true)(f: java.io.File => A): A = {
    val file = java.io.File.createTempFile(prefix,suffix)
    try { f(file) } finally { if (delete) file.delete }
  }

  // Trait for comparison by referential equality
  trait RefEq extends AnyRef {
    override def hashCode = System.identityHashCode(this)
    override def equals(x: Any) = x match {
      case x:AnyRef => this eq x
      case _ => false
    }
  }

  def scoped[A](name: String, f: => A): A = {
    JavaUtils.pushScope(name)
    try f finally JavaUtils.popScope()
  }
  def silenced[A](f: => A): A = {
    val s = JavaUtils.skipScopes
    JavaUtils.skipScopes = true
    try f finally JavaUtils.skipScopes = s
  }

  // Check if an error was generated by the Scala plugin
  def fromScalaPlugin(e: Throwable): Boolean =
    e.getStackTrace.exists(_.getClassName contains "org.jetbrains.plugin.scala.")

  // Dodge checked exception compile errors for a block of code
  abstract class Unchecked[A] { @throws(classOf[Exception]) def apply: A }
  def unchecked[A](f: Unchecked[A]): A = f.apply

  // Tuple construction from Java
  def tuple[A,B]    (a: A, b: B):             (A,B)     = (a,b)
  def tuple[A,B,C]  (a: A, b: B, c: C):       (A,B,C)   = (a,b,c)
  def tuple[A,B,C,D](a: A, b: B, c: C, d: D): (A,B,C,D) = (a,b,c,d)
}