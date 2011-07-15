/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2011, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.util.control

/** A class that can be instantiated for the break control abstraction.
 *  Example usage:
 *  {{{
 *  val mybreaks = new Breaks
 *  import mybreaks.{break, breakable}
 *
 *  breakable {
 *    for (...) {
 *      if (...) break
 *    }
 *  }
 *  }}}
 *  Calls to break from one instantiation of `Breaks` will never
 *  target breakable objects of some other instantiation.
 */  
class Breaks {

  private val breakException = new BreakControl

  /** A block from which one can exit with a `break`. */
  def breakable(op: => Unit) {
    try {
      op
    } catch {
      case ex: BreakControl =>
        if (ex ne breakException) throw ex
    }
  }

  trait TryBlock {
    def catchBreak(onBreak: => Unit): Unit
  }

  def tryBreakable(op: => Unit) = new TryBlock {
    def catchBreak(onBreak: => Unit) = try {
      op
    } catch {
      case ex: BreakControl =>
        if (ex ne breakException) throw ex
        onBreak
    }
  }

  /** Break from dynamically closest enclosing breakable block.
   *
   *  @note This might be different than the statically closest enclosing block!
   */
  def break() { throw breakException }
} 

/** An object that can be used for the break control abstraction.
 *  Example usage:
 *  {{{
 *  import Breaks.{break, breakable}
 *
 *  breakable {
 *    for (...) {
 *      if (...) break
 *    }
 *  }
 *  }}}
 */  
object Breaks extends Breaks

private class BreakControl extends ControlThrowable
