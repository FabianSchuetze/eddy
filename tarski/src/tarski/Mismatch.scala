/* Mismatch: Repair mismatched parentheses in token streams
 *
 * Mismatch is activated only if the grouping in a token stream doesn't
 * add up, in which case it attempts to add or remove grouping tokens to
 * best fix the problem.  Currently, grouping is added only adjacent to
 * grouping which is already there, or at the beginning or end of the string.
 */

package tarski

import utility.Utility._
import utility.Locations._
import tarski.Scores._
import tarski.JavaScores._
import tarski.Tokens._
import scala.annotation.tailrec
import scala.math._

object Mismatch {
  case class Seg(t: Token, r: SRange, after: Boolean, ps: List[Loc[Token]])
  type Segments = List[Seg]

  def matched(ks: Segments): Boolean = {
    @tailrec
    def loop(ks: Segments, d: Int): Boolean = ks match {
      case Nil => d == 0
      case Seg(_:LeftTok, _,_,k)::r => loop(r,d+k.size)
      case Seg(_:RightTok,_,_,k)::r => val kn = k.size
                                       d >= kn && loop(r,d-kn)
      case _::r => loop(r,d)
    }
    loop(ks,0)
  }

  // Penalty turning from parens into to parens
  def pr(from: Int, to: Int, after: Boolean): Prob = Prob(s"change parens $from -> $to",
    if (from == to) 1
    else if (from == 0)
      if (after && to==1) .9 // Adding parentheses after if and such is cheap
      else 1.0 / (1+to)
    else from.toDouble / (from+abs(from-to))
  )

  // Ensure that ps starts and ends with all kinds of parentheses
  def ensure(ps: Segments): Segments = {
    @tailrec def startsWith(t: Token, ps: Segments): Boolean = ps match {
      case p::r => t==p.t || (p.ps.isEmpty && startsWith(t,r))
      case _ => false
    }
    def add(t: Token, r: SRange, after: Boolean, ps: Segments): Segments =
      if (startsWith(t,ps)) ps else Seg(t,r,after,Nil)::ps
    def after(ps: Segments): Segments = ps match {
      case Nil => Nil
      case p::ps =>
        val aps = after(ps)
        p :: (p.t match {
          case IfTok|_:ElifTok|WhileTok|UntilTok|SynchronizedTok => add(LParenTok,p.r.after,after=true,aps)
          case _ => aps
        })
    }
    val rL = ps.head.r
    val rR = ps.last.r
    def L(t: Token, r: Segments) = add(t,rL,after=false,r)
    def R(t: Token, r: Segments) = add(t,rR,after=false,r)
    after(L(LCurlyTok,L(LParenTok,L(LBrackTok,R(RightAnyTok,ps.reverse).reverse))))
  }

  // Coerce a segment of one length into another, keeping track of as much location information as possible
  def tweak(p: Token, r: SRange, ps: List[Loc[Token]], n: Int): List[Loc[Token]] = ps match {
    case Nil =>
      val pr = Loc(p,r)
      List.fill(n)(pr)
    case _ =>
      val m = ps.size
      if (n < m) ps.take(n/2) ::: ps.drop(m-(n+1)/2)
      else ps splitAt (m/2) match {
        case (s0,s1@(Loc(_,r)::_)) =>
          val q = Loc(p,r)
          s0 ::: List.fill(n-m)(q) ::: s1
        case (_,Nil) => impossible
      }
  }

  // Make at most n mutations to a kind stream
  def mutate(rs: Segments, n: Int): Scored[Segments] =
    if (n == 0) known(rs)
    else rs match {
      case Nil => known(Nil)
      case Seg(k@(_:LeftTok|_:RightTok),r,after,is)::rs =>
        val i = is.size
        val j = listGood(for (j <- (max(0,i-n) to (i+n)).toList) yield Alt(pr(i,j,after),j))
        j flatMap (j => mutate(rs,n-abs(i-j)) map (Seg(k,r,after=false,tweak(k,r,is,j))::_))
      case o::rs => mutate(rs,n) map (o::_)
    }

  def kindErrors(ts: List[Loc[Token]]): Int = {
    @tailrec def loop(ts: List[Token], stack: List[Token], errors: Int): Int = (ts,stack) match {
      case (Nil,Nil) => errors
      case ((t@(LParenTok|LBrackTok|LCurlyTok))::ts,s) => loop(ts,t::s,errors)
      case (RightAnyTok::ts,_::s) => loop(ts,s,errors)
      case (RParenTok::ts,LParenTok::s) => loop(ts,s,errors)
      case (RBrackTok::ts,LBrackTok::s) => loop(ts,s,errors)
      case (RCurlyTok::ts,LCurlyTok::s) => loop(ts,s,errors)
      case ((RParenTok|RBrackTok|RCurlyTok)::ts,_::s) => loop(ts,s,errors+1)
      case (_::ts,s) => loop(ts,s,errors)
      case (Nil,_) => impossible
    }
    loop(ts map (_.x),Nil,0)
  }

  def repair(ts: List[Loc[Token]]): Scored[List[Loc[Token]]] = if (ts.isEmpty) known(ts) else {
    val rs = ensure(segmentBy(ts)(_.x==_.x) map {
      case ps@(Loc(t,r)::_) => Seg(t,r,after=false,ps)
      case _ => impossible
    })
    if (matched(rs)) known(ts)
    else {
      // Mutate at most 2 times
      val ts0 = mutate(rs,2).filter(matched,"Mismatched parentheses") map (s => s.map(_.ps).flatten)
      val ts1 = ts0 flatMap (ts => single(ts,Prob("kind errors",pow(.25,kindErrors(ts)))))
      if (false) {
        println("repaired to:")
        implicit val f = fullShowFlags
        for (Alt(p,ts) <- ts1.stream.toList)
          println(s"  $p ${kindErrors(ts)}: ${ts map (_.x.show) mkString " "}")
      }
      ts1
    }
  }
}
