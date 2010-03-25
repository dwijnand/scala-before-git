/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

// generated by genprod on Sun Jan 17 19:38:09 PST 2010  

package scala


object Product2 {
  def unapply[T1, T2](x: Product2[T1, T2]): Option[Product2[T1, T2]] = 
    Some(x)
}

/** Product2 is a cartesian product of 2 components.
 *  
 *  @since 2.3
 */
trait Product2[@specialized("Int, Long, Double") +T1, @specialized("Int, Long, Double") +T2] extends Product {
  /**
   *  The arity of this product.
   *  @return 2
   */
  override def productArity = 2

  /**
   *  Returns the n-th projection of this product if 0&lt;=n&lt;arity,
   *  otherwise null.
   *
   *  @param n number of the projection to be returned 
   *  @return  same as _(n+1)
   *  @throws  IndexOutOfBoundsException
   */
  @throws(classOf[IndexOutOfBoundsException])
  override def productElement(n: Int) = n match { 
    case 0 => _1
    case 1 => _2
    case _ => throw new IndexOutOfBoundsException(n.toString())
 }  

  /** projection of this product */
  def _1: T1

  /** projection of this product */
  def _2: T2



}
